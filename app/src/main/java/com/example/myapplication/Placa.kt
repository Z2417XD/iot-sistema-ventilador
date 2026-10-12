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
 * Conexión con la placa, compartida por todas las pantallas.
 *
 * Mientras haya al menos una pantalla visible registrada, consulta /estado
 * cada 2 segundos, guarda el historial y revisa la zona de confort para
 * mandar alertas. Así los datos siguen llegando aunque se cambie de pantalla
 * (antes, al abrir el historial la consulta se detenía).
 */
object Placa {

    private const val TAG = "Placa"
    private const val INTERVALO_MS = 2000L

    // La red no puede usarse en el hilo principal: las peticiones van a este
    // hilo y el resultado vuelve al hilo principal con "principal".
    private val hilo = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    private val placaDemo = PlacaDemo()                 // solo se usa desde "hilo"

    /** Pantallas interesadas. Reciben true cuando llegó una lectura nueva. */
    private val oyentes = mutableListOf<(Boolean) -> Unit>()
    private val lecturas = ArrayDeque<Float>()
    private var consultaEnCurso = false
    private var zonaAvisada: Zona? = null               // última zona que generó (o quitó) una alerta
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

    /** Llamar en onStart de cada pantalla. */
    fun registrar(contexto: Context, oyente: (Boolean) -> Unit) {
        this.contexto = WeakReference(contexto.applicationContext)
        if (oyente !in oyentes) oyentes.add(oyente)
        if (oyentes.size == 1) reiniciarConsulta()
    }

    /** Llamar en onStop de cada pantalla. */
    fun desregistrar(oyente: (Boolean) -> Unit) {
        oyentes.remove(oyente)
        if (oyentes.isEmpty()) principal.removeCallbacks(consulta)
    }

    /** Empieza a consultar otra placa (o el modo demo) desde cero. */
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

    private fun reiniciarConsulta() {
        principal.removeCallbacks(consulta)
        principal.post(consulta)
    }

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

    private fun procesar(ipPedida: String, recibido: Estado?, esConsulta: Boolean) {
        if (esConsulta) consultaEnCurso = false else ordenesPendientes--

        // Si mientras tanto cambió la IP, esta respuesta ya no sirve.
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
                // No se deja una lectura vieja como si fuera actual.
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

    /** Manda una alerta cuando la temperatura sale de la zona de confort. */
    private fun revisarZona(temperatura: Double?) {
        if (temperatura == null) return
        val anterior = zonaAvisada
        val nueva = zonaParaAlertas(temperatura, anterior)
        zonaAvisada = nueva
        if (nueva == anterior) return

        val ctx = contexto?.get() ?: return
        if (nueva == Zona.CONFORT) {
            Alertas.quitar(ctx)          // volvió a la normalidad: se retira la alerta
        } else {
            Alertas.avisarFueraDeConfort(ctx, nueva, temperatura)
        }
    }

    private fun avisar(nuevaLectura: Boolean) {
        // Se recorre una copia por si una pantalla se desregistra al recibir el aviso.
        for (oyente in oyentes.toList()) oyente(nuevaLectura)
    }

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
