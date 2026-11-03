package com.example.myapplication

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    // Vistas de activity_main.xml
    private lateinit var btnConectar: Button
    private lateinit var btnDemo: Button
    private lateinit var tvConexion: TextView
    private lateinit var tvError: TextView
    private lateinit var termometro: TermometroView
    private lateinit var tvTemperatura: TextView
    private lateinit var tvEstado: TextView
    private lateinit var tvDetalle: TextView
    private lateinit var tvHumedad: TextView
    private lateinit var swVentilador: MaterialSwitch
    private lateinit var tvVentiladorEstado: TextView
    private lateinit var ventiladorIcono: VentiladorView
    private lateinit var btnVerHistorial: Button
    private lateinit var cardSimulacion: View
    private lateinit var slSimulacion: Slider

    private lateinit var fondo: FondoAnimado

    private var actualizandoPantalla = false    // evita que el switch reenvíe órdenes
    private var arrastrandoBarra = false        // evita mover la barra mientras se arrastra

    // --- Animaciones ---
    private val suave = PathInterpolator(0.2f, 0f, 0f, 1f)   // curva de aceleración de las animaciones
    private var temperaturaMostrada: Float? = null
    private var animacionTemperatura: ValueAnimator? = null

    // Solicitud del permiso de notificaciones (Android 13+).
    private val pedirPermiso =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Recibe los cambios de Placa (lectura, error o conexión nueva). */
    private val oyente: (Boolean) -> Unit = { pintar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Redirige al login si no hay sesión activa.
        if (!Sesion.activa) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        // Íconos claros en las barras del sistema.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_main)
        fondo = FondoAnimado(findViewById(R.id.main))

        btnConectar = findViewById(R.id.btnConectar)
        btnDemo = findViewById(R.id.btnDemo)
        tvConexion = findViewById(R.id.tvConexion)
        tvError = findViewById(R.id.tvError)
        termometro = findViewById(R.id.termometro)
        tvTemperatura = findViewById(R.id.tvTemperatura)
        tvEstado = findViewById(R.id.tvEstado)
        tvDetalle = findViewById(R.id.tvDetalle)
        tvHumedad = findViewById(R.id.tvHumedad)
        swVentilador = findViewById(R.id.swVentilador)
        tvVentiladorEstado = findViewById(R.id.tvVentiladorEstado)
        ventiladorIcono = findViewById(R.id.ventiladorIcono)
        btnVerHistorial = findViewById(R.id.btnVerHistorial)
        cardSimulacion = findViewById(R.id.cardSimulacion)
        slSimulacion = findViewById(R.id.slSimulacion)

        // Conexión automática con la placa al abrir la app.
        if (Placa.ip.isBlank()) Placa.conectar(IP_PLACA)

        // --- Conexión ---
        btnConectar.setOnClickListener { Placa.conectar(IP_PLACA) }
        // Activa el modo demo, o vuelve a la placa al salir de él.
        btnDemo.setOnClickListener {
            Placa.conectar(if (Placa.enModoDemo) IP_PLACA else IP_DEMO)
        }

        // --- Ventilador (control manual) ---
        swVentilador.setOnCheckedChangeListener { _, encendido ->
            if (!actualizandoPantalla) Placa.enviarOrden("/ventilador?on=${if (encendido) 1 else 0}")
        }

        // --- Simulación (solo modo demo) ---
        slSimulacion.setLabelFormatter { getString(R.string.simulacion_valor, it) }
        slSimulacion.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                arrastrandoBarra = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                arrastrandoBarra = false
                Placa.enviarOrden("/simular?valor=${slider.value}")
            }
        })

        btnVerHistorial.setOnClickListener {
            startActivity(Intent(this, HistorialActivity::class.java))
        }

        Alertas.crearCanal(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Alertas.tienePermiso(this)) {
            pedirPermiso.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        mostrarSinDatos()
        if (savedInstanceState == null) animarEntrada(findViewById(R.id.contenido))
    }

    // Registro en Placa mientras la pantalla está visible.
    override fun onStart() {
        super.onStart()
        if (!Sesion.activa) return
        Placa.registrar(this, oyente)
        pintar()
    }

    override fun onStop() {
        super.onStop()
        Placa.desregistrar(oyente)
    }

    override fun onDestroy() {
        super.onDestroy()
        animacionTemperatura?.cancel()
        if (::fondo.isInitialized) fondo.detener()
    }

    // -----------------------------------------------------------------------
    // Pantalla
    // -----------------------------------------------------------------------

    /** Actualiza la pantalla con el estado actual de Placa. */
    private fun pintar() {
        tvConexion.text = textoConexion()
        btnDemo.setText(if (Placa.enModoDemo) R.string.salir_demo else R.string.modo_demo)
        cardSimulacion.visibility = if (Placa.enModoDemo) View.VISIBLE else View.GONE

        val estado = Placa.estado
        if (estado == null) {
            mostrarSinDatos()
            if (Placa.hayError) {
                tvDetalle.setText(R.string.detalle_sin_conexion)
                tvError.text = getString(R.string.error_conexion, RED_PLACA)
                tvError.visibility = View.VISIBLE
            } else {
                tvError.visibility = View.GONE
            }
            return
        }
        tvError.visibility = View.GONE

        // Estado de la habitación
        val temperatura = estado.temperatura
        val nivel = Placa.nivel
        fondo.mostrar(nivel)
        mostrarTemperatura(temperatura?.toFloat())
        cambiarTexto(
            tvEstado,
            getString(if (temperatura != null) nivel.titulo else R.string.sin_lectura)
        )
        tvDetalle.text = detalle(temperatura)
        tvHumedad.text = estado.humedad?.let { getString(R.string.humedad_valor, it) }
        termometro.mostrar(temperatura?.toFloat())
        sincronizarBarra(temperatura)

        // Ventilador: no se sobrescribe mientras hay órdenes sin respuesta.
        if (Placa.ordenesPendientes == 0) {
            // La bandera evita que el listener reenvíe la orden.
            actualizandoPantalla = true
            swVentilador.isChecked = estado.ventilador
            actualizandoPantalla = false
        }
        tvVentiladorEstado.setText(
            if (estado.ventilador) R.string.ventilador_encendido else R.string.ventilador_apagado
        )
        ventiladorIcono.encender(estado.ventilador)
        swVentilador.isEnabled = true
    }

    /** Lleva la barra de simulación a la temperatura actual, salvo mientras se arrastra. */
    private fun sincronizarBarra(temperatura: Double?) {
        if (!Placa.enModoDemo || temperatura == null) return
        if (arrastrandoBarra || Placa.ordenesPendientes > 0) return
        slSimulacion.value = temperatura.roundToInt().toDouble()
            .coerceIn(SIMULACION_MIN, SIMULACION_MAX).toFloat()
    }

    /** Texto del estado de la conexión. */
    private fun textoConexion(): String = when {
        Placa.ip.isBlank() -> getString(R.string.conexion_inicio, RED_PLACA)
        Placa.enModoDemo -> getString(R.string.conexion_demo)
        Placa.hayError -> getString(R.string.conexion_sin)
        Placa.estado == null -> getString(R.string.conexion_conectando)
        else -> getString(R.string.conexion_conectado)
    }

    /** Texto de la posición de la temperatura respecto a la zona de confort. */
    private fun detalle(temperatura: Double?): String {
        if (temperatura == null) return getString(R.string.detalle_sensor)
        return when (Placa.zona) {
            Zona.SOBRE ->
                if (temperatura > CONFORT_MAX) getString(R.string.detalle_sobre, temperatura - CONFORT_MAX)
                else getString(R.string.detalle_limite)
            Zona.BAJO ->
                if (temperatura < CONFORT_MIN) getString(R.string.detalle_bajo, CONFORT_MIN - temperatura)
                else getString(R.string.detalle_limite)
            else -> getString(R.string.detalle_confort, CONFORT_MIN, CONFORT_MAX)
        }
    }

    /** Muestra la pantalla sin datos de la placa. */
    private fun mostrarSinDatos() {
        fondo.mostrar(NivelTemperatura.SIN_DATOS)
        mostrarTemperatura(null)
        cambiarTexto(tvEstado, getString(NivelTemperatura.SIN_DATOS.titulo))
        tvDetalle.setText(R.string.detalle_conectate)
        tvHumedad.text = null
        tvVentiladorEstado.setText(R.string.sin_datos)
        ventiladorIcono.encender(false)
        termometro.mostrar(null)
        actualizandoPantalla = true
        swVentilador.isChecked = false
        actualizandoPantalla = false
        swVentilador.isEnabled = false
    }

    // -----------------------------------------------------------------------
    // Animaciones
    // -----------------------------------------------------------------------

    /** Anima el número de temperatura hasta el valor nuevo. */
    private fun mostrarTemperatura(nueva: Float?) {
        animacionTemperatura?.cancel()
        if (nueva == null) {
            temperaturaMostrada = null
            tvTemperatura.setText(R.string.sin_valor)
            return
        }
        val desde = temperaturaMostrada ?: 0f
        if (desde == nueva) {
            tvTemperatura.text = getString(R.string.temperatura_valor, nueva)
            return
        }
        val animacion = ValueAnimator.ofFloat(desde, nueva)
        animacion.duration = 800L
        animacion.interpolator = suave
        animacion.addUpdateListener {
            val valor = it.animatedValue as Float
            temperaturaMostrada = valor
            tvTemperatura.text = getString(R.string.temperatura_valor, valor)
        }
        animacion.start()
        animacionTemperatura = animacion
    }

    /** Cambia un texto con un fundido; el tag guarda el texto de destino. */
    private fun cambiarTexto(vista: TextView, texto: String) {
        val destino = (vista.tag as? String) ?: vista.text.toString()
        if (destino == texto) return
        vista.tag = texto
        vista.animate().cancel()
        vista.animate()
            .alpha(0f)
            .setDuration(140L)
            .withEndAction {
                vista.text = texto
                vista.animate().alpha(1f).setDuration(260L).start()
            }
            .start()
    }

    /** Animación de entrada de las secciones. */
    private fun animarEntrada(contenedor: ViewGroup) {
        val distancia = 32f * resources.displayMetrics.density
        for (i in 0 until contenedor.childCount) {
            val seccion = contenedor.getChildAt(i)
            if (seccion.visibility != View.VISIBLE) continue
            seccion.alpha = 0f
            seccion.translationY = distancia
            seccion.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(70L * i)
                .setDuration(520L)
                .setInterpolator(suave)
                .start()
        }
    }
}
