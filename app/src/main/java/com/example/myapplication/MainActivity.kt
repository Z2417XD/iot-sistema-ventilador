package com.example.myapplication

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    // Vistas de activity_main.xml
    private lateinit var etIp: EditText
    private lateinit var btnConectar: Button
    private lateinit var btnDemo: Button
    private lateinit var tvConexion: TextView
    private lateinit var tvError: TextView
    private lateinit var termometro: TermometroView
    private lateinit var tvTemperatura: TextView
    private lateinit var tvEstado: TextView
    private lateinit var tvDetalle: TextView
    private lateinit var tvHumedad: TextView
    private lateinit var btnBajar: Button
    private lateinit var tvObjetivo: TextView
    private lateinit var btnSubir: Button
    private lateinit var slObjetivo: Slider
    private lateinit var swAuto: MaterialSwitch
    private lateinit var swVentilador: MaterialSwitch
    private lateinit var tvVentiladorEstado: TextView
    private lateinit var ventiladorIcono: VentiladorView
    private lateinit var btnVerHistorial: Button

    private lateinit var prefs: SharedPreferences
    private lateinit var fondo: FondoAnimado

    private var objetivoActual: Double? = null  // objetivo que se ve en pantalla
    private var actualizandoPantalla = false    // evita que los switches reenvíen órdenes
    private var arrastrando = false             // el usuario está moviendo el slider

    // --- Animaciones ---
    private val suave = PathInterpolator(0.2f, 0f, 0f, 1f)   // parte rápido y frena suave
    private var temperaturaMostrada: Float? = null
    private var animacionTemperatura: ValueAnimator? = null

    // Android 13+ exige pedir el permiso de notificaciones mientras la app corre.
    private val pedirPermiso =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Placa avisa aquí cada vez que cambia algo (lectura, error o conexión nueva). */
    private val oyente: (Boolean) -> Unit = { pintar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // El fondo siempre es oscuro, así que los íconos de las barras van en blanco.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_main)
        fondo = FondoAnimado(findViewById(R.id.main))

        etIp = findViewById(R.id.etIp)
        btnConectar = findViewById(R.id.btnConectar)
        btnDemo = findViewById(R.id.btnDemo)
        tvConexion = findViewById(R.id.tvConexion)
        tvError = findViewById(R.id.tvError)
        termometro = findViewById(R.id.termometro)
        tvTemperatura = findViewById(R.id.tvTemperatura)
        tvEstado = findViewById(R.id.tvEstado)
        tvDetalle = findViewById(R.id.tvDetalle)
        tvHumedad = findViewById(R.id.tvHumedad)
        btnBajar = findViewById(R.id.btnBajar)
        tvObjetivo = findViewById(R.id.tvObjetivo)
        btnSubir = findViewById(R.id.btnSubir)
        slObjetivo = findViewById(R.id.slObjetivo)
        swAuto = findViewById(R.id.swAuto)
        swVentilador = findViewById(R.id.swVentilador)
        tvVentiladorEstado = findViewById(R.id.tvVentiladorEstado)
        ventiladorIcono = findViewById(R.id.ventiladorIcono)
        btnVerHistorial = findViewById(R.id.btnVerHistorial)

        // La IP guardada se usa al abrir la app; el modo demo no se guarda.
        prefs = getSharedPreferences("clima", MODE_PRIVATE)
        val ipGuardada = prefs.getString("ip", "") ?: ""
        etIp.setText(if (Placa.ip.isNotBlank() && Placa.ip != IP_DEMO) Placa.ip else ipGuardada)
        if (Placa.ip.isBlank() && ipGuardada.isNotBlank()) Placa.conectar(ipGuardada)

        // --- Conexión ---
        btnConectar.setOnClickListener {
            val ip = etIp.text.toString().trim()
            prefs.edit { putString("ip", ip) }
            Placa.conectar(ip)
        }
        btnDemo.setOnClickListener { Placa.conectar(IP_DEMO) }

        // --- Subir y bajar la temperatura objetivo ---
        // El modo automático usa la temperatura objetivo para decidir cuándo
        // prender el ventilador. Por eso, cambiar el objetivo no lo desactiva.
        btnBajar.setOnClickListener { objetivoActual?.let { cambiarObjetivo(it - 1) } }
        btnSubir.setOnClickListener { objetivoActual?.let { cambiarObjetivo(it + 1) } }
        slObjetivo.setLabelFormatter { valor -> getString(R.string.objetivo_valor, valor) }
        slObjetivo.addOnChangeListener { _, valor, delUsuario ->
            // Mientras se arrastra solo cambia el número; la orden se envía al soltar.
            if (delUsuario) tvObjetivo.text = getString(R.string.objetivo_valor, valor)
        }
        slObjetivo.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                arrastrando = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                arrastrando = false
                cambiarObjetivo(slider.value.toDouble())
            }
        })

        // --- Ventilador ---
        // Encender o apagar a mano desactiva el modo automático (lo hace la placa).
        swAuto.setOnCheckedChangeListener { _, activo ->
            if (!actualizandoPantalla) Placa.enviarOrden("/auto?on=${if (activo) 1 else 0}")
        }
        swVentilador.setOnCheckedChangeListener { _, activo ->
            if (!actualizandoPantalla) Placa.enviarOrden("/ventilador?on=${if (activo) 1 else 0}")
        }

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

    // Mientras esta pantalla está visible recibe los datos de la placa.
    override fun onStart() {
        super.onStart()
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
        fondo.detener()
    }

    /** El usuario cambió el objetivo: la pantalla responde al tiro y luego se avisa a la placa. */
    private fun cambiarObjetivo(nuevo: Double) {
        val valor = nuevo.coerceIn(OBJETIVO_MIN, OBJETIVO_MAX)
        mostrarObjetivo(valor, conRebote = true)
        termometro.mostrar(Placa.estado?.temperatura?.toFloat(), valor.toFloat())
        habilitarControles(true)
        Placa.enviarOrden("/objetivo?valor=$valor")
    }

    // -----------------------------------------------------------------------
    // Pantalla
    // -----------------------------------------------------------------------

    /** Dibuja la pantalla con lo que tiene Placa en este momento. */
    private fun pintar() {
        tvConexion.text = textoConexion()

        val estado = Placa.estado
        if (estado == null) {
            mostrarSinDatos()
            if (Placa.hayError) {
                tvDetalle.setText(R.string.detalle_sin_conexion)
                tvError.text = getString(R.string.error_conexion, Placa.ip)
                tvError.visibility = View.VISIBLE
            } else {
                tvError.visibility = View.GONE
            }
            return
        }
        tvError.visibility = View.GONE

        val temperatura = estado.temperatura
        val nivel = Placa.nivel

        // Mientras haya una orden sin responder no se pisan los controles con
        // datos viejos: así no "saltan" justo después de tocarlos.
        val controlesAlDia = Placa.ordenesPendientes == 0
        if (controlesAlDia && !arrastrando) mostrarObjetivo(estado.objetivo, conRebote = false)
        val objetivo = (objetivoActual ?: estado.objetivo).toFloat()

        // Estado de la habitación: el fondo dice de un vistazo si está fría o caliente.
        fondo.mostrar(nivel)
        mostrarTemperatura(temperatura?.toFloat())
        cambiarTexto(
            tvEstado,
            getString(if (temperatura != null) nivel.titulo else R.string.sin_lectura)
        )
        tvDetalle.text = detalle(temperatura)
        tvHumedad.text = estado.humedad?.let { getString(R.string.humedad_valor, it) }
        termometro.mostrar(temperatura?.toFloat(), objetivo)

        // Ventilador
        if (controlesAlDia) {
            // Cambiar isChecked dispara el listener; la bandera evita reenviar la orden.
            actualizandoPantalla = true
            swAuto.isChecked = estado.auto
            swVentilador.isChecked = estado.ventilador
            actualizandoPantalla = false
        }
        tvVentiladorEstado.setText(
            if (estado.ventilador) R.string.ventilador_encendido else R.string.ventilador_apagado
        )
        ventiladorIcono.encender(estado.ventilador)

        habilitarControles(true)
    }

    private fun textoConexion(): String = when {
        Placa.ip.isBlank() -> getString(R.string.conexion_escribe_ip)
        Placa.ip == IP_DEMO -> getString(R.string.conexion_demo)
        Placa.hayError -> getString(R.string.conexion_sin)
        Placa.estado == null -> getString(R.string.conexion_conectando, Placa.ip)
        else -> getString(R.string.conexion_conectado, Placa.ip)
    }

    /** Frase bajo el estado: dónde está la temperatura respecto a la zona de confort. */
    private fun detalle(temperatura: Double?): String {
        if (temperatura == null) return getString(R.string.detalle_sensor)
        return when (zonaDe(temperatura)) {
            Zona.SOBRE -> getString(R.string.detalle_sobre, temperatura - CONFORT_MAX)
            Zona.BAJO -> getString(R.string.detalle_bajo, CONFORT_MIN - temperatura)
            Zona.CONFORT -> getString(R.string.detalle_confort, CONFORT_MIN, CONFORT_MAX)
        }
    }

    private fun mostrarSinDatos() {
        objetivoActual = null
        fondo.mostrar(NivelTemperatura.SIN_DATOS)
        mostrarTemperatura(null)
        cambiarTexto(tvEstado, getString(NivelTemperatura.SIN_DATOS.titulo))
        tvDetalle.setText(R.string.detalle_conectate)
        tvHumedad.text = null
        tvObjetivo.setText(R.string.sin_valor)
        tvVentiladorEstado.setText(R.string.sin_datos)
        ventiladorIcono.encender(false)
        termometro.mostrar(null, null)
        habilitarControles(false)
    }

    private fun mostrarObjetivo(valor: Double, conRebote: Boolean) {
        objetivoActual = valor
        val texto = getString(R.string.objetivo_valor, valor)
        if (tvObjetivo.text.toString() != texto) {
            tvObjetivo.text = texto
            if (conRebote) {
                // El número "salta" un poco para confirmar el toque.
                tvObjetivo.animate().cancel()
                tvObjetivo.scaleX = 1.2f
                tvObjetivo.scaleY = 1.2f
                tvObjetivo.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(320L)
                    .setInterpolator(OvershootInterpolator())
                    .start()
            }
        }
        val enSlider = valor.roundToInt()
            .coerceIn(OBJETIVO_MIN.toInt(), OBJETIVO_MAX.toInt())
            .toFloat()
        if (slObjetivo.value != enSlider) slObjetivo.value = enSlider
    }

    private fun habilitarControles(conectado: Boolean) {
        val objetivo = objetivoActual
        btnBajar.isEnabled = conectado && objetivo != null && objetivo > OBJETIVO_MIN
        btnSubir.isEnabled = conectado && objetivo != null && objetivo < OBJETIVO_MAX
        slObjetivo.isEnabled = conectado
        swAuto.isEnabled = conectado
        swVentilador.isEnabled = conectado
    }

    // -----------------------------------------------------------------------
    // Animaciones
    // -----------------------------------------------------------------------

    /** El número grande "cuenta" hasta el valor nuevo. */
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

    /** Cambia un texto con un fundido corto en vez de reemplazarlo de golpe. */
    private fun cambiarTexto(vista: TextView, texto: String) {
        if (vista.text.toString() == texto) return
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

    /** Las secciones suben y aparecen una después de la otra. */
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
