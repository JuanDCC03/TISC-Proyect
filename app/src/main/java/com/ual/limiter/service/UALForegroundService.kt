package com.ual.limiter.service

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
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Compresor UAL Activo")
            .setContentText("Funcionamiento en segundo plano...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(1, notification)

        // Llamar a shizuku y configurar DynamicsProcessing global (sesión 0)
        // Lógica de inicialización del limitador

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Canal de Servicio de Compresión UAL",
                NotificationManager.IMPORTANCE_LOW 

            val manager = getSystemService(NotificationManager::class.kt)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}