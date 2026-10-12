package com.example.myapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

private const val VELOCIDAD_MAXIMA = 540f      // grados por segundo

/**
 * Ícono de ventilador de tres aspas. Al encenderlo acelera de a poco y al
 * apagarlo va frenando, como un ventilador de verdad.
 */
class VentiladorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val aspas = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val centro = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(140, 0, 0, 0)
    }

    private var encendido = false
    private var angulo = 0f
    private var velocidad = 0f          // grados por segundo
    private var ultimoCuadro = 0L

    fun encender(valor: Boolean) {
        if (encendido == valor) return
        encendido = valor
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Tiempo desde el cuadro anterior (con tope, por si la vista estuvo oculta)
        val ahora = SystemClock.uptimeMillis()
        val segundos = if (ultimoCuadro == 0L) 0f else minOf((ahora - ultimoCuadro) / 1000f, 0.05f)
        ultimoCuadro = ahora

        // La velocidad se acerca de a poco a la que corresponde
        val destino = if (encendido) VELOCIDAD_MAXIMA else 0f
        velocidad += (destino - velocidad) * minOf(segundos * 2.5f, 1f)
        if (!encendido && velocidad < 3f) velocidad = 0f
        angulo = (angulo + velocidad * segundos) % 360f

        val centroX = width / 2f
        val centroY = height / 2f
        val radio = minOf(width, height) / 2f

        aspas.alpha = if (encendido) 255 else 150
        canvas.save()
        canvas.rotate(angulo, centroX, centroY)
        for (i in 0 until 3) {
            canvas.drawOval(
                centroX - radio * 0.24f, centroY - radio * 0.98f,
                centroX + radio * 0.24f, centroY - radio * 0.06f,
                aspas
            )
            canvas.rotate(120f, centroX, centroY)
        }
        canvas.restore()
        canvas.drawCircle(centroX, centroY, radio * 0.2f, aspas)
        canvas.drawCircle(centroX, centroY, radio * 0.09f, centro)

        // Mientras gire, se pide el siguiente cuadro
        if (encendido || velocidad > 0f) {
            postInvalidateOnAnimation()
        } else {
            ultimoCuadro = 0L
        }
    }
}
