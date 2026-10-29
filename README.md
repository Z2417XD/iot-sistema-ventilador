[README.md](https://github.com/user-attachments/files/34153165/README.md)
# Sistema de control de clima con Arduino y Android

Proyecto de Aplicaciones Móviles para IoT (TI3042), evaluación sumativa de la Unidad 2.

Es una app Android que se conecta por WiFi a una placa Arduino UNO R4 WiFi. La placa mide la temperatura y la humedad de una pieza con un sensor DHT11, y desde el celular se puede ver esa información, cambiar la temperatura que uno quiere y prender o apagar un ventilador. Como no tengo un ventilador de verdad conectado, usé un micro servo SG90 que se mueve de un lado a otro cuando el ventilador está encendido.

Además, si la temperatura se sale de lo cómodo (menos de 17 °C o más de 25 °C), la app manda una notificación al celular.

## Qué se necesita

- Arduino UNO R4 WiFi, sensor DHT11 y micro servo SG90.
- Un celular Android (mínimo Android 7.0).
- Un computador con la aplicación **Arduino IDE**, que es la que se usa para cargar el código a la placa y para abrir el monitor serie. Sin ella no se puede programar el Arduino, así que es parte del proyecto igual que la app.
- Cable USB para conectar la placa al computador la primera vez.

Las conexiones son: DHT11 al pin D2 (más 5V y GND), y el servo al pin D9 (más 5V y GND).

## Dejar lista la placa

El código de la placa está en `arduino/control_clima/control_clima.ino`. Los pasos son:

1. Abrir el archivo con Arduino IDE e instalar las librerías `DHT sensor library` (de Adafruit) y `ArduinoGraphics`. El resto (`Servo`, `WiFiS3` y `Arduino_LED_Matrix`) ya viene con la placa.
2. Escribir el nombre y la contraseña del WiFi en estas dos líneas del código:

   ```cpp
   const char WIFI_NOMBRE[] = "TU_WIFI";
   const char WIFI_CLAVE[]  = "TU_CLAVE";
   ```

3. Elegir la placa Arduino UNO R4 WiFi y cargar el código.

Esto se hace una sola vez. Después de cargarlo, la placa ya sabe a qué red conectarse, así que basta con enchufarla y esperar a que se conecte sola, sin volver a tocar el computador. En el repositorio dejé `TU_WIFI` y `TU_CLAVE` a propósito, para no publicar la clave de mi red.

Un detalle útil: en la universidad los puertos de la red están bloqueados, por eso la placa se conecta al hotspot del celular. Lo más cómodo es ponerle al hotspot el mismo nombre y la misma clave que se escribieron en el código. Así la placa se conecta en cualquier lugar donde se active ese hotspot.

## Cómo se comunican la app y la placa

Usé WiFi con peticiones HTTP. Lo elegí porque la UNO R4 WiFi ya trae el módulo WiFi incluido, tiene bastante más alcance que Bluetooth, y es muy fácil de probar: se puede abrir la dirección de la placa en el navegador del celular y ver la respuesta. El consumo de energía no pesa mucho porque la placa va conectada a corriente.

La placa funciona como un pequeño servidor web en el puerto 80. La app le pide cosas y ella responde con un JSON. Estas son las rutas:

| Ruta | Qué hace |
|---|---|
| `/estado` | Devuelve temperatura, humedad, estado del ventilador, modo automático, objetivo y alerta. |
| `/objetivo?valor=24.0` | Cambia la temperatura objetivo (entre 10 y 40 °C). |
| `/auto?on=1` o `on=0` | Activa o desactiva el modo automático. |
| `/ventilador?on=1` o `on=0` | Prende o apaga el ventilador a mano. Esto desactiva el modo automático. |

Un ejemplo de respuesta:

```json
{"temperatura":23.0,"humedad":55,"ventilador":false,"auto":true,"objetivo":24.0,"alerta":"ninguna"}
```

La app pregunta por `/estado` cada 2 segundos. Si la placa no contesta en 3 segundos, muestra que se perdió la conexión y sigue intentando. Por el lado de la placa, cada 10 segundos revisa si sigue conectada al WiFi y, si se cayó, se reconecta sola.

### Cómo saber la IP de la placa

La app necesita la IP de la placa para conectarse. Cuando la placa arranca y se conecta al WiFi, muestra la IP deslizándose en la matriz de LEDs, así que se puede leer sin computador. También la imprime en el monitor serie de Arduino IDE si está conectada por USB.

Como el hotspot reparte las IP automáticamente, **la IP puede cambiar** cada vez que la placa se reinicia. Conviene revisarla de nuevo antes de cada prueba.

## La app

Al abrir la app aparece primero el login. Después de entrar hay dos pantallas.

La pantalla principal tiene el campo para escribir la IP, el termómetro, la humedad, los botones y el deslizador para cambiar la temperatura objetivo, y los interruptores del ventilador y del modo automático. El fondo cambia de color según qué tan fría o caliente esté la pieza.

La pantalla de historial muestra los parámetros en tiempo real, un gráfico con los últimos 5 minutos de temperatura y unas métricas calculadas con esos datos (mínima, máxima, promedio y el porcentaje de tiempo dentro de la zona de confort). Desde ahí se pueden guardar las métricas en la nube.

También tiene un modo demo, que simula la placa dentro del mismo celular. Lo agregué para poder probar la app sin tener el Arduino a mano.

## Inicio de sesión

Por ahora hay un solo usuario, que está escrito dentro del código de `LoginActivity`:

- usuario: `admin`
- contraseña: `1234`

La contraseña no está guardada tal cual en el código, sino su hash SHA-256, y al ingresar se compara con el hash de lo que escribió la persona. Después de 3 intentos fallidos el botón se bloquea por 30 segundos. Si alguien intenta abrir otra pantalla sin haber iniciado sesión, la app lo devuelve al login.

Esto es provisorio, como pide la guía. Más adelante este acceso se cambiaría por una consulta a una base de datos.

## Base de datos

Usé Firebase (Firestore) en vez de SQLite. Mis razones son que los datos quedan en la nube y no dependen del celular, que no tengo que armar ni mantener un servidor propio como pasaría con MySQL, y que más adelante otro dispositivo podría leer los mismos datos.

Firestore no trabaja con tablas sino con colecciones de documentos. Acá hay una sola colección, `metricas`, y cada documento es un guardado hecho desde la pantalla de historial. Estos son sus campos:

| Campo | Tipo | Qué guarda |
|---|---|---|
| `fecha` | timestamp | Momento del guardado, lo pone el servidor. |
| `usuario` | texto | Usuario con la sesión iniciada. |
| `origen` | texto | `placa` si los datos vienen del Arduino, `demo` si vienen de la simulación. |
| `dispositivo` | texto | IP de la placa. |
| `lecturas` | entero | Cuántas lecturas se usaron para calcular las métricas. |
| `temp_min` | decimal | Temperatura mínima (°C). |
| `temp_max` | decimal | Temperatura máxima (°C). |
| `temp_promedio` | decimal | Temperatura promedio (°C). |
| `pct_confort` | decimal | Porcentaje de lecturas dentro de la zona de confort. |
| `confort_min` | decimal | Límite inferior de la zona de confort (17 °C). |
| `confort_max` | decimal | Límite superior de la zona de confort (25 °C). |
| `objetivo` | decimal | Temperatura objetivo al momento de guardar. |
| `humedad` | decimal | Humedad al momento de guardar (%). |
| `ventilador` | booleano | Si el ventilador estaba encendido. |
| `modo_auto` | booleano | Si el modo automático estaba activado. |

Las reglas de Firestore piden un usuario autenticado para escribir. Por eso, justo después de validar el login, la app abre una sesión anónima de Firebase.

## Cómo probarlo

Con la placa:

1. Dejar la placa lista como se explica arriba y enchufarla.
2. Anotar la IP que aparece en la matriz de LEDs.
3. Probar en el navegador del celular `http://IP_DE_LA_PLACA/estado`. Si todo va bien, devuelve el JSON.
4. Abrir la app, entrar con `admin` y `1234`, escribir la IP y tocar **Conectar**.

Sin la placa, basta con tocar **Modo demo** en la pantalla principal.

Mientras se prueba, conviene revisar que el login rechace una clave incorrecta, que el servo se mueva al prender el ventilador, que llegue la notificación cuando la temperatura pasa de 25 °C (por ejemplo, acercando la mano al sensor) y que al tocar **Guardar métricas** aparezca un documento nuevo en la colección `metricas` de la consola de Firebase.

## Lo que falta

- La comunicación con la placa va por HTTP sin cifrar. Para un prototipo en una red propia sirve, pero en un entorno real habría que protegerla.
- El usuario y la contraseña son genéricos y están dentro de la app.
- Las métricas se guardan en Firestore, pero la app todavía no las lee de vuelta. El gráfico del historial usa solo las lecturas que están en memoria.
- La IP de la placa se escribe a mano.

## Estructura del repositorio

```
app/                     App Android en Kotlin
  src/main/java/...      Pantallas, conexión con la placa, alertas y métricas
  src/main/res/          Layouts, textos y colores
arduino/control_clima/   Código de la placa Arduino UNO R4 WiFi
```
