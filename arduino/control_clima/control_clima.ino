// ===========================================================================
// Control de clima - Arduino UNO R4 WiFi
// Proyecto TI3042: DHT11 + micro servo SG90 (simula el ventilador) + app Android
// ===========================================================================
//
// CONEXIONES
//   DHT11  (módulo de 3 pines)   VCC -> 5V   |  GND -> GND  |  DATA -> D2
//          (sensor suelto de 4 pines: además una resistencia de 10 kΩ entre DATA y 5V)
//   SG90   cable rojo -> 5V      |  café/negro -> GND         |  naranjo/amarillo -> D9
//
// LIBRERÍAS (Arduino IDE > Herramientas > Administrar bibliotecas)
//   - "DHT sensor library" de Adafruit (acepta instalar "Adafruit Unified Sensor")
//   - "ArduinoGraphics" de Arduino (texto en la matriz de LEDs; opcional con USAR_MATRIZ)
//   - Servo, WiFiS3 y Arduino_LED_Matrix ya vienen con la placa UNO R4.
//
// COMUNICACIÓN CON LA APP
//   Servidor HTTP en el puerto 80. Rutas (todas responden el estado en JSON):
//
//     /estado                  consulta del estado
//     /objetivo?valor=24.0     temperatura objetivo (10 a 40 °C)
//     /auto?on=1  | on=0       modo automático activado / desactivado
//     /ventilador?on=1 | on=0  ventilador encendido / apagado (modo manual)
//
//   Respuesta:
//     {"temperatura":23.0,"humedad":55,"ventilador":false,"auto":true,
//      "objetivo":24.0,"alerta":"ninguna"}
//
//   Prueba desde el navegador: http://IP_DE_LA_PLACA/estado
// ===========================================================================

#define USAR_MATRIZ 1          // 1 = IP y temperatura en la matriz de LEDs

#include <WiFiS3.h>
#include <DHT.h>
#include <Servo.h>
#if USAR_MATRIZ
#include "ArduinoGraphics.h"   // debe incluirse antes de Arduino_LED_Matrix.h
#include "Arduino_LED_Matrix.h"
#endif

// ----------------------------- Configuración -----------------------------
// Red WiFi (2,4 GHz, WPA2)
const char WIFI_NOMBRE[] = "TU_WIFI";
const char WIFI_CLAVE[]  = "TU_CLAVE";

const int PIN_DHT   = 2;
const int PIN_SERVO = 9;

// Zona de confort en °C (igual a CONFORT_MIN / CONFORT_MAX de Clima.kt)
const float CONFORT_MIN = 17.0;
const float CONFORT_MAX = 25.0;

const float OBJETIVO_MIN = 10.0;   // límites de la temperatura objetivo
const float OBJETIVO_MAX = 40.0;

// ------------------------------- Estado ----------------------------------
DHT dht(PIN_DHT, DHT11);
Servo servo;
WiFiServer servidor(80);
#if USAR_MATRIZ
ArduinoLEDMatrix matriz;
#endif

float temperatura = NAN;
float humedad     = NAN;
float objetivo    = 24.0;
bool  modoAuto    = true;
bool  ventilador  = false;

unsigned long ultimaLectura = 0;
unsigned long ultimoPaso    = 0;
unsigned long ultimaRevisionWifi = 0;
int angulo = 90;
int paso   = 3;
int temperaturaEnMatriz = -1000;   // último valor mostrado en la matriz

// =========================================================================
void setup() {
  Serial.begin(115200);
  delay(1500);                     // espera para abrir el Monitor Serie

  dht.begin();
  servo.attach(PIN_SERVO);
  servo.write(angulo);
#if USAR_MATRIZ
  matriz.begin();
#endif

  if (WiFi.status() == WL_NO_MODULE) {
    Serial.println("ERROR: no se encuentra el módulo WiFi de la placa.");
    while (true) delay(1000);
  }
  if (WiFi.firmwareVersion() < WIFI_FIRMWARE_LATEST_VERSION) {
    Serial.println("Aviso: conviene actualizar el firmware WiFi de la placa.");
  }

  conectarWifi();
  servidor.begin();
}

void loop() {
  revisarWifi();
  leerSensor();
  controlAutomatico();
  moverServo();
  atenderCliente();
}

// ------------------------------- WiFi ------------------------------------

// Conexión a la red WiFi
void conectarWifi() {
  Serial.print("Conectando a ");
  Serial.println(WIFI_NOMBRE);
  mostrarTextoFijo("..");

  while (WiFi.begin(WIFI_NOMBRE, WIFI_CLAVE) != WL_CONNECTED) {
    Serial.print(".");
    delay(3000);
  }
  // Espera la asignación de la IP
  while (WiFi.localIP() == IPAddress(0, 0, 0, 0)) {
    delay(200);
  }

  IPAddress ip = WiFi.localIP();
  Serial.println();
  Serial.print("Listo. Escribe esta IP en la app: ");
  Serial.println(ip);
  Serial.print("Prueba en el navegador del celular: http://");
  Serial.print(ip);
  Serial.println("/estado");

  // IP desplazada dos veces en la matriz de LEDs
  String texto = "   IP " + ip.toString() + "   ";
  desplazarTexto(texto);
  desplazarTexto(texto);
  temperaturaEnMatriz = -1000;     // redibujo de la temperatura
}

// Reconexión automática al WiFi (revisión cada 10 s)
void revisarWifi() {
  if (millis() - ultimaRevisionWifi < 10000) return;
  ultimaRevisionWifi = millis();
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("Se perdió el WiFi, reconectando...");
    WiFi.disconnect();
    conectarWifi();
    servidor.begin();
  }
}

// ------------------------------- Sensor ----------------------------------

// Lectura del DHT11 cada 2 segundos
void leerSensor() {
  if (millis() - ultimaLectura < 2000) return;
  ultimaLectura = millis();

  float t = dht.readTemperature();
  float h = dht.readHumidity();
  if (isnan(t) || isnan(h)) {
    Serial.println("No se pudo leer el DHT11 (revisa el cable de DATA en D2).");
  }
  temperatura = t;                 // NAN si la lectura falló
  humedad = h;

  mostrarTemperaturaEnMatriz();
}

// ---------------------------- Ventilador ---------------------------------

// Modo automático: ventilador según el objetivo, con histéresis de 0,5 °C
void controlAutomatico() {
  if (!modoAuto || isnan(temperatura)) return;
  if (temperatura >= objetivo + 0.5) ventilador = true;
  if (temperatura <= objetivo - 0.5) ventilador = false;
}

// Movimiento del servo de lado a lado mientras el ventilador está encendido (sin delay)
void moverServo() {
  if (!ventilador) return;
  if (millis() - ultimoPaso < 15) return;
  ultimoPaso = millis();
  angulo += paso;
  if (angulo >= 180 || angulo <= 0) {
    paso = -paso;
    angulo = constrain(angulo, 0, 180);
  }
  servo.write(angulo);
}

// Alerta según la zona de confort
const char* alerta() {
  if (isnan(temperatura)) return "ninguna";
  if (temperatura > CONFORT_MAX) return "alta";
  if (temperatura < CONFORT_MIN) return "baja";
  return "ninguna";
}

// ------------------------------ Servidor ---------------------------------

// Devuelve el valor de un parámetro: valorDe("/auto?on=1", "on=") -> "1"
String valorDe(const String& ruta, const char* clave) {
  int inicio = ruta.indexOf(clave);
  if (inicio < 0) return "";
  inicio += strlen(clave);
  int fin = ruta.indexOf('&', inicio);
  return fin < 0 ? ruta.substring(inicio) : ruta.substring(inicio, fin);
}

// Respuesta HTTP con el estado en JSON
void responderEstado(WiFiClient& cliente) {
  String json = "{\"temperatura\":";
  json += isnan(temperatura) ? String("null") : String(temperatura, 1);
  json += ",\"humedad\":";
  json += isnan(humedad) ? String("null") : String(humedad, 0);
  json += ",\"ventilador\":";
  json += ventilador ? "true" : "false";
  json += ",\"auto\":";
  json += modoAuto ? "true" : "false";
  json += ",\"objetivo\":";
  json += String(objetivo, 1);
  json += ",\"alerta\":\"";
  json += alerta();
  json += "\"}";

  cliente.print("HTTP/1.1 200 OK\r\n");
  cliente.print("Content-Type: application/json\r\n");
  cliente.print("Content-Length: ");
  cliente.print(json.length());
  cliente.print("\r\nConnection: close\r\n\r\n");
  cliente.print(json);
}

// Atención de peticiones HTTP de la app
void atenderCliente() {
  WiFiClient cliente = servidor.available();
  if (!cliente) return;

  cliente.setTimeout(1000);
  String linea = cliente.readStringUntil('\n');   // "GET /ruta?x=1 HTTP/1.1"
  while (cliente.available()) cliente.read();     // descarta las cabeceras

  int a = linea.indexOf(' ');
  int b = linea.indexOf(' ', a + 1);
  if (a < 0 || b < 0) {
    cliente.stop();
    return;
  }
  String ruta = linea.substring(a + 1, b);

  bool conocida = true;
  if (ruta.startsWith("/objetivo")) {
    String valor = valorDe(ruta, "valor=");
    if (valor.length() > 0) {
      objetivo = constrain(valor.toFloat(), OBJETIVO_MIN, OBJETIVO_MAX);
    }
  } else if (ruta.startsWith("/auto")) {
    modoAuto = valorDe(ruta, "on=") == "1";
    controlAutomatico();
  } else if (ruta.startsWith("/ventilador")) {
    ventilador = valorDe(ruta, "on=") == "1";
    modoAuto = false;                              // orden manual
  } else if (!ruta.startsWith("/estado")) {
    conocida = false;
  }

  if (conocida) {
    Serial.print("App -> ");
    Serial.println(ruta);
    responderEstado(cliente);
  } else {
    cliente.print("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
  }
  delay(1);
  cliente.stop();
}

// --------------------------- Matriz de LEDs ------------------------------

// Texto desplazable en la matriz de LEDs
void desplazarTexto(const String& texto) {
#if USAR_MATRIZ
  matriz.beginDraw();
  matriz.stroke(0xFFFFFFFF);
  matriz.textScrollSpeed(60);
  matriz.textFont(Font_5x7);
  matriz.beginText(0, 1, 0xFFFFFF);
  matriz.println(texto);
  matriz.endText(SCROLL_LEFT);     // bloquea hasta terminar el desplazamiento
  matriz.endDraw();
#endif
}

// Texto fijo en la matriz de LEDs
void mostrarTextoFijo(const char* texto) {
#if USAR_MATRIZ
  matriz.beginDraw();
  matriz.clear();
  matriz.stroke(0xFFFFFFFF);
  matriz.textFont(Font_5x7);
  matriz.beginText(0, 1, 0xFFFFFF);
  matriz.print(texto);
  matriz.endText();
  matriz.endDraw();
#endif
}

// Temperatura (2 dígitos) en la matriz de LEDs
void mostrarTemperaturaEnMatriz() {
  int valor = isnan(temperatura) ? -1 : (int)round(temperatura);
  if (valor == temperaturaEnMatriz) return;
  temperaturaEnMatriz = valor;

  if (valor < 0) {
    mostrarTextoFijo("--");
  } else {
    char texto[4];
    snprintf(texto, sizeof(texto), "%d", constrain(valor, 0, 99));
    mostrarTextoFijo(texto);
  }
}
