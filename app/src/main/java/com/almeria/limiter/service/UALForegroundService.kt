package com.almeria.limiter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class UALForegroundService : Service() {

    private val CHANNEL_ID = "LimiterServiceChannel"

    override fun onCreate() {
        super.onCreate()
        createServiceNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Creamos la notificación obligatoria
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Compresor UAL Activo")
            .setContentText("Protegiendo tus oídos en segundo plano...")
            .setSmallIcon(android.R.drawable.ic_media_play) // Cambiar por un ícono propio luego
            .build()

        // Este método le dice a Android: "No me cierres, soy un servicio prioritario"
        startForeground(1, notification)

        // Aquí es donde llaman a Shizuku y configuran el DynamicsProcessing global (sesión 0)
        // ... tu lógica de inicialización del limitador ...

        return START_STICKY // Si el sistema llega a quedarse sin RAM, reiniciará el servicio automáticamente
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createServiceNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Canal de Servicio de Compresión UAL",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }
}