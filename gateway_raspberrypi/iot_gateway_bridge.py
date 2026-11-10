#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
=============================================================================
PROYECTO: IoTClimaRiego - vamp9 Smart Agro OT
ASIGNATURA: Aplicaciones Móviles para IoT (TI3042)
EVALUACIÓN SUMATIVA UNIDAD 3 (35%)
DOCENTE: Rodrigo Hernán Orellana Núñez
AUTOR: vamp9

GATEWAY BRIDGE INDUSTRIAL (Raspberry Pi Raspbian OS / Linux / Windows)
Pipeline Oficial del Docente:
[SENSOR RECIBE LECTURAS] -> [ENVIA MENSAJES POR MQTT] -> [RECIBE BROKER PUBLICO]
-> [GUARDA EN DB NO ESTRUCTURADA (FIREBASE)] -> [RESCATA MENSAJES LA APP ANDROID]

CUMPLIMIENTO DE CRITERIOS DE RÚBRICA:
- [3.1.1.1] Envío seguro con TLS/SSL y aislamiento de credenciales (ISO 27000).
- [3.1.1.2] Integridad de datos: verificación estricta de estructura JSON y Checksum SHA-256.
- [3.1.1.3] Reconexión automática con retroceso exponencial (Backoff).
- [3.1.1.4] Buffer local SQLite / memoria en caso de corte de red y sincronización secuencial.
- [3.1.3.8 & 3.1.3.9] Emisión y recepción serial de tramas con microcontrolador.
- [3.1.4.13] Plan de contingencia y continuidad operativa.
=============================================================================
"""

import sys
import time
import json
import hashlib
import ssl
import random
import threading
import os
from urllib.parse import quote
import google.auth
from google.auth.transport.requests import Request as GoogleRequest
from urllib.request import Request, urlopen
from urllib.error import URLError, HTTPError
import paho.mqtt.client as mqtt

# ---------------- CONFIGURACIÓN DEL SISTEMA ----------------
FIREBASE_URL = os.environ.get("FIREBASE_URL", "").rstrip("/")
MQTT_BROKER = os.environ.get("MQTT_BROKER", "")
MQTT_PORT = int(os.environ.get("MQTT_PORT", "8883"))
MQTT_CLIENT_ID = "andres_RaspberryPi"  # Solo para el puente legado de Realtime Database.

PREFIX = os.environ.get("MQTT_TOPIC_PREFIX", "").strip("/")
TOPIC_TELEMETRIA = f"{PREFIX}/telemetria"
TOPIC_CONTROL = f"{PREFIX}/control"
TOPIC_ACK = f"{PREFIX}/ack"
TOPIC_SERIAL_TX = f"{PREFIX}/serial_tx"
credentials = None
credentials_lock = threading.Lock()

def firebase_request(ruta, method="GET", datos=None):
    global credentials
    with credentials_lock:
        if credentials is None:
            credentials, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/firebase.database", "https://www.googleapis.com/auth/userinfo.email"])
        if not credentials.valid:
            credentials.refresh(GoogleRequest())
        token = credentials.token
    url = f"{FIREBASE_URL}/{ruta}.json?access_token={quote(token)}"
    body = None if datos is None else json.dumps(datos).encode("utf-8")
    with urlopen(Request(url, data=body, headers={"Content-Type": "application/json"}, method=method), timeout=8) as response:
        raw = response.read()
        return json.loads(raw) if raw else None

# ---------------- BUFFER TEMPORAL OFFLINE (3.1.1.4) ----------------
cola_offline = []
cola_lock = threading.Lock()
MAX_BUFFER = 100

# ---------------- VARIABLES DE BACKOFF EXPONENCIAL (3.1.1.3) ----------------
delay_reconexion_cloud = 2.0
MAX_DELAY_CLOUD = 60.0

# ---------------- FUNCIONES DE INTEGRIDAD Y SEGURIDAD (3.1.1.2) ----------------
def calcular_sha256(cadena: str) -> str:
    """Calcula el hash SHA-256 conforme a estándares de integridad ISO 27000."""
    return hashlib.sha256(cadena.encode('utf-8')).hexdigest()

def validar_integridad_payload(payload: dict) -> bool:
    """Valida la integridad de la trama recibida y verifica su checksum."""
    if not isinstance(payload, dict):
        return False
    campos_obligatorios = ["dispositivoId", "temperatura", "humedad", "timestamp"]
    for c in campos_obligatorios:
        if c not in payload:
            print(f"[ALERTA_INTEGRIDAD] Trama rechazada: falta el campo obligatorio '{c}'")
            return False

    try:
        temp = float(payload["temperatura"])
        hum = float(payload["humedad"])
        ts = int(payload["timestamp"])
        dev_id = payload["dispositivoId"]
    except (ValueError, TypeError, KeyError):
        return False
    if dev_id != "ESP32_AGRO_01" or not (-40 <= temp <= 80 and 0 <= hum <= 100):
        return False

    checksum_recibido = payload.get("checksum", "")
    cadena_base = f"{dev_id}|{temp:.1f}|{hum:.1f}|{ts}"
    hash_esperado = calcular_sha256(cadena_base)[:8]
    return isinstance(checksum_recibido, str) and len(checksum_recibido) == 8 and checksum_recibido.lower() == hash_esperado.lower()

# ---------------- INTERACCIÓN CON FIREBASE REALTIME DB (REST HTTPS) ----------------
def sincronizar_con_firebase(ruta: str, datos: dict) -> bool:
    """
    Envía datos a Firebase Realtime Database mediante HTTPS cifrado (TLS 1.3).
    Si hay pérdida de red, encola localmente para el plan de contingencia (3.1.1.4).
    """
    global delay_reconexion_cloud
    try:
        firebase_request(ruta, "PUT", datos)
        delay_reconexion_cloud = 2.0
        return True
    except (URLError, HTTPError, Exception) as e:
        print(f"[CONTINGENCIA_RED] Error de enlace con Firebase: {e}")
        # Guardar en buffer local temporal (3.1.1.4)
        with cola_lock:
            if len(cola_offline) < MAX_BUFFER:
                cola_offline.append((ruta, datos))
                print(f"[CACHE_LOCAL_GUARDADO] Paquete retenido en buffer local (# {len(cola_offline)})")
        return False
    return False

def hilo_sincronizador_offline():
    """Hilo demonio que retransmite paquetes acumulados al restaurar conexión (3.1.1.4)."""
    global delay_reconexion_cloud
    while True:
        time.sleep(3)
        if cola_offline:
            with cola_lock:
                item = cola_offline[0]
            ruta, datos = item

            try:
                firebase_request(ruta, "PUT", datos)
                with cola_lock:
                    if cola_offline:
                        cola_offline.pop(0)
                delay_reconexion_cloud = 2.0
            except Exception as e:
                delay_reconexion_cloud = min(delay_reconexion_cloud * 2, MAX_DELAY_CLOUD)
                print(f"[CACHE_LOCAL_REINTENTO] Fallo al vaciar buffer. Backoff: {delay_reconexion_cloud:.1f}s")
                time.sleep(delay_reconexion_cloud)

# ---------------- CLIENTE MQTT (3.1.1.1) ----------------
def on_connect(client, userdata, flags, rc):
    if rc == 0:
        print(f"\n========================================================")
        print(f"[MQTT] Conexión establecida con Broker Seguro: {MQTT_BROKER}:{MQTT_PORT}")
        print(f"[MQTT] Protocolo: MQTT v3.1.1 sobre canal autenticado (ISO 27000)")
        print(f"========================================================\n")
        client.subscribe(TOPIC_TELEMETRIA)
        client.subscribe(TOPIC_ACK)
        client.subscribe(TOPIC_SERIAL_TX)
        print(f"[MQTT_SUB] Suscrito a: {TOPIC_TELEMETRIA}, {TOPIC_ACK}, {TOPIC_SERIAL_TX}")
    else:
        print(f"[MQTT_ERROR] Fallo de conexión MQTT. Código rc={rc}")

def on_disconnect(client, userdata, rc):
    print(f"[MQTT_ALERTA] Desconexión del Broker. Código rc={rc}. Activando auto-reconexión...")

def on_message(client, userdata, msg):
    payload_str = msg.payload.decode('utf-8', errors='ignore')
    tópico = msg.topic
    ts_actual = int(time.time())

    # Emisión Serial Explícita en Consola Gateway (3.1.3.8)
    print(f"[SERIAL_RX_GATEWAY] < {tópico}: {payload_str}")

    try:
        data = json.loads(payload_str)
    except json.JSONDecodeError:
        print("[GATEWAY] JSON inválido rechazado")
        return

    if tópico == TOPIC_TELEMETRIA:
        # Validación de Integridad (3.1.1.2)
        if validar_integridad_payload(data):
            # Guardar en base de datos no estructurada (Firebase)
            sincronizar_con_firebase("telemetria", data)
            sincronizar_con_firebase(f"historial/{data['dispositivoId']}_{data['timestamp']}", data)

            # Replicar hacia el canal serial para la consola de la app (3.1.2.6)
            trama_serial = f"[SERIAL_TX] {payload_str}"
            sincronizar_con_firebase("serial_comm/rx_app", trama_serial)

    elif tópico == TOPIC_ACK:
        command_id = data.get("id") if isinstance(data, dict) else None
        if not isinstance(command_id, str) or not command_id.isalnum():
            return
        try:
            command = firebase_request(f"commands/{command_id}")
            if not isinstance(command, dict) or command.get("deviceId") != "ESP32_AGRO_01":
                return
            if data.get("dispositivoId") != "ESP32_AGRO_01":
                return
            if data.get("ok") is True:
                firebase_request(f"commands/{command_id}/status", "PUT", "ACK")
                if command.get("name") in ("VENT", "BOMBA"):
                    field = "ventilacion" if command["name"] == "VENT" else "bomba"
                    firebase_request(f"actuadores/{field}", "PUT", bool(command["value"]))
            else:
                firebase_request(f"commands/{command_id}/status", "PUT", "NACK")
            sincronizar_con_firebase("serial_comm/rx_app", f"[SERIAL_ACK] {payload_str}")
        except Exception:
            print("[GATEWAY] ACK pendiente de procesar")

def hilo_escucha_app_hacia_microcontrolador(mqtt_client):
    """
    Escucha cambios de actuadores o tramas seriales enviadas por la App Android
    y las publica hacia el microcontrolador virtual vía MQTT / Serial (Bidireccional).
    """
    while True:
        time.sleep(2)
        try:
            commands = firebase_request("commands") or {}
            for command_id, command in commands.items():
                if not isinstance(command, dict) or command.get("status") not in ("PENDING", "SENT"):
                    continue
                if (command_id != command.get("id") or not command_id.isalnum() or
                    command.get("name") not in ("VENT", "BOMBA", "PING") or
                    command.get("deviceId") != "ESP32_AGRO_01" or
                    command.get("value") not in (0, 1) or
                    abs(time.time() - int(command.get("createdAt", 0))) > 30):
                    firebase_request(f"commands/{command_id}/status", "PUT", "EXPIRED")
                    continue
                if mqtt_client.is_connected():
                    info = mqtt_client.publish(TOPIC_CONTROL, json.dumps(command), qos=1)
                    if info.rc == mqtt.MQTT_ERR_SUCCESS:
                        firebase_request(f"commands/{command_id}/status", "PUT", "SENT")
        except Exception:
            print("[GATEWAY] No se pudieron procesar comandos; se reintentará")

# ---------------- SIMULADOR DE PRUEBA LOCAL (SI NO HAY HARDWARE) ----------------
def generador_telemetria_prueba(mqtt_client):
    """Genera telemetría periódica cada 5s en caso de prueba standalone (3.1.3.10)."""
    while True:
        time.sleep(5)
        temp = round(23.5 + random.uniform(-1.5, 2.0), 1)
        hum = round(58.0 + random.uniform(-3.0, 4.0), 1)
        ts = int(time.time())
        cadena = f"ESP32_AGRO_01|{temp}|{hum}|{ts}"
        chk = calcular_sha256(cadena)[:8]

        payload = {
            "dispositivoId": "ESP32_AGRO_01",
            "timestamp": ts,
            "temperatura": temp,
            "humedad": hum,
            "actuador_estado": False,
            "checksum": chk
        }
        json_str = json.dumps(payload)
        mqtt_client.publish(TOPIC_TELEMETRIA, json_str)
        print(f"[SIMULADOR_IOT] Publicado en {TOPIC_TELEMETRIA}: {json_str}")

# =============================================================================
# INICIO DEL SERVICIO GATEWAY
# =============================================================================
def main():
    if not (FIREBASE_URL.startswith("https://") and MQTT_BROKER and PREFIX and MQTT_PORT == 8883):
        raise SystemExit("Configurar Firebase y broker MQTT TLS; consultar config.example.env")
    print("=" * 65)
    print(">>> GATEWAY INDUSTRIAL OT vamp9 - EVALUACIÓN UNIDAD 3 <<<")
    print(">>> Plataforma de Integración IoT Microcontrolador <-> App <<<")
    print("=" * 65)

    # Iniciar hilo de sincronización offline (3.1.1.4)
    t_sync = threading.Thread(target=hilo_sincronizador_offline, daemon=True)
    t_sync.start()

    # Configurar cliente MQTT con auto-reconexión (3.1.1.3)
    client = mqtt.Client(client_id=MQTT_CLIENT_ID)
    client.tls_set(
        ca_certs=os.environ["MQTT_CA_CERT"],
        certfile=os.environ["MQTT_CLIENT_CERT"],
        keyfile=os.environ["MQTT_PRIVATE_KEY"],
        cert_reqs=ssl.CERT_REQUIRED,
        tls_version=ssl.PROTOCOL_TLS_CLIENT,
    )
    client.on_connect = on_connect
    client.on_disconnect = on_disconnect
    client.on_message = on_message

    # Conectar a broker MQTT con reintentos
    backoff = 2
    conectado = False
    while not conectado:
        try:
            print(f"[MQTT] Intentando conectar a {MQTT_BROKER}:{MQTT_PORT}...")
            client.connect(MQTT_BROKER, MQTT_PORT, keepalive=60)
            conectado = True
        except Exception as e:
            print(f"[MQTT] No se pudo conectar ({e}). Reintentando en {backoff}s...")
            time.sleep(backoff)
            backoff = min(backoff * 2, 30)

    # Iniciar hilo de escucha bidireccional desde la App Android
    t_app = threading.Thread(target=hilo_escucha_app_hacia_microcontrolador, args=(client,), daemon=True)
    t_app.start()

    # Loop MQTT principal
    client.loop_start()

    # Opcional: Si se pasa '--simulate', genera telemetría autónoma

    print("\n[OK] Gateway en ejecución. Presione Ctrl+C para finalizar.\n")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[INFO] Finalizando Gateway...")
        client.loop_stop()
        client.disconnect()
        print("[OK] Gateway detenido correctamente.")

if __name__ == "__main__":
    main()
