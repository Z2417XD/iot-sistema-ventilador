// ===========================================================================
// Control de clima - Arduino UNO R4 WiFi
// Proyecto TI3042: DHT11 + micro servo SG90 (simula el ventilador) + app Android
// ===========================================================================
//
// CONEXIONES
//   DHT11  (módulo de 3 pines)   VCC -> 5V   |  GND -> GND  |  DATA -> D2
//   SG90   cable rojo -> 5V      |  café/negro -> GND         |  naranjo/amarillo -> D9
//
// LIBRERÍAS (Arduino IDE > Herramientas > Administrar bibliotecas)
//   - "DHT sensor library" de Adafruit (acepta instalar "Adafruit Unified Sensor")
//   - "ArduinoGraphics" de Arduino (texto en la matriz de LEDs; opcional con USAR_MATRIZ)
//   - Servo, WiFiS3 y Arduino_LED_Matrix ya vienen con la placa UNO R4.
//
// COMUNICACIÓN CON LA APP
//   La placa crea su propia red WiFi ("ControlClima", clave "clima1234") con la
//   IP fija 192.168.4.1. El celular se conecta a esa red y la app envía las órdenes.
//   Servidor HTTP en el puerto 80. Rutas (todas responden el estado en JSON):
//
//     /estado                  consulta del estado
//     /ventilador?on=1 | on=0  ventilador encendido / apagado (control manual)
//
//   Respuesta:
//     {"temperatura":23.0,"humedad":55,"ventilador":false,"alerta":"ninguna"}
//
//   Prueba desde el navegador: http://192.168.4.1/estado
// ===========================================================================

#define USAR_MATRIZ 1          // 1 = red, IP y temperatura en la matriz de LEDs

#include <WiFiS3.h>
#include <DHT.h>
#include <Servo.h>
#if USAR_MATRIZ
#include "ArduinoGraphics.h"   // debe incluirse antes de Arduino_LED_Matrix.h
#include "Arduino_LED_Matrix.h"
#endif

// ----------------------------- Configuración -----------------------------
// Red WiFi propia de la placa (punto de acceso). La clave necesita al menos 8 caracteres.
const char RED_NOMBRE[] = "ControlClima";
const char RED_CLAVE[]  = "clima1234";
const IPAddress IP_PLACA(192, 168, 4, 1);   // IP fija que se escribe en la app

const int PIN_DHT   = 2;
const int PIN_SERVO = 9;

// Zona de confort en °C (igual a CONFORT_MIN / CONFORT_MAX de Clima.kt)
const float CONFORT_MIN = 17.0;
const float CONFORT_MAX = 25.0;

// ------------------------------- Estado ----------------------------------
DHT dht(PIN_DHT, DHT11);
Servo servo;
WiFiServer servidor(80);
#if USAR_MATRIZ
ArduinoLEDMatrix matriz;
#endif

float temperatura = NAN;
float humedad     = NAN;
bool  ventilador  = false;

unsigned long ultimaLectura = 0;
unsigned long ultimaLecturaValida = 0;
unsigned long ultimoPaso    = 0;
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

  crearRed();
  servidor.begin();
}

void loop() {
  leerSensor();
  moverServo();
  atenderCliente();
}

// ------------------------------- WiFi ------------------------------------

// Creación de la red WiFi de la placa; el celular se conecta directo a ella
void crearRed() {
  Serial.print("Creando la red ");
  Serial.println(RED_NOMBRE);
  mostrarTextoFijo("..");

  WiFi.config(IP_PLACA);
  while (WiFi.beginAP(RED_NOMBRE, RED_CLAVE) != WL_AP_LISTENING) {
    Serial.println("No se pudo crear la red, reintentando...");
    delay(3000);
  }

  Serial.print("Listo. Conecta el celular a la red ");
  Serial.print(RED_NOMBRE);
  Serial.print(" (clave ");
  Serial.print(RED_CLAVE);
  Serial.println(")");
  Serial.print("IP para la app: ");
  Serial.println(IP_PLACA);
  Serial.print("Prueba en el navegador del celular: http://");
  Serial.print(IP_PLACA);
  Serial.println("/estado");

  // Nombre de la red e IP desplazados en la matriz de LEDs
  String texto = String("   ") + RED_NOMBRE + "  IP " + IP_PLACA.toString() + "   ";
  desplazarTexto(texto);
  temperaturaEnMatriz = -1000;     // redibujo de la temperatura
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
    // Conserva la última lectura válida; sin lecturas válidas por 10 s se informa "sin lectura"
    if (millis() - ultimaLecturaValida > 10000) {
      temperatura = NAN;
      humedad = NAN;
    }
  } else {
    temperatura = t;
    humedad = h;
    ultimaLecturaValida = millis();
    Serial.print("Temperatura: ");
    Serial.print(t, 1);
    Serial.print(" C  Humedad: ");
    Serial.print(h, 0);
    Serial.println(" %");
  }

  mostrarTemperaturaEnMatriz();
}

// ---------------------------- Ventilador ---------------------------------

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

// Devuelve el valor de un parámetro: valorDe("/ventilador?on=1", "on=") -> "1"
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
  if (ruta.startsWith("/ventilador")) {
    ventilador = valorDe(ruta, "on=") == "1";
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
