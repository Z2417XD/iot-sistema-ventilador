package com.example.myapplication

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.PathInterpolator
import java.util.Locale

/**
 * Gráfico con las últimas lecturas de temperatura. La curva se dibuja
 * suavizada, se desliza hacia la izquierda cuando llega una lectura nueva y
 * la escala vertical se acomoda de a poco en vez de saltar.
 * La franja clara es la zona de confort y la línea punteada, el objetivo.
 * Se dibuja en blanco porque va sobre el fondo de color de la pantalla.
 */
class HistorialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val densidad = resources.displayMetrics.density

    private val linea = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val punto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val lineaObjetivo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(150, 255, 255, 255)
    }
    private val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 255, 255, 255)
    }
    private val franjaConfort = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(30, 255, 255, 255)
    }
    private val textoEsperando = context.getString(R.string.esperando_lecturas)

    private val trazo = Path()
    private val area = Path()
    private val guia = Path()
    private val suave = PathInterpolator(0.2f, 0f, 0f, 1f)

    private var datos: List<Float> = emptyList()
    private var objetivo: Float? = null

    // Valores que se animan
    private var escalaLista = false
    private var minimoMostrado = 0f
    private var maximoMostrado = 1f
    private var corrimiento = 0f        // 1 -> 0: la curva avanza un paso hacia la izquierda
    private var animador: ValueAnimator? = null

    init {
        linea.strokeWidth = 2.5f * densidad
        lineaObjetivo.strokeWidth = 1.5f * densidad
        lineaObjetivo.pathEffect = DashPathEffect(floatArrayOf(6f * densidad, 4f * densidad), 0f)
        texto.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics
        )
    }

    /**
     * Actualiza el gráfico. "nuevaLectura" indica que se agregó un punto al
     * final de la lista (y por eso la curva debe deslizarse).
     */
    fun mostrar(datos: List<Float>, objetivo: Float?, nuevaLectura: Boolean) {
        this.datos = datos
        this.objetivo = objetivo
        animador?.cancel()

        if (datos.size < 2) {
            escalaLista = false
            corrimiento = 0f
            invalidate()
            return
        }

        // Escala vertical: cubre las lecturas, el objetivo y la zona de confort,
        // con 1 °C de margen.
        var minimo = minOf(datos[0], CONFORT_MIN.toFloat())
        var maximo = maxOf(datos[0], CONFORT_MAX.toFloat())
        for (valor in datos) {
            if (valor < minimo) minimo = valor
            if (valor > maximo) maximo = valor
        }
        if (objetivo != null) {
            if (objetivo < minimo) minimo = objetivo
            if (objetivo > maximo) maximo = objetivo
        }
        val minimoDestino = minimo - 1f
        val maximoDestino = maximo + 1f

        if (!escalaLista) {
            minimoMostrado = minimoDestino
            maximoMostrado = maximoDestino
            escalaLista = true
        }
        val minimoDesde = minimoMostrado
        val maximoDesde = maximoMostrado
        val corrimientoDesde = if (nuevaLectura) 1f else corrimiento

        val animacion = ValueAnimator.ofFloat(0f, 1f)
        animacion.duration = 700L
        animacion.interpolator = suave
        animacion.addUpdateListener {
            val avance = it.animatedFraction
            minimoMostrado = minimoDesde + (minimoDestino - minimoDesde) * avance
            maximoMostrado = maximoDesde + (maximoDestino - maximoDesde) * avance
            corrimiento = corrimientoDesde * (1f - avance)
            invalidate()
        }
        animacion.start()
        animador = animacion
    }

    private fun etiqueta(valor: Float): String =
        String.format(Locale.getDefault(), "%.0f°", valor)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Relleno bajo la curva: blanco que se desvanece hacia abajo.
        relleno.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Color.argb(90, 255, 255, 255), Color.argb(0, 255, 255, 255),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animador?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val izquierda = 40f * densidad          // espacio para las etiquetas
        val derecha = width - 12f * densidad
        val arriba = 12f * densidad
        val abajo = height - 12f * densidad
        if (derecha <= izquierda || abajo <= arriba) return

        if (datos.size < 2) {
            texto.textAlign = Paint.Align.CENTER
            canvas.drawText(textoEsperando, width / 2f, height / 2f, texto)
            return
        }

        val rango = maximoMostrado - minimoMostrado
        if (rango <= 0f) return
        val alto = abajo - arriba

        // La lectura más nueva queda en el borde derecho. Mientras dura la
        // animación todo está corrido un paso a la derecha y va volviendo.
        val pasoX = (derecha - izquierda) / (MUESTRAS_HISTORIAL - 1)
        val inicioX = derecha - pasoX * (datos.size - 1) + pasoX * corrimiento

        trazo.reset()
        var xAnterior = 0f
        var yAnterior = 0f
        for (i in datos.indices) {
            val x = inicioX + pasoX * i
            val y = abajo - (datos[i] - minimoMostrado) / rango * alto
            if (i == 0) {
                trazo.moveTo(x, y)
            } else {
                // Curva suave entre un punto y el siguiente
                val medio = (xAnterior + x) / 2f
                trazo.cubicTo(medio, yAnterior, medio, y, x, y)
            }
            xAnterior = x
            yAnterior = y
        }
        area.set(trazo)
        area.lineTo(xAnterior, abajo)
        area.lineTo(inicioX, abajo)
        area.close()

        canvas.save()
        canvas.clipRect(izquierda, 0f, derecha + 8f * densidad, height.toFloat())
        // Franja de la zona de confort, detrás de la curva
        val yConfortMax = abajo - (CONFORT_MAX.toFloat() - minimoMostrado) / rango * alto
        val yConfortMin = abajo - (CONFORT_MIN.toFloat() - minimoMostrado) / rango * alto
        canvas.drawRect(izquierda, yConfortMax, derecha + 8f * densidad, yConfortMin, franjaConfort)
        canvas.drawPath(area, relleno)
        canvas.drawPath(trazo, linea)
        canvas.drawCircle(xAnterior, yAnterior, 4.5f * densidad, punto)
        canvas.restore()

        val meta = objetivo
        if (meta != null) {
            val y = abajo - (meta - minimoMostrado) / rango * alto
            guia.reset()
            guia.moveTo(izquierda, y)
            guia.lineTo(derecha, y)
            canvas.drawPath(guia, lineaObjetivo)
        }

        texto.textAlign = Paint.Align.LEFT
        canvas.drawText(etiqueta(maximoMostrado), 0f, arriba + texto.textSize * 0.4f, texto)
        canvas.drawText(etiqueta(minimoMostrado), 0f, abajo + texto.textSize * 0.4f, texto)
    }
}
