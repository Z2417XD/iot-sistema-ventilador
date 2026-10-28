package com.example.myapplication

import androidx.annotation.StringRes

// ---------------------------------------------------------------------------
// Constantes de temperatura usadas por el indicador, el gráfico, los colores y las alertas.
// ---------------------------------------------------------------------------

/** Límites de la zona de confort en °C (ambos incluidos). */
const val CONFORT_MIN = 17.0
const val CONFORT_MAX = 25.0

/** Límites de los niveles "Fría" y "Muy caliente" en °C. */
const val FRIO_EXTREMO = 14.0
const val CALOR_EXTREMO = 30.0

/** Margen en °C para dar por terminada una alerta; evita avisos repetidos en el límite. */
const val MARGEN_ALERTA = 0.5

/** Límites de la temperatura objetivo en °C. */
const val OBJETIVO_MIN = 10.0
const val OBJETIVO_MAX = 40.0

/** Cantidad de lecturas del historial (150 lecturas cada 2 s = 5 minutos). */
const val MUESTRAS_HISTORIAL = 150

/** Valor de IP que activa el modo demo. */
const val IP_DEMO = "demo"

/** Estado que entrega la placa en formato JSON. */
data class Estado(
    val temperatura: Double?,   // °C; null si el sensor no entregó lectura
    val humedad: Double?,
    val ventilador: Boolean,    // true = ventilador encendido
    val auto: Boolean,          // true = control automático según el objetivo
    val objetivo: Double,       // temperatura deseada en °C
    val alerta: String          // "alta", "baja" o "ninguna"
)

/** Posición de la temperatura respecto a la zona de confort. */
enum class Zona { BAJO, CONFORT, SOBRE }

/** Nivel de temperatura con su texto y los colores del degradado de fondo (arriba y abajo). */
enum class NivelTemperatura(
    @param:StringRes val titulo: Int,
    val colorArriba: Int,
    val colorAbajo: Int
) {
    SIN_DATOS(R.string.nivel_sin_datos, 0xFF455A64.toInt(), 0xFF1B2529.toInt()),        // gris
    FRIA(R.string.nivel_fria, 0xFF1565C0.toInt(), 0xFF0A1F4D.toInt()),                  // azul
    FRESCA(R.string.nivel_fresca, 0xFF00838F.toInt(), 0xFF05343F.toInt()),              // turquesa
    AGRADABLE(R.string.nivel_agradable, 0xFF2E7D32.toInt(), 0xFF0C3B1E.toInt()),        // verde
    CALUROSA(R.string.nivel_calurosa, 0xFFC4560B.toInt(), 0xFF5E1D05.toInt()),          // naranja
    MUY_CALIENTE(R.string.nivel_muy_caliente, 0xFFC62828.toInt(), 0xFF5A0E14.toInt())   // rojo
}

/** Zona de confort que corresponde a una temperatura. */
fun zonaDe(temperatura: Double): Zona = when {
    temperatura < CONFORT_MIN -> Zona.BAJO
    temperatura > CONFORT_MAX -> Zona.SOBRE
    else -> Zona.CONFORT
}

/** Zona usada para las alertas; aplica MARGEN_ALERTA al volver a la zona de confort. */
fun zonaParaAlertas(temperatura: Double, anterior: Zona?): Zona {
    val exacta = zonaDe(temperatura)
    return when {
        anterior == Zona.SOBRE && exacta == Zona.CONFORT &&
            temperatura > CONFORT_MAX - MARGEN_ALERTA -> Zona.SOBRE
        anterior == Zona.BAJO && exacta == Zona.CONFORT &&
            temperatura < CONFORT_MIN + MARGEN_ALERTA -> Zona.BAJO
        else -> exacta
    }
}

/** Nivel (texto y color) que corresponde a una temperatura. */
fun nivelDe(temperatura: Double?): NivelTemperatura = when {
    temperatura == null -> NivelTemperatura.SIN_DATOS
    temperatura < FRIO_EXTREMO -> NivelTemperatura.FRIA
    temperatura < CONFORT_MIN -> NivelTemperatura.FRESCA
    temperatura <= CONFORT_MAX -> NivelTemperatura.AGRADABLE
    temperatura <= CALOR_EXTREMO -> NivelTemperatura.CALUROSA
    else -> NivelTemperatura.MUY_CALIENTE
}
