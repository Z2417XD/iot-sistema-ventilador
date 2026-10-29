# Sistema de control de clima con Arduino y Android

Proyecto de la asignatura **Aplicaciones Móviles para IoT (TI3042)**, evaluación sumativa de la Unidad 2.

La idea es simple: una habitación que se puede calentar de más, y una aplicación en el celular que avisa cuando eso pasa y deja subir o bajar la temperatura deseada para que un ventilador reaccione. Como no tengo un ventilador real conectado, uso un micro servo SG90 que se mueve de lado a lado y hace de ventilador.

## Qué hace

Una placa Arduino UNO R4 WiFi lee la temperatura y la humedad con un sensor DHT11 y las publica en la red a través de un pequeño servidor web. La app Android se conecta a esa placa por WiFi, muestra los datos en tiempo real y permite mandarle órdenes.

Desde la app se puede:

- ver la temperatura y la humedad actuales, con un termómetro y un fondo que cambia de color según qué tan fría o caliente está la habitación;
- definir la temperatura objetivo (entre 10 y 40 °C) con botones o un deslizador;
- encender o apagar el ventilador a mano, o dejarlo en modo automático;
- recibir una notificación cuando la temperatura sale de la zona de confort (entre 17 y 25 °C);
- revisar un gráfico con los últimos 5 minutos de lecturas y sus métricas (mínima, máxima, promedio y porcentaje de tiempo dentro de la zona de confort);
- guardar esas métricas en la nube.

También tiene un **modo demo**, que simula la placa dentro del propio celular. Lo hice para poder probar la app sin tener el Arduino a mano.

## Cómo está armado

```
Celular Android  <----- WiFi (HTTP) ----->  Arduino UNO R4 WiFi
  (app en Kotlin)                            DHT11 (sensor) + servo SG90
        |
        +----- internet ----->  Firebase (Authentication + Firestore)
```

### Conexión con la placa

Elegí **WiFi con peticiones HTTP** y no Bluetooth por una razón práctica: la UNO R4 WiFi ya trae el módulo WiFi integrado, el alcance es mucho mayor que el de Bluetooth y probar la comunicación es fácil, porque basta abrir una dirección en el navegador del celular. El consumo de energía no es un problema porque la placa va conectada a corriente.

En la universidad los puertos de la red están bloqueados, así que la placa se conecta al punto de acceso (hotspot) del celular. Eso tiene una consecuencia a tener en cuenta: **la IP de la placa puede cambiar cada vez que se reinicia**. Por eso, al arrancar, la placa muestra su IP desplazándose por la matriz de LEDs y también la imprime por el monitor serie. Esa IP es la que se escribe en la app.

La placa responde en JSON. Estas son las rutas que atiende:

| Ruta | Para qué sirve |
|---|---|
| `/estado` | Devuelve temperatura, humedad, estado del ventilador, modo automático, objetivo y alerta. |
| `/objetivo?valor=24.0` | Cambia la temperatura objetivo. |
| `/auto?on=1` o `on=0` | Activa o desactiva el modo automático. |
| `/ventilador?on=1` o `on=0` | Enciende o apaga el ventilador a mano (esto desactiva el modo automático). |

Ejemplo de respuesta:

```json
{"temperatura":23.0,"humedad":55,"ventilador":false,"auto":true,"objetivo":24.0,"alerta":"ninguna"}
```

La app consulta `/estado` cada 2 segundos mientras hay una pantalla abierta. Si la placa no responde en 3 segundos, la app muestra que se perdió la conexión y sigue intentando. Por su parte, la placa revisa su WiFi cada 10 segundos y se vuelve a conectar sola si se cae.

### Herramientas de desarrollo

- **Android Studio** con **Kotlin**, mínimo Android 7.0 (API 24).
- **Material Components** para la interfaz.
- **Firebase** (Authentication y Firestore) para el inicio de sesión de respaldo y el almacenamiento.
- **Arduino IDE** para la placa, con las librerías `DHT sensor library` (Adafruit), `ArduinoGraphics`, y `Servo`, `WiFiS3` y `Arduino_LED_Matrix`, que ya vienen con la placa.

## Inicio de sesión

La app arranca en una pantalla de login. Por ahora hay un único usuario genérico, definido dentro del código de `LoginActivity`:

- usuario: `admin`
- contraseña: `1234`

La contraseña no está escrita tal cual en el código: se guarda su hash SHA-256 y al ingresar se compara con el hash de lo que escribió la persona. Además, después de 3 intentos fallidos el botón se bloquea durante 30 segundos.

Es una solución provisoria, tal como lo pide la guía: más adelante este acceso se reemplazaría por una consulta a base de datos. Si no hay sesión iniciada, cualquier otra pantalla de la app devuelve al login.

## Base de datos

Elegí **Firebase (Firestore)** en lugar de SQLite por tres motivos. Primero, los datos quedan en la nube y se pueden ver desde cualquier lugar, sin depender del celular. Segundo, no hay que montar ni mantener un servidor propio, algo que con MySQL sí habría tenido que hacer. Y tercero, deja la puerta abierta para que, en las siguientes unidades, otro dispositivo lea los mismos datos.

Firestore es una base de datos de documentos, así que no tiene tablas como tal. El equivalente es una **colección**. Este proyecto usa una sola, llamada `metricas`, donde cada documento corresponde a un guardado hecho desde la pantalla de historial.

| Campo | Tipo | Descripción |
|---|---|---|
| `fecha` | timestamp | Momento del guardado (lo pone el servidor). |
| `usuario` | texto | Usuario con la sesión iniciada. |
| `origen` | texto | `placa` si los datos vienen del Arduino, `demo` si vienen de la simulación. |
| `dispositivo` | texto | IP de la placa consultada. |
| `lecturas` | número entero | Cantidad de lecturas usadas para calcular las métricas. |
| `temp_min` | número decimal | Temperatura mínima registrada (°C). |
| `temp_max` | número decimal | Temperatura máxima registrada (°C). |
| `temp_promedio` | número decimal | Temperatura promedio (°C). |
| `pct_confort` | número decimal | Porcentaje de lecturas dentro de la zona de confort. |
| `confort_min` | número decimal | Límite inferior de la zona de confort (17 °C). |
| `confort_max` | número decimal | Límite superior de la zona de confort (25 °C). |
| `objetivo` | número decimal | Temperatura objetivo al momento de guardar. |
| `humedad` | número decimal | Humedad al momento de guardar (%). |
| `ventilador` | booleano | Si el ventilador estaba encendido. |
| `modo_auto` | booleano | Si el modo automático estaba activado. |

Para poder escribir en Firestore, la app inicia una sesión anónima de Firebase justo después de validar el login, porque las reglas de la base de datos exigen un usuario autenticado.

## Cómo probarlo

**Con la placa:**

1. Abre `arduino/control_clima/control_clima.ino`, escribe el nombre y la clave de tu WiFi (o del hotspot del celular) en `WIFI_NOMBRE` y `WIFI_CLAVE`, y súbelo a la placa.
2. Conecta el DHT11 al pin D2 y el servo al pin D9.
3. Anota la IP que aparece en la matriz de LEDs o en el monitor serie.
4. Prueba en el navegador del celular: `http://IP_DE_LA_PLACA/estado` debería devolver el JSON.
5. Abre la app, inicia sesión con `admin` / `1234`, escribe la IP y pulsa **Conectar**.

**Sin la placa:** en la pantalla principal, pulsa **Modo demo**.

Qué conviene revisar durante la prueba:

- que el login rechace una clave incorrecta y deje entrar con la correcta;
- que al cambiar el objetivo o encender el ventilador, el servo reaccione;
- que, al subir la temperatura sobre 25 °C (por ejemplo acercando la mano al sensor), llegue la notificación;
- que al pulsar **Guardar métricas** en el historial aparezca un documento nuevo en la colección `metricas` de la consola de Firebase.

## Limitaciones conocidas

Prefiero dejar claro lo que todavía no está resuelto:

- La comunicación con la placa va por HTTP sin cifrar. Para este prototipo en una red controlada es suficiente, pero no sería aceptable en un entorno real.
- El usuario y la contraseña del login son genéricos y están dentro de la app.
- Las métricas se guardan en Firestore, pero la app todavía no las lee de vuelta; el gráfico del historial usa solo las lecturas que están en memoria.
- La IP de la placa debe ingresarse a mano, y puede cambiar si el hotspot la reasigna.

## Estructura del repositorio

```
app/                     Aplicación Android (Kotlin)
  src/main/java/...      Pantallas, conexión con la placa, alertas y métricas
  src/main/res/          Layouts, textos y colores
arduino/control_clima/   Código de la placa Arduino UNO R4 WiFi
```
