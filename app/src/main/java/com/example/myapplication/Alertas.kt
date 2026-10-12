package com.example.myapplication

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Notificaciones de la zona de confort. */
object Alertas {

    private const val TAG = "Alertas"
    private const val CANAL = "alertas_temperatura"
    private const val ID_ALERTA = 1

    fun crearCanal(contexto: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL,
                contexto.getString(R.string.canal_alertas),
                NotificationManager.IMPORTANCE_HIGH
            )
            contexto.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(canal)
        }
    }

    /** En Android 13+ el usuario tiene que aceptar el permiso; antes no existe. */
    fun tienePermiso(contexto: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun avisarFueraDeConfort(contexto: Context, zona: Zona, temperatura: Double) {
        if (!tienePermiso(contexto)) return

        val titulo = contexto.getString(
            if (zona == Zona.SOBRE) R.string.alerta_sobre_titulo else R.string.alerta_bajo_titulo
        )
        val texto = contexto.getString(R.string.alerta_texto, temperatura, CONFORT_MIN, CONFORT_MAX)

        // Tocar la notificación abre la app (sin crear una pantalla repetida).
        val abrirApp = PendingIntent.getActivity(
            contexto,
            0,
            Intent(contexto, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notificacion = NotificationCompat.Builder(contexto, CANAL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(abrirApp)
            .setAutoCancel(true)
            .build()

        // El permiso se revisó arriba, pero el usuario puede quitarlo en cualquier
        // momento desde Ajustes; en ese caso Android lanza SecurityException.
        try {
            NotificationManagerCompat.from(contexto).notify(ID_ALERTA, notificacion)
        } catch (e: SecurityException) {
            Log.w(TAG, "Sin permiso para mostrar notificaciones", e)
        }
    }

    /** Retira la alerta cuando la temperatura vuelve a la zona de confort. */
    fun quitar(contexto: Context) {
        NotificationManagerCompat.from(contexto).cancel(ID_ALERTA)
    }
}
