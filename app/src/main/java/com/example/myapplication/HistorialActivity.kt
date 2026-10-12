package com.example.myapplication

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

/**
 * Parámetros en tiempo real y gráfico de los últimos 5 minutos.
 * Los datos vienen de Placa, que sigue consultando la placa mientras esta
 * pantalla está abierta.
 */
class HistorialActivity : AppCompatActivity() {

    private lateinit var tvParametros: TextView
    private lateinit var grafico: HistorialView
    private lateinit var fondo: FondoAnimado

    private val oyente: (Boolean) -> Unit = { nuevaLectura -> pintar(nuevaLectura) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_historial)
        fondo = FondoAnimado(findViewById(R.id.raizHistorial))
        fondo.mostrar(Placa.nivel, animar = false)

        tvParametros = findViewById(R.id.tvParametros)
        grafico = findViewById(R.id.graficoHistorial)
        findViewById<Button>(R.id.btnVolver).setOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        Placa.registrar(this, oyente)
        pintar(nuevaLectura = false)
    }

    override fun onStop() {
        super.onStop()
        Placa.desregistrar(oyente)
    }

    override fun onDestroy() {
        super.onDestroy()
        fondo.detener()
    }

    private fun pintar(nuevaLectura: Boolean) {
        val estado = Placa.estado
        fondo.mostrar(Placa.nivel)

        if (estado == null) {
            tvParametros.setText(
                if (Placa.hayError) R.string.historial_sin_conexion else R.string.historial_sin_datos
            )
        } else {
            val temperatura = estado.temperatura
            val humedad = estado.humedad
            tvParametros.text = getString(
                R.string.historial_parametros,
                if (temperatura != null) getString(R.string.historial_grados, temperatura)
                else getString(R.string.sin_valor),
                if (humedad != null) getString(R.string.historial_humedad, humedad)
                else getString(R.string.sin_valor),
                getString(R.string.objetivo_valor, estado.objetivo),
                getString(
                    if (estado.ventilador) R.string.ventilador_encendido
                    else R.string.ventilador_apagado
                ),
                getString(if (estado.auto) R.string.activado else R.string.desactivado),
                getString(textoZona(Placa.zona))
            )
        }

        grafico.mostrar(Placa.historial, estado?.objetivo?.toFloat(), nuevaLectura)
    }

    private fun textoZona(zona: Zona?): Int = when (zona) {
        null -> R.string.sin_valor
        Zona.BAJO -> R.string.zona_bajo
        Zona.CONFORT -> R.string.zona_confort
        Zona.SOBRE -> R.string.zona_sobre
    }
}
