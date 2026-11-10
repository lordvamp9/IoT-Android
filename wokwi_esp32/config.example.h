#pragma once
// Copiar a config.h (ignorado por Git). No subir credenciales reales.
#define WIFI_SSID "Wokwi-GUEST"
#define WIFI_PASS ""
#define MQTT_BROKER "TU_ENDPOINT_AWS_IOT_CORE-ats.iot.TU_REGION.amazonaws.com"
#define MQTT_PORT 8883
#define MQTT_CLIENT "ESP32_AGRO_01"
#define MQTT_TOPIC_PREFIX "curso/tu_equipo"
static const char MQTT_CA_CERT[] = R"EOF(-----BEGIN CERTIFICATE-----
CERTIFICADO_CA_DEL_BROKER
-----END CERTIFICATE-----)EOF";
static const char MQTT_DEVICE_CERT[] = R"EOF(-----BEGIN CERTIFICATE-----
CERTIFICADO_CLIENTE_X509_DEL_ESP32
-----END CERTIFICATE-----)EOF";
static const char MQTT_DEVICE_PRIVATE_KEY[] = R"EOF(-----BEGIN PRIVATE KEY-----
CLAVE_PRIVADA_DEL_ESP32
-----END PRIVATE KEY-----)EOF";

