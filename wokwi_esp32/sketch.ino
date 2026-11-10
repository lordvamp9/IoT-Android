/**
 * ============================================================================
 * PROYECTO: IoTClimaRiego -Smart Agro OT
 *
 * Microcontrolador Virtual: ESP32 (Wokwi / Raspbian)
 */

#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <DHTesp.h>
#include <ESP32Servo.h>
#include <time.h>
#include "mbedtls/md.h"
#include "config.h"

// ---------------- CONFIGURACIÓN DE PINES ----------------
#define PIN_DHT           15    // Sensor Temperatura y Humedad DHT22
#define PIN_SERVO_VENT    18    // Servo SG90 Compuerta de Ventilación
#define PIN_RELE_BOMBA    19    // Relé / LED Indicador Bomba de Riego
#define PIN_LED_ESTADO     2    // LED Onboard indicador de enlace / red

// ---------------- CREDENCIALES RED Y BROKER MQTT ----------------
const String TOPIC_TELEMETRIA = String(MQTT_TOPIC_PREFIX) + "/telemetria";
const String TOPIC_CONTROL = String(MQTT_TOPIC_PREFIX) + "/control";
const String TOPIC_SERIAL_TX = String(MQTT_TOPIC_PREFIX) + "/serial_tx";
const String TOPIC_ACK = String(MQTT_TOPIC_PREFIX) + "/ack";

// ---------------- OBJETOS GLOBALES ----------------
WiFiClientSecure espClient;
PubSubClient mqttClient(espClient);
DHTesp dhtSensor;
Servo servoVentilacion;

// ---------------- VARIABLES DE ESTADO Y TELEMETRÍA ----------------
const char* DISPOSITIVO_ID = "ESP32_AGRO_01";
float temperatura = 24.5;
float humedad = 60.2;
bool estadoBomba = false;
bool estadoVentilacion = false;
bool modoManual = false;

// ---------------- CONTROL DE TIEMPO EFICIENTE (3.1.3.10) ----------------
unsigned long ultimoMuestreo = 0;
const unsigned long INTERVALO_MUESTREO = 5000; // 5 segundos entre lecturas

// ---------------- RECONEXIÓN AUTOMÁTICA CON BACKOFF (3.1.1.3) ----------------
unsigned long tiempoUltimoIntentoWifi = 0;
unsigned long delayReconexionWifi = 2000;  // Empieza en 2 seg
const unsigned long MAX_DELAY_WIFI = 30000; // Máximo 30 seg

unsigned long tiempoUltimoIntentoMqtt = 0;
unsigned long delayReconexionMqtt = 2000;
bool relojIniciado = false;
const unsigned long MAX_DELAY_MQTT = 30000;

// ---------------- BUFFER TEMPORAL OFFLINE EN IOT (3.1.1.4) ----------------
const int CAPACIDAD_BUFFER = 20;
struct TelemetriaCache {
  unsigned long timestamp;
  float temp;
  float hum;
  bool bomba;
  bool ventilacion;
  char checksum[9];
};

TelemetriaCache bufferLocal[CAPACIDAD_BUFFER];
int elementosEnBuffer = 0;
String ultimosComandoIds[16];
int siguienteComandoId = 0;

// ---------------- DECLARACIÓN DE FUNCIONES ----------------
String calcularSHA256(const String& input);
void conectarWiFi();
void verificarReconexionAutomatica();
void conectarMQTT();
void callbackMQTT(char* topic, byte* payload, unsigned int length);
void leerSensores();
void publicarTelemetria(float temp, float hum, bool bomba, bool vent, unsigned long ts);
void guardarEnBufferLocal(float temp, float hum, bool bomba, bool vent, unsigned long ts, const String& chk);
void vaciarBufferLocalHaciaNube();
void procesarComandoSerial(const String& comando);
void aplicarEstadoVentilacion(bool activa);
void aplicarEstadoBomba(bool activa);

// ============================================================================
// SETUP
// ============================================================================
void setup() {
  Serial.begin(115200);
  delay(1000);

  Serial.println("\n========================================================");
  Serial.println(">>> vamp9 OT Smart Agro - Nodo ESP32 Inicializando <<<");
  Serial.println(">>> Evaluación Sumativa Unidad 3 (TI3042 - INACAP) <<<");
  Serial.println("========================================================\n");

  // Configuración de Pines
  pinMode(PIN_RELE_BOMBA, OUTPUT);
  pinMode(PIN_LED_ESTADO, OUTPUT);
  digitalWrite(PIN_RELE_BOMBA, LOW);
  digitalWrite(PIN_LED_ESTADO, LOW);

  // Inicialización de Sensor DHT22
  dhtSensor.setup(PIN_DHT, DHTesp::DHT22);
  Serial.println("[HARDWARE] Sensor DHT22 inicializado en GPIO 15.");

  // Inicialización de Servo Motor SG90
  ESP32PWM::allocateTimer(0);
  servoVentilacion.setPeriodHertz(50);
  servoVentilacion.attach(PIN_SERVO_VENT, 500, 2400);
  servoVentilacion.write(0); // Cerrado inicial (0°)
  Serial.println("[HARDWARE] Servo SG90 Compuerta inicializado en GPIO 18 (0° - Cerrada).");
  Serial.println("[HARDWARE] Relé Bomba inicializado en GPIO 19 (OFF).");

  // Configuración de Red y Broker MQTT
  espClient.setCACert(MQTT_CA_CERT);
  espClient.setCertificate(MQTT_DEVICE_CERT);
  espClient.setPrivateKey(MQTT_DEVICE_PRIVATE_KEY);
  conectarWiFi();
  mqttClient.setServer(MQTT_BROKER, MQTT_PORT);
  mqttClient.setBufferSize(512);
  mqttClient.setCallback(callbackMQTT);

  Serial.println("\n[SISTEMA] Nodo IoT listo para transmisión Serial y MQTT.");
  Serial.println("[SERIAL_RX] Escuchando comandos en puerto Serial. Formatos válidos:");
  Serial.println("            CMD:VENT=1 | CMD:VENT=0 | CMD:BOMBA=1 | CMD:BOMBA=0 | CMD:PING\n");
}

// ============================================================================
// LOOP PRINCIPAL (No bloqueante, eficiente en energía - 3.1.3.10)
// ============================================================================
void loop() {
  // 1. Gestión de Reconexión Automática con Backoff Exponencial (3.1.1.3)
  verificarReconexionAutomatica();

  // 2. Loop de cliente MQTT si hay conexión activa
  if (mqttClient.connected()) {
    mqttClient.loop();
  }

  // 3. Recepción y Decodificación de Comandos Seriales (3.1.3.9)
  if (Serial.available() > 0) {
    String comandoSerial = Serial.readStringUntil('\n');
    comandoSerial.trim();
    if (comandoSerial.length() > 0) {
      procesarComandoSerial(comandoSerial);
    }
  }

  // 4. Muestreo Periódico Eficiente de Sensores (Cada 5s) (3.1.3.10)
  unsigned long tiempoActual = millis();
  if (tiempoActual - ultimoMuestreo >= INTERVALO_MUESTREO) {
    ultimoMuestreo = tiempoActual;
    leerSensores();
  }
}

// ============================================================================
// CONEXIÓN WI-FI CON RETROCESO EXPONENCIAL (3.1.1.3)
// ============================================================================
void conectarWiFi() {
  Serial.print("[WIFI] Iniciando conexión a SSID: ");
  Serial.println(WIFI_SSID);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
}

void verificarReconexionAutomatica() {
  unsigned long ahora = millis();

  // A. Verificación Wi-Fi
  if (WiFi.status() != WL_CONNECTED) {
    digitalWrite(PIN_LED_ESTADO, LOW);
    if (ahora - tiempoUltimoIntentoWifi >= delayReconexionWifi) {
      tiempoUltimoIntentoWifi = ahora;
      Serial.printf("[WIFI_RECONNECT] Reintentando conexión Wi-Fi (Backoff: %lu ms)...\n", delayReconexionWifi);
      WiFi.reconnect();

      // Retroceso exponencial: duplica el delay hasta el tope
      delayReconexionWifi = min(delayReconexionWifi * 2, MAX_DELAY_WIFI);
    }
    return;
  } else {
    // Si ya está conectado, resetea el backoff de Wi-Fi
    delayReconexionWifi = 2000;
  }

  if (!relojIniciado) {
    configTime(0, 0, "pool.ntp.org", "time.google.com");
    relojIniciado = true;
    Serial.println("[HORA] Sincronizando reloj para TLS y telemetría.");
  }
  if (time(nullptr) < 1700000000) return;

  // B. Verificación MQTT
  if (!mqttClient.connected()) {
    digitalWrite(PIN_LED_ESTADO, LOW);
    if (ahora - tiempoUltimoIntentoMqtt >= delayReconexionMqtt) {
      tiempoUltimoIntentoMqtt = ahora;
      Serial.printf("[MQTT_RECONNECT] Reintentando conexión a Broker %s (Backoff: %lu ms)...\n", MQTT_BROKER, delayReconexionMqtt);
      conectarMQTT();

      // Retroceso exponencial para MQTT
      delayReconexionMqtt = min(delayReconexionMqtt * 2, MAX_DELAY_MQTT);
    }
  } else {
    digitalWrite(PIN_LED_ESTADO, HIGH);
    delayReconexionMqtt = 2000;
  }
}

void conectarMQTT() {
  if (mqttClient.connect(MQTT_CLIENT)) {
    Serial.println("[MQTT] Conectado exitosamente al Broker Cloud Seguro.");
    mqttClient.subscribe(TOPIC_CONTROL.c_str());
    Serial.printf("[MQTT] Suscrito al tópico de control: %s\n", TOPIC_CONTROL.c_str());

    // Si había paquetes retenidos durante una caída de red, vaciar buffer (3.1.1.4)
    if (elementosEnBuffer > 0) {
      vaciarBufferLocalHaciaNube();
    }
  } else {
    Serial.printf("[MQTT_ERROR] Fallo al conectar. rc=%d\n", mqttClient.state());
  }
}

// ============================================================================
// RECEPCIÓN DE MENSAJES MQTT DESDE LA CLOUD / APP
// ============================================================================
void callbackMQTT(char* topic, byte* payload, unsigned int length) {
  String mensaje = "";
  for (unsigned int i = 0; i < length; i++) {
    mensaje += (char)payload[i];
  }

  Serial.printf("[MQTT_RX] Tópico: %s | Mensaje: %s\n", topic, mensaje.c_str());

  // Parseo de Payload JSON o comando estructurado
  StaticJsonDocument<256> doc;
  DeserializationError error = deserializeJson(doc, mensaje);

  if (!error && doc.containsKey("id")) {
    String id = doc["id"].as<String>();
    String name = doc["name"].as<String>();
    String device = doc["deviceId"].as<String>();
    unsigned long createdAt = doc["createdAt"] | 0;
    int value = doc["value"] | -1;
    time_t now = time(nullptr);
    bool valid = id.length() > 0 && id.length() <= 64 && device == DISPOSITIVO_ID &&
      (name == "VENT" || name == "BOMBA" || name == "PING" || name == "MODO") && (value == 0 || value == 1) &&
      // Wokwi puede avanzar más lento que el reloj real; el gateway valida la
      // antigüedad contra la hora real antes de publicar el comando.
      now >= 1700000000 && createdAt <= (unsigned long)now + 900 &&
      (unsigned long)now <= createdAt + 30;
    if (!valid) { Serial.println("[MQTT] Comando inválido"); return; }
    bool repetido = false;
    for (const String& previo : ultimosComandoIds) if (previo == id) repetido = true;
    if (!repetido) {
      procesarComandoSerial("CMD:" + name + "=" + String(value));
      ultimosComandoIds[siguienteComandoId] = id;
      siguienteComandoId = (siguienteComandoId + 1) % 16;
    }
    StaticJsonDocument<160> ack;
    ack["id"] = id;
    ack["dispositivoId"] = DISPOSITIVO_ID;
    ack["ok"] = true;
    char ackBuffer[160];
    serializeJson(ack, ackBuffer);
    mqttClient.publish(TOPIC_ACK.c_str(), ackBuffer);
    return;
  }
  Serial.println("[MQTT] Trama rechazada: formato de comando no autorizado");
}

// ============================================================================
// LECTURA DE SENSORES Y PUBLICACIÓN
// ============================================================================
void leerSensores() {
  TempAndHumidity datos = dhtSensor.getTempAndHumidity();

  if (isnan(datos.temperature) || isnan(datos.humidity)) {
    Serial.println("[SENSOR_ERROR] DHT22 sin lectura válida; no se publica telemetría ficticia");
    return;
  } else {
    temperatura = datos.temperature;
    humedad = datos.humidity;
  }

  // Humedad ambiental usada como indicador simulado de riego.
  if (!modoManual) {
    bool vent = estadoVentilacion ? temperatura > 28.0 : temperatura >= 30.0;
    bool bomba = estadoBomba ? humedad < 45.0 : humedad <= 40.0;
    if (vent != estadoVentilacion) aplicarEstadoVentilacion(vent);
    if (bomba != estadoBomba) aplicarEstadoBomba(bomba);
  }

  time_t hora = time(nullptr);
  if (hora < 1700000000) {
    Serial.println("[HORA] Esperando sincronización; lectura no publicada.");
    return;
  }
  unsigned long timestampActual = (unsigned long)hora;
  publicarTelemetria(temperatura, humedad, estadoBomba, estadoVentilacion, timestampActual);
}

// ============================================================================
// CÁLCULO DE INTEGRIDAD CRIPTOGRÁFICA (SHA-256) (3.1.1.2)
// ============================================================================
String calcularSHA256(const String& input) {
  byte shaResult[32];
  mbedtls_md_context_t ctx;
  mbedtls_md_type_t md_type = MBEDTLS_MD_SHA256;

  mbedtls_md_init(&ctx);
  mbedtls_md_setup(&ctx, mbedtls_md_info_from_type(md_type), 0);
  mbedtls_md_starts(&ctx);
  mbedtls_md_update(&ctx, (const unsigned char*)input.c_str(), input.length());
  mbedtls_md_finish(&ctx, shaResult);
  mbedtls_md_free(&ctx);

  String hashHex = "";
  for (int i = 0; i < 32; i++) {
    char buf[3];
    sprintf(buf, "%02x", shaResult[i]);
    hashHex += buf;
  }
  return hashHex;
}

// ============================================================================
// PUBLICACIÓN DE TELEMETRÍA Y ENVÍO SERIAL (3.1.3.8 - 12.8 pts)
// ============================================================================
void publicarTelemetria(float temp, float hum, bool bomba, bool vent, unsigned long ts) {
  // 1. Cálculo de integridad criptográfica (SHA-256) (3.1.1.2)
  String baseString = String(DISPOSITIVO_ID) + "|" + String(temp, 1) + "|" + String(hum, 1) + "|" + String(ts);
  String fullHash = calcularSHA256(baseString);
  String checksum = fullHash.substring(0, 8); // Checksum de 8 caracteres hexadecimales

  // 2. Construcción de Payload JSON estructurado
  StaticJsonDocument<256> doc;
  doc["dispositivoId"] = DISPOSITIVO_ID;
  doc["timestamp"] = ts;
  doc["temperatura"] = serialized(String(temp, 1));
  doc["humedad"] = serialized(String(hum, 1));
  doc["actuador_estado"] = bomba || vent;
  doc["actuador_bomba"] = bomba;
  doc["actuador_ventilacion"] = vent;
  doc["checksum"] = checksum;

  char jsonBuffer[256];
  serializeJson(doc, jsonBuffer);

  // 3. ENVÍO SERIAL EXPLÍCITO DESDE MICROCONTROLADOR (Criterio 3.1.3.8 - 12.8 pts)
  Serial.printf("[SERIAL_TX] %s\n", jsonBuffer);

  // 4. ENVÍO MQTT SEGURO O ALMACENAMIENTO TEMPORAL OFFLINE (Criterio 3.1.1.4)
  if (mqttClient.connected()) {
    if (!mqttClient.publish(TOPIC_TELEMETRIA.c_str(), jsonBuffer)) {
      guardarEnBufferLocal(temp, hum, bomba, vent, ts, checksum);
    }
    mqttClient.publish(TOPIC_SERIAL_TX.c_str(), jsonBuffer);
  } else {
    // Si no hay conexión de red, guardar en buffer local temporal (3.1.1.4)
    guardarEnBufferLocal(temp, hum, bomba, vent, ts, checksum);
  }
}

// ============================================================================
// ALMACENAMIENTO TEMPORAL LOCAL EN IOT Y REENVÍO (3.1.1.4)
// ============================================================================
void guardarEnBufferLocal(float temp, float hum, bool bomba, bool vent, unsigned long ts, const String& chk) {
  if (elementosEnBuffer < CAPACIDAD_BUFFER) {
    bufferLocal[elementosEnBuffer].timestamp = ts;
    bufferLocal[elementosEnBuffer].temp = temp;
    bufferLocal[elementosEnBuffer].hum = hum;
    bufferLocal[elementosEnBuffer].bomba = bomba;
    bufferLocal[elementosEnBuffer].ventilacion = vent;
    strncpy(bufferLocal[elementosEnBuffer].checksum, chk.c_str(), 8);
    bufferLocal[elementosEnBuffer].checksum[8] = '\0';
    elementosEnBuffer++;

    Serial.printf("[CACHE_OFFLINE_SAVED] Sin conexión MQTT. Lectura retenida en buffer local (#%d/%d) [Temp: %.1f°C | Hum: %.1f%%]\n",
                  elementosEnBuffer, CAPACIDAD_BUFFER, temp, hum);
  } else {
    Serial.println("[CACHE_OFFLINE_WARNING] Buffer lleno. Se descarta la lectura nueva.");
  }
}

void vaciarBufferLocalHaciaNube() {
  Serial.printf("[CACHE_OFFLINE_FLUSH] Conexión restablecida. Reenviando %d paquetes almacenados localmente...\n", elementosEnBuffer);

  int entregados = 0;
  for (int i = 0; i < elementosEnBuffer; i++) {
    StaticJsonDocument<256> doc;
    doc["dispositivoId"] = DISPOSITIVO_ID;
    doc["timestamp"] = bufferLocal[i].timestamp;
    // El checksum se calculo con una cifra decimal; conservarla al reenviar.
    doc["temperatura"] = serialized(String(bufferLocal[i].temp, 1));
    doc["humedad"] = serialized(String(bufferLocal[i].hum, 1));
    doc["actuador_estado"] = bufferLocal[i].bomba || bufferLocal[i].ventilacion;
    doc["actuador_bomba"] = bufferLocal[i].bomba;
    doc["actuador_ventilacion"] = bufferLocal[i].ventilacion;
    doc["checksum"] = bufferLocal[i].checksum;
    doc["origen"] = "offline_cache_resync";

    char jsonBuffer[256];
    serializeJson(doc, jsonBuffer);

    if (!mqttClient.publish(TOPIC_TELEMETRIA.c_str(), jsonBuffer)) break;
    entregados++;
    Serial.printf("[SERIAL_TX_RECOVERY] Reenviado #%d: %s\n", i + 1, jsonBuffer);
    delay(100); // Pequeña pausa para evitar colisiones
  }

  for (int i = entregados; i < elementosEnBuffer; i++) bufferLocal[i - entregados] = bufferLocal[i];
  elementosEnBuffer -= entregados;
  Serial.printf("[CACHE_OFFLINE] Entregados al cliente MQTT: %d; pendientes: %d\n", entregados, elementosEnBuffer);
}

// ============================================================================
// RECEPCIÓN SERIAL EN MICROCONTROLADOR (3.1.3.9 - 4.2 pts)
// ============================================================================
void procesarComandoSerial(const String& cmd) {
  String c = cmd;
  c.toUpperCase();

  Serial.printf("[SERIAL_RX] Comando recibido: %s\n", c.c_str());

  bool comandoValido = false;
  String respuesta = "";

  if (c == "CMD:MODO=1" || c == "CMD:MODO=0") {
    modoManual = c == "CMD:MODO=1";
    respuesta = modoManual ? "ACK:MODO_MANUAL" : "ACK:MODO_AUTOMATICO";
    comandoValido = true;
  } else if (c == "CMD:VENT=1" || c == "CMD:VENTILACION=1") {
    aplicarEstadoVentilacion(true);
    respuesta = "ACK:OK_VENTILACION;STATUS=EXECUTED;VAL=1";
    comandoValido = true;
  } else if (c == "CMD:VENT=0" || c == "CMD:VENTILACION=0") {
    aplicarEstadoVentilacion(false);
    respuesta = "ACK:OK_VENTILACION;STATUS=EXECUTED;VAL=0";
    comandoValido = true;
  } else if (c == "CMD:BOMBA=1") {
    aplicarEstadoBomba(true);
    respuesta = "ACK:OK_BOMBA;STATUS=EXECUTED;VAL=1";
    comandoValido = true;
  } else if (c == "CMD:BOMBA=0") {
    aplicarEstadoBomba(false);
    respuesta = "ACK:OK_BOMBA;STATUS=EXECUTED;VAL=0";
    comandoValido = true;
  } else if (c == "CMD:PING=1") {
    respuesta = "ACK:PONG;DISPOSITIVO=ESP32_AGRO_01;STATUS=ONLINE";
    comandoValido = true;
  } else {
    respuesta = "NACK:COMANDO_DESCONOCIDO;ERROR=400";
  }

  // Emisión Serial del Acuse de Recibo (3.1.3.8)
  Serial.printf("[SERIAL_ACK] %s\n", respuesta.c_str());

  // Replicar acuse por MQTT si hay conexión
  if (mqttClient.connected()) {
    StaticJsonDocument<128> ackDoc;
    ackDoc["ack"] = respuesta;
    ackDoc["valido"] = comandoValido;
    ackDoc["dispositivoId"] = DISPOSITIVO_ID;
    char bufferAck[128];
    serializeJson(ackDoc, bufferAck);
    mqttClient.publish(TOPIC_ACK.c_str(), bufferAck);
  }
}

// ============================================================================
// CONTROL FÍSICO DE ACTUADORES
// ============================================================================
void aplicarEstadoVentilacion(bool activa) {
  estadoVentilacion = activa;
  if (activa) {
    servoVentilacion.write(90); // Abrir compuerta a 90°
    Serial.println("[ACTUADOR] Servo SG90 -> COMPUERTA ABIERTA (90°)");
  } else {
    servoVentilacion.write(0);  // Cerrar compuerta a 0°
    Serial.println("[ACTUADOR] Servo SG90 -> COMPUERTA CERRADA (0°)");
  }
}

void aplicarEstadoBomba(bool activa) {
  estadoBomba = activa;
  digitalWrite(PIN_RELE_BOMBA, activa ? HIGH : LOW);
  Serial.printf("[ACTUADOR] Relé Bomba de Riego -> %s\n", activa ? "ENCENDIDA (Flujo OK)" : "APAGADA (Inactiva)");
}
