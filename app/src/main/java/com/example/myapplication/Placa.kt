package com.example.myapplication

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Conexión HTTP con la placa Arduino UNO R4 WiFi, compartida por todas las pantallas.
 * Consulta /estado cada 2 segundos mientras haya una pantalla registrada,
 * guarda el historial y revisa la zona de confort para las alertas.
 */
object Placa {

    private const val TAG = "Placa"
    private const val INTERVALO_MS = 2000L

    // Hilo de red y hilo principal (la red no puede usarse en el hilo principal).
    private val hilo = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    private val placaDemo = PlacaDemo()                 // usado solo desde "hilo"

    /** Pantallas registradas; reciben true cuando llega una lectura nueva. */
    private val oyentes = mutableListOf<(Boolean) -> Unit>()
    private val lecturas = ArrayDeque<Float>()
    private var consultaEnCurso = false
    private var zonaAvisada: Zona? = null               // última zona notificada
    private var contexto: WeakReference<Context>? = null

    /** IP que se está consultando ("" = ninguna, IP_DEMO = modo demo). */
    var ip = ""
        private set

    /** Último estado recibido, o null si aún no hay datos o falló la conexión. */
    var estado: Estado? = null
        private set

    /** true si la última petición a la placa falló. */
    var hayError = false
        private set

    /** Órdenes enviadas que aún no responden. */
    var ordenesPendientes = 0
        private set

    /** Últimas temperaturas, de la más antigua a la más nueva. */
    val historial: List<Float>
        get() = lecturas.toList()

    /** Nivel (texto y color) del estado actual. */
    val nivel: NivelTemperatura
        get() = nivelDe(estado?.temperatura)

    /** Zona exacta del estado actual, o null si no hay lectura. */
    val zona: Zona?
        get() = estado?.temperatura?.let { zonaDe(it) }

    private val consulta = Runnable {
        if (ip.isNotBlank() && !consultaEnCurso) {
            consultaEnCurso = true
            pedir("/estado", esConsulta = true)
        }
    }

    /** Registra una pantalla (se llama en onStart). */
    fun registrar(contexto: Context, oyente: (Boolean) -> Unit) {
        this.contexto = WeakReference(contexto.applicationContext)
        if (oyente !in oyentes) oyentes.add(oyente)
        if (oyentes.size == 1) reiniciarConsulta()
    }

    /** Quita una pantalla (se llama en onStop). */
    fun desregistrar(oyente: (Boolean) -> Unit) {
        oyentes.remove(oyente)
        if (oyentes.isEmpty()) principal.removeCallbacks(consulta)
    }

    /** Cambia la IP consultada (o activa el modo demo) y reinicia los datos. */
    fun conectar(nuevaIp: String) {
        ip = nuevaIp
        estado = null
        hayError = false
        lecturas.clear()
        zonaAvisada = null
        contexto?.get()?.let { Alertas.quitar(it) }
        avisar(nuevaLectura = false)
        if (oyentes.isNotEmpty()) reiniciarConsulta()
    }

    /** Envía una orden a la placa, por ejemplo "/objetivo?valor=24". */
    fun enviarOrden(ruta: String) = pedir(ruta, esConsulta = false)

    /** Reinicia la consulta periódica de /estado. */
    private fun reiniciarConsulta() {
        principal.removeCallbacks(consulta)
        principal.post(consulta)
    }

    /** Envía una petición a la placa en el hilo de red. */
    private fun pedir(ruta: String, esConsulta: Boolean) {
        val ipPedida = ip
        if (ipPedida.isBlank()) {
            if (esConsulta) consultaEnCurso = false
            return
        }
        if (!esConsulta) ordenesPendientes++

        hilo.execute {
            val recibido = try {
                if (ipPedida == IP_DEMO) placaDemo.atender(ruta) else descargar(ipPedida, ruta)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo pedir $ruta a $ipPedida", e)
                null
            }
            principal.post { procesar(ipPedida, recibido, esConsulta) }
        }
    }

    /** Procesa la respuesta de la placa en el hilo principal. */
    private fun procesar(ipPedida: String, recibido: Estado?, esConsulta: Boolean) {
        if (esConsulta) consultaEnCurso = false else ordenesPendientes--

        // Descarta respuestas de una IP anterior.
        if (ipPedida == ip) {
            if (recibido != null) {
                estado = recibido
                hayError = false
                val temperatura = recibido.temperatura
                val nuevaLectura = esConsulta && temperatura != null
                if (temperatura != null && nuevaLectura) {
                    lecturas.addLast(temperatura.toFloat())
                    if (lecturas.size > MUESTRAS_HISTORIAL) lecturas.removeFirst()
                }
                revisarZona(temperatura)
                avisar(nuevaLectura)
            } else {
                // Sin conexión: se descarta el último estado.
                estado = null
                hayError = true
                avisar(nuevaLectura = false)
            }
        }

        if (esConsulta && oyentes.isNotEmpty()) {
            principal.removeCallbacks(consulta)
            principal.postDelayed(consulta, INTERVALO_MS)
        }
    }

    /** Envía o retira la alerta según la zona de confort. */
    private fun revisarZona(temperatura: Double?) {
        if (temperatura == null) return
        val anterior = zonaAvisada
        val nueva = zonaParaAlertas(temperatura, anterior)
        zonaAvisada = nueva
        if (nueva == anterior) return

        val ctx = contexto?.get() ?: return
        if (nueva == Zona.CONFORT) {
            Alertas.quitar(ctx)          // temperatura dentro de la zona de confort
        } else {
            Alertas.avisarFueraDeConfort(ctx, nueva, temperatura)
        }
    }

    /** Notifica a las pantallas registradas. */
    private fun avisar(nuevaLectura: Boolean) {
        // Copia de la lista: permite desregistrar durante el aviso.
        for (oyente in oyentes.toList()) oyente(nuevaLectura)
    }

    /** Petición HTTP GET a la placa y lectura del JSON de respuesta. */
    private fun descargar(ip: String, ruta: String): Estado {
        val conexion = URL("http://$ip$ruta").openConnection() as HttpURLConnection
        try {
            conexion.connectTimeout = 3000
            conexion.readTimeout = 3000
            val texto = conexion.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(texto)
            return Estado(
                temperatura = if (json.isNull("temperatura")) null else json.getDouble("temperatura"),
                humedad = if (json.isNull("humedad")) null else json.getDouble("humedad"),
                ventilador = json.getBoolean("ventilador"),
                auto = json.getBoolean("auto"),
                objetivo = json.getDouble("objetivo"),
                alerta = json.optString("alerta", "ninguna")
            )
        } finally {
            conexion.disconnect()
        }
    }
}
