package com.example.myapplication

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Placa simulada para probar la app sin el Arduino (botón "Modo demo").
 * Entiende las mismas rutas que el sketch y responde igual:
 *   /estado, /objetivo?valor=24, /auto?on=1, /ventilador?on=1
 *
 * La habitación simulada se calienta sola hasta ~33 °C y el ventilador la
 * enfría hasta ~12 °C, así se pueden ver todos los niveles de la pantalla y
 * probar las alertas de la zona de confort. Por ejemplo:
 *   - objetivo 35 °C  → el ventilador se apaga y la pieza pasa de 25 °C (alerta)
 *   - objetivo 10 °C  → el ventilador no para y la pieza baja de 17 °C (alerta)
 */
class PlacaDemo {

    private var temperatura = 23.0
    private var objetivo = 24.0
    private var auto = true
    private var ventilador = false

    fun atender(ruta: String): Estado {
        val valor = ruta.substringAfter('=', "")

        if (ruta.startsWith("/objetivo")) {
            val nuevo = valor.toDoubleOrNull()
            if (nuevo != null) objetivo = nuevo.coerceIn(OBJETIVO_MIN, OBJETIVO_MAX)
        } else if (ruta.startsWith("/auto")) {
            auto = valor == "1"
        } else if (ruta.startsWith("/ventilador")) {
            ventilador = valor == "1"
            auto = false
        } else {
            avanzar()       // cada consulta de /estado hace pasar el tiempo
        }

        // Misma lógica que controlAutomatico() del sketch
        if (auto) {
            if (temperatura >= objetivo + 0.5) ventilador = true
            if (temperatura <= objetivo - 0.5) ventilador = false
        }

        // Misma lógica que alerta() del sketch (margen de 3 °C sobre el objetivo)
        val alerta = when {
            temperatura >= objetivo + 3.0 -> "alta"
            temperatura <= objetivo - 3.0 -> "baja"
            else -> "ninguna"
        }

        return Estado(
            temperatura = (temperatura * 10).roundToInt() / 10.0,
            humedad = 55.0,
            ventilador = ventilador,
            auto = auto,
            objetivo = objetivo,
            alerta = alerta
        )
    }

    private fun avanzar() {
        val destino = if (ventilador) 12.0 else 33.0
        temperatura += (destino - temperatura) * 0.03 + Random.nextDouble(-0.05, 0.05)
    }
}
