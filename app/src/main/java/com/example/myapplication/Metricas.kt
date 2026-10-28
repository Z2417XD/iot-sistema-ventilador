package com.example.myapplication

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/** Métricas de las lecturas del historial (últimos 5 minutos). */
data class Metricas(
    val lecturas: Int,
    val minima: Double,
    val maxima: Double,
    val promedio: Double,
    val porcentajeConfort: Double   // % de lecturas dentro de la zona de confort
)

/** Calcula las métricas de una lista de temperaturas, o null si está vacía. */
fun calcularMetricas(temperaturas: List<Float>): Metricas? {
    if (temperaturas.isEmpty()) return null
    val valores = temperaturas.map { it.toDouble() }
    val enConfort = valores.count { it in CONFORT_MIN..CONFORT_MAX }
    return Metricas(
        lecturas = valores.size,
        minima = valores.min(),
        maxima = valores.max(),
        promedio = valores.average(),
        porcentajeConfort = 100.0 * enConfort / valores.size
    )
}

/**
 * Guarda las métricas en la colección "metricas" de Firestore (un documento por guardado).
 * Campos:
 *   fecha, usuario, origen ("placa" o "demo"), dispositivo (IP), lecturas,
 *   temp_min, temp_max, temp_promedio, pct_confort, confort_min, confort_max,
 *   objetivo, humedad, ventilador, modo_auto
 */
fun guardarMetricas(metricas: Metricas, estado: Estado?, alTerminar: (Boolean) -> Unit) {
    val documento = hashMapOf(
        "fecha" to FieldValue.serverTimestamp(),
        "usuario" to Sesion.usuario,
        "origen" to if (Placa.ip == IP_DEMO) "demo" else "placa",
        "dispositivo" to Placa.ip,
        "lecturas" to metricas.lecturas,
        "temp_min" to metricas.minima,
        "temp_max" to metricas.maxima,
        "temp_promedio" to metricas.promedio,
        "pct_confort" to metricas.porcentajeConfort,
        "confort_min" to CONFORT_MIN,
        "confort_max" to CONFORT_MAX,
        "objetivo" to estado?.objetivo,
        "humedad" to estado?.humedad,
        "ventilador" to estado?.ventilador,
        "modo_auto" to estado?.auto
    )

    FirebaseFirestore.getInstance()
        .collection("metricas")
        .add(documento)
        .addOnSuccessListener { alTerminar(true) }
        .addOnFailureListener { error ->
            Log.w("Metricas", "No se pudieron guardar las métricas", error)
            alTerminar(false)
        }
}
