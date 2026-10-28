package com.example.myapplication

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Placa simulada para pruebas sin el Arduino (botón "Modo demo").
 * Responde las mismas rutas que el sketch: /estado, /objetivo, /auto y /ventilador.
 * Rango simulado: ~12 °C con el ventilador encendido y ~33 °C con el ventilador apagado.
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
            avanzar()       // avanza la simulación en cada consulta de /estado
        }

        // Misma lógica que controlAutomatico() del sketch
        if (auto) {
            if (temperatura >= objetivo + 0.5) ventilador = true
            if (temperatura <= objetivo - 0.5) ventilador = false
        }

        // Misma lógica que alerta() del sketch
        val alerta = when {
            temperatura > CONFORT_MAX -> "alta"
            temperatura < CONFORT_MIN -> "baja"
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

    /** Simula el cambio de temperatura de la habitación. */
    private fun avanzar() {
        val destino = if (ventilador) 12.0 else 33.0
        temperatura += (destino - temperatura) * 0.03 + Random.nextDouble(-0.05, 0.05)
    }
}
