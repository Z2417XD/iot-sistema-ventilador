package com.example.myapplication

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.PathInterpolator

/** Fondo degradado de una pantalla, con transición de color entre niveles. */
class FondoAnimado(vista: View) {

    private val suave = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val mezclaColor = ArgbEvaluator()
    private var arriba = NivelTemperatura.SIN_DATOS.colorArriba
    private var abajo = NivelTemperatura.SIN_DATOS.colorAbajo
    private val degradado = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(arriba, abajo)
    )
    private var actual = NivelTemperatura.SIN_DATOS
    private var animacion: ValueAnimator? = null

    init {
        vista.background = degradado
    }

    fun mostrar(nivel: NivelTemperatura, animar: Boolean = true) {
        if (nivel == actual) return
        actual = nivel
        animacion?.cancel()

        if (!animar) {
            arriba = nivel.colorArriba
            abajo = nivel.colorAbajo
            degradado.colors = intArrayOf(arriba, abajo)
            return
        }

        val desdeArriba = arriba
        val desdeAbajo = abajo
        val nueva = ValueAnimator.ofFloat(0f, 1f)
        nueva.duration = 900L
        nueva.interpolator = suave
        nueva.addUpdateListener {
            val avance = it.animatedFraction
            arriba = mezclaColor.evaluate(avance, desdeArriba, nivel.colorArriba) as Int
            abajo = mezclaColor.evaluate(avance, desdeAbajo, nivel.colorAbajo) as Int
            degradado.colors = intArrayOf(arriba, abajo)
        }
        nueva.start()
        animacion = nueva
    }

    fun detener() {
        animacion?.cancel()
    }
}
