package com.example.myapplication

import kotlin.math.roundToInt
import kotlin.random.Random

private const val TEMPERATURA_INICIAL = 23.0
private const val ENFRIAMIENTO_VENTILADOR = 10.0       // °C que el ventilador baja respecto al ambiente
private const val RAPIDEZ = 0.04                       // fracción de cambio por consulta
const val SIMULACION_MIN = 5.0                         // rango de la barra de simulación, en °C
const val SIMULACION_MAX = 40.0

/**
 * Placa simulada para pruebas sin el Arduino (botón "Modo demo").
 * Responde las mismas rutas que el sketch (/estado y /ventilador) y además
 * /simular?valor=X, que fija la temperatura de la habitación simulada.
 */
class PlacaDemo {

    private var temperatura = TEMPERATURA_INICIAL
    private var ambiente = TEMPERATURA_INICIAL         // temperatura hacia la que tiende la habitación
    private var ventilador = false

    /** Vuelve la simulación al estado inicial. */
    fun reiniciar() {
        temperatura = TEMPERATURA_INICIAL
        ambiente = TEMPERATURA_INICIAL
        ventilador = false
    }

    /** Responde una petición igual que la placa. */
    fun atender(ruta: String): Estado {
        val valor = ruta.substringAfter('=', "")
        when {
            ruta.startsWith("/ventilador") -> ventilador = valor == "1"
            ruta.startsWith("/simular") -> valor.toDoubleOrNull()?.let {
                val fijada = it.coerceIn(SIMULACION_MIN, SIMULACION_MAX)
                temperatura = fijada
                ambiente = fijada
            }
            else -> avanzar()       // avanza la simulación en cada consulta de /estado
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
            alerta = alerta
        )
    }

    /** Simula el cambio de temperatura: tiende al ambiente, o por debajo con el ventilador encendido. */
    private fun avanzar() {
        val destino = if (ventilador) ambiente - ENFRIAMIENTO_VENTILADOR else ambiente
        temperatura += (destino - temperatura) * RAPIDEZ + Random.nextDouble(-0.05, 0.05)
    }
}
