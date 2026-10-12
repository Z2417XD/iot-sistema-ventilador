package com.example.myapplication

import androidx.annotation.StringRes

// ---------------------------------------------------------------------------
// Reglas de temperatura de la app. Para cambiar la zona de confort o los
// límites, basta con editar estas constantes: el indicador, el gráfico, los
// colores y las alertas las usan todas.
// ---------------------------------------------------------------------------

/** Zona de confort de la habitación, en °C (los límites cuentan como confort). */
const val CONFORT_MIN = 17.0
const val CONFORT_MAX = 25.0

/** Bajo FRIO_EXTREMO la pieza está "Fría"; sobre CALOR_EXTREMO, "Muy caliente". */
const val FRIO_EXTREMO = 14.0
const val CALOR_EXTREMO = 30.0

/**
 * Para que una alerta vuelva a sonar, la temperatura tiene que entrar al menos
 * este margen a la zona de confort. Evita avisos repetidos cuando la lectura
 * oscila justo en un límite (por ejemplo 25,0 → 25,1 → 25,0 → 25,1).
 */
const val MARGEN_ALERTA = 0.5

/** Límites de la temperatura objetivo (los mismos que el Slider del layout). */
const val OBJETIVO_MIN = 10.0
const val OBJETIVO_MAX = 40.0

/** Lecturas que guarda el gráfico: 150 lecturas x 2 s = 5 minutos. */
const val MUESTRAS_HISTORIAL = 150

/** "IP" interna del modo demo. */
const val IP_DEMO = "demo"

/** Lo que responde la placa en /estado. */
data class Estado(
    val temperatura: Double?,   // null si el DHT11 no entregó lectura
    val humedad: Double?,
    val ventilador: Boolean,    // true = servo "ventilando"
    val auto: Boolean,          // true = la placa decide sola según el objetivo
    val objetivo: Double,       // temperatura deseada en °C
    val alerta: String          // la calcula la placa; la app usa la zona de confort
)

/** Dónde está la temperatura respecto a la zona de confort. */
enum class Zona { BAJO, CONFORT, SOBRE }

/**
 * Cómo se siente la habitación. Cada nivel tiene su texto y los dos colores
 * del degradado de fondo (arriba y abajo).
 */
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

/** Zona exacta de una temperatura (la que se muestra en pantalla). */
fun zonaDe(temperatura: Double): Zona = when {
    temperatura < CONFORT_MIN -> Zona.BAJO
    temperatura > CONFORT_MAX -> Zona.SOBRE
    else -> Zona.CONFORT
}

/**
 * Zona para las alertas: igual que zonaDe(), pero para dar por "vuelta a la
 * normalidad" una alerta hay que entrar MARGEN_ALERTA grados a la zona de confort.
 */
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
