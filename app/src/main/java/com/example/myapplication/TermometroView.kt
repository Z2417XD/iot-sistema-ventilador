package com.example.myapplication

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private const val ESCALA_MIN = 0f          // escala visual del indicador, en °C
private const val ESCALA_MAX = 40f
private const val ANGULO_INICIO = 150f     // el arco deja una abertura abajo
private const val ANGULO_BARRIDO = 240f

/**
 * Indicador circular de temperatura.
 * - La franja más clara del arco es la zona de confort (CONFORT_MIN a CONFORT_MAX),
 *   con una marca y su valor en cada límite.
 * - El arco blanco se llena según la temperatura actual.
 * - El punto marca la temperatura objetivo.
 * El arco y el punto se mueven con una animación suave cada vez que cambian.
 * Se dibuja en blanco porque va sobre el fondo de color de la pantalla.
 */
class TermometroView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val pista = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(55, 255, 255, 255)
    }
    private val franjaConfort = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = Color.argb(110, 255, 255, 255)
    }
    private val arco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val marcaConfort = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val marcaBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val marcaCentro = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(150, 0, 0, 0)
    }
    private val etiqueta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textAlign = Paint.Align.CENTER
    }
    private val caja = RectF()
    private val suave = PathInterpolator(0.2f, 0f, 0f, 1f)

    private val fraccionConfortMin = fraccion(CONFORT_MIN.toFloat())
    private val fraccionConfortMax = fraccion(CONFORT_MAX.toFloat())
    private val textoConfortMin = grados(CONFORT_MIN.toFloat())
    private val textoConfortMax = grados(CONFORT_MAX.toFloat())
    private val textoEscalaMin = grados(ESCALA_MIN)
    private val textoEscalaMax = grados(ESCALA_MAX)

    private var hayLectura = false
    private var fraccionMostrada = 0f          // 0 = ESCALA_MIN, 1 = ESCALA_MAX
    private var fraccionObjetivo: Float? = null
    private var animador: ValueAnimator? = null

    /** Actualiza el indicador. Con temperatura null se muestra vacío. */
    fun mostrar(temperatura: Float?, objetivo: Float?) {
        val destinoTemperatura = if (temperatura != null) fraccion(temperatura) else 0f
        val destinoObjetivo = if (objetivo != null) fraccion(objetivo) else null
        val desdeTemperatura = fraccionMostrada
        // La primera vez el punto del objetivo aparece directo en su lugar.
        val desdeObjetivo = fraccionObjetivo ?: destinoObjetivo

        hayLectura = temperatura != null
        animador?.cancel()
        if (destinoObjetivo == null) fraccionObjetivo = null

        if (desdeTemperatura == destinoTemperatura && desdeObjetivo == destinoObjetivo) {
            fraccionObjetivo = destinoObjetivo
            invalidate()
            return
        }

        val animacion = ValueAnimator.ofFloat(0f, 1f)
        animacion.duration = 900L
        animacion.interpolator = suave
        animacion.addUpdateListener {
            val avance = it.animatedFraction
            fraccionMostrada = desdeTemperatura + (destinoTemperatura - desdeTemperatura) * avance
            if (desdeObjetivo != null && destinoObjetivo != null) {
                fraccionObjetivo = desdeObjetivo + (destinoObjetivo - desdeObjetivo) * avance
            }
            invalidate()
        }
        animacion.start()
        animador = animacion
    }

    private fun fraccion(valor: Float): Float =
        ((valor - ESCALA_MIN) / (ESCALA_MAX - ESCALA_MIN)).coerceIn(0f, 1f)

    private fun grados(valor: Float): String = String.format(Locale.getDefault(), "%.0f°", valor)

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animador?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val lado = minOf(width, height).toFloat()
        if (lado <= 0f) return

        val grosor = lado * 0.06f
        val centroX = width / 2f
        val centroY = height / 2f
        val radio = lado / 2f - grosor * 1.6f
        caja.set(centroX - radio, centroY - radio, centroX + radio, centroY + radio)

        // 1. Pista completa y, encima, la franja de la zona de confort
        pista.strokeWidth = grosor
        franjaConfort.strokeWidth = grosor
        canvas.drawArc(caja, ANGULO_INICIO, ANGULO_BARRIDO, false, pista)
        canvas.drawArc(
            caja,
            ANGULO_INICIO + ANGULO_BARRIDO * fraccionConfortMin,
            ANGULO_BARRIDO * (fraccionConfortMax - fraccionConfortMin),
            false,
            franjaConfort
        )

        // 2. Temperatura actual
        arco.strokeWidth = grosor
        if (hayLectura) {
            val barrido = maxOf(ANGULO_BARRIDO * fraccionMostrada, 0.1f)
            canvas.drawArc(caja, ANGULO_INICIO, barrido, false, arco)
        }

        // 3. Marcas de los límites de confort: una raya por fuera del arco y el
        //    valor por dentro, para que se vean aunque el arco blanco las cubra.
        marcaConfort.strokeWidth = grosor * 0.22f
        etiqueta.textSize = lado * 0.05f
        dibujarLimite(canvas, fraccionConfortMin, textoConfortMin, centroX, centroY, radio, grosor)
        dibujarLimite(canvas, fraccionConfortMax, textoConfortMax, centroX, centroY, radio, grosor)

        // 4. Punto de la temperatura objetivo
        val objetivo = fraccionObjetivo
        if (objetivo != null) {
            val angulo = Math.toRadians((ANGULO_INICIO + ANGULO_BARRIDO * objetivo).toDouble())
            val x = centroX + radio * cos(angulo).toFloat()
            val y = centroY + radio * sin(angulo).toFloat()
            canvas.drawCircle(x, y, grosor * 0.95f, marcaBorde)
            canvas.drawCircle(x, y, grosor * 0.55f, marcaCentro)
        }

        // 5. Extremos de la escala
        val anguloInicio = Math.toRadians(ANGULO_INICIO.toDouble())
        val anguloFin = Math.toRadians((ANGULO_INICIO + ANGULO_BARRIDO).toDouble())
        val yTexto = centroY + radio * sin(anguloInicio).toFloat() + grosor + etiqueta.textSize
        canvas.drawText(textoEscalaMin, centroX + radio * cos(anguloInicio).toFloat(), yTexto, etiqueta)
        canvas.drawText(textoEscalaMax, centroX + radio * cos(anguloFin).toFloat(), yTexto, etiqueta)
    }

    private fun dibujarLimite(
        canvas: Canvas,
        fraccionLimite: Float,
        texto: String,
        centroX: Float,
        centroY: Float,
        radio: Float,
        grosor: Float
    ) {
        val angulo = Math.toRadians((ANGULO_INICIO + ANGULO_BARRIDO * fraccionLimite).toDouble())
        val coseno = cos(angulo).toFloat()
        val seno = sin(angulo).toFloat()

        val desde = radio + grosor * 0.75f
        val hasta = radio + grosor * 1.4f
        canvas.drawLine(
            centroX + desde * coseno, centroY + desde * seno,
            centroX + hasta * coseno, centroY + hasta * seno,
            marcaConfort
        )

        val radioTexto = radio - grosor * 1.9f
        canvas.drawText(
            texto,
            centroX + radioTexto * coseno,
            centroY + radioTexto * seno + etiqueta.textSize * 0.35f,
            etiqueta
        )
    }
}
