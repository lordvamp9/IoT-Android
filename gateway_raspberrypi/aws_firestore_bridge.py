"""Puente AWS IoT Core MQTT -> Cloud Firestore -> comandos MQTT.

Requiere GOOGLE_APPLICATION_CREDENTIALS y certificado de cliente AWS distintos
de los que usa el ESP32. No inicia conexiones al importarse.
"""

import hashlib
import json
import os
import ssl
import threading
import time

import firebase_admin
from firebase_admin import credentials as firebase_credentials, firestore
import paho.mqtt.client as mqtt

DEVICE_ID = "ESP32_AGRO_01"
COMMAND_TTL_SECONDS = 30
MAX_PENDING = 100


def valid_telemetry(data):
    if not isinstance(data, dict) or data.get("dispositivoId") != DEVICE_ID:
        return False
    try:
        temp = float(data["temperatura"])
        hum = float(data["humedad"])
        ts = int(data["timestamp"])
        checksum = data["checksum"]
        if not (-40 <= temp <= 80 and 0 <= hum <= 100):
            return False
        canonical = f"{DEVICE_ID}|{temp:.1f}|{hum:.1f}|{ts}"
        expected = hashlib.sha256(canonical.encode()).hexdigest()[:8]
        return isinstance(checksum, str) and checksum.lower() == expected
    except (KeyError, TypeError, ValueError):
        return False


def valid_command(doc_id, data):
    if not isinstance(data, dict) or data.get("id") != doc_id or not doc_id.isalnum():
        return False
    if data.get("deviceId") != DEVICE_ID or data.get("name") not in ("PING", "VENT", "BOMBA", "MODO"):
        return False
    if type(data.get("value")) is not int or data["value"] not in (0, 1):
        return False
    try:
        return abs(time.time() - int(data["createdAt"])) <= COMMAND_TTL_SECONDS
    except (KeyError, TypeError, ValueError):
        return False


class Bridge:
    def __init__(self, database):
        self.db = database
        self.prefix = os.environ["MQTT_TOPIC_PREFIX"].strip("/")
        self.telemetry = f"{self.prefix}/telemetria"
        self.control = f"{self.prefix}/control"
        self.ack = f"{self.prefix}/ack"
        self.last_send = {}
        self.lock = threading.Lock()
        self.client = mqtt.Client(client_id="andres_RaspberryPi")
        self.client.tls_set(
            ca_certs=os.environ["MQTT_CA_CERT"],
            certfile=os.environ["MQTT_CLIENT_CERT"],
            keyfile=os.environ["MQTT_PRIVATE_KEY"],
            cert_reqs=ssl.CERT_REQUIRED,
            tls_version=ssl.PROTOCOL_TLS_CLIENT,
        )
        self.client.on_connect = self.on_connect
        self.client.on_message = self.on_message

    def on_connect(self, client, userdata, flags, rc):
        if rc == 0:
            client.subscribe([(self.telemetry, 1), (self.ack, 1)])
            print("Conectado a AWS IoT Core")
        else:
            print(f"AWS IoT Core rechazó conexión: {rc}")

    def on_message(self, client, userdata, message):
        try:
            data = json.loads(message.payload.decode("utf-8"))
            if message.topic == self.telemetry:
                if not valid_telemetry(data):
                    print("Telemetría rechazada por formato o checksum")
                    return
                key = f"{DEVICE_ID}_{int(data['timestamp'])}"
                self.db.collection("historial").document(key).set(data)
                self.db.collection("telemetria").document(DEVICE_ID).set(data)
                if isinstance(data.get("actuador_ventilacion"), bool) and isinstance(data.get("actuador_bomba"), bool):
                    self.db.collection("actuadores").document(DEVICE_ID).set({
                        "ventilacion": data["actuador_ventilacion"], "bomba": data["actuador_bomba"]
                    }, merge=True)
            elif message.topic == self.ack:
                self.handle_ack(data)
        except (UnicodeDecodeError, ValueError, TypeError) as exc:
            print(f"Mensaje MQTT inválido: {type(exc).__name__}")
        except Exception as exc:
            print(f"Firestore no disponible; mensaje MQTT no confirmado: {type(exc).__name__}")

    def handle_ack(self, data):
        if not isinstance(data, dict):
            return
        command_id = data.get("id")
        if not isinstance(command_id, str) or not command_id.isalnum():
            return
        if data.get("dispositivoId") != DEVICE_ID:
            return
        ref = self.db.collection("commands").document(command_id)
        snapshot = ref.get()
        if not snapshot.exists:
            return
        command = snapshot.to_dict()
        if not valid_command(command_id, command) or command.get("status") not in ("PENDING", "SENT"):
            return
        success = data.get("ok") is True
        ref.update({"status": "ACK" if success else "NACK"})
        self.db.collection("respuestas").document(command_id).set({**data, "receivedAt": int(time.time())})
        if success and command["name"] in ("VENT", "BOMBA"):
            self.db.collection("actuadores").document(DEVICE_ID).set({
                "ventilacion" if command["name"] == "VENT" else "bomba": bool(command["value"])
            }, merge=True)
        with self.lock:
            self.last_send.pop(command_id, None)

    def poll_commands(self):
        while True:
            try:
                query = self.db.collection("commands").where("status", "in", ["PENDING", "SENT"]).limit(MAX_PENDING)
                for snapshot in query.stream():
                    command = snapshot.to_dict()
                    if not valid_command(snapshot.id, command):
                        snapshot.reference.update({"status": "EXPIRED"})
                        continue
                    with self.lock:
                        last = self.last_send.get(snapshot.id, 0)
                    if not self.client.is_connected() or time.monotonic() - last < 3:
                        continue
                    result = self.client.publish(self.control, json.dumps(command), qos=1)
                    if result.rc == mqtt.MQTT_ERR_SUCCESS:
                        with self.lock:
                            self.last_send[snapshot.id] = time.monotonic()
                        snapshot.reference.update({"status": "SENT"})
            except Exception as exc:
                print(f"Consulta de comandos pendiente: {type(exc).__name__}")
            time.sleep(2)

    def run(self):
        self.client.connect(os.environ["MQTT_BROKER"], 8883, keepalive=60)
        self.client.loop_start()
        try:
            self.poll_commands()
        finally:
            self.client.loop_stop()
            self.client.disconnect()


def main():
    required = ("GOOGLE_APPLICATION_CREDENTIALS", "MQTT_BROKER", "MQTT_CA_CERT",
                "MQTT_CLIENT_CERT", "MQTT_PRIVATE_KEY", "MQTT_TOPIC_PREFIX")
    if any(not os.environ.get(name) for name in required):
        raise SystemExit("Falta configuración; ver config.example.env")
    firebase_admin.initialize_app(firebase_credentials.Certificate(os.environ["GOOGLE_APPLICATION_CREDENTIALS"]))
    Bridge(firestore.client()).run()


if __name__ == "__main__":
    main()
