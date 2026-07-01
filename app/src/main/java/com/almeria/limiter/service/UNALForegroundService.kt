package com.almeria.limiter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.almeria.limiter.IUNALServiceCallback
import com.almeria.limiter.IUNALUserService
import com.almeria.limiter.model.AudioProfileType
import com.almeria.limiter.model.LimiterParameters
import com.almeria.limiter.shizuku.ShizukuManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class UNALForegroundService : Service() {

    private val TAG = "UNALForegroundService"
    private val CHANNEL_ID = "LimiterServiceChannel"
    private val NOTIFICATION_ID = 1

    // Binder client communications
    private val binder = LocalBinder()
    private var telemetryListener: TelemetryListener? = null
    
    // Coroutine scope for reactive flow monitoring (active package/profiles)
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Custom user configurations (General profile)
    private var userThreshold = -12f
    private var userAttack = 5f
    private var userRelease = 50f
    private var userMakeupGain = 0f
    private var isAdaptiveGainEnabled = false
    private var isDosimeterEnabled = false
    private var userLowEq = 0f
    private var userMidEq = 0f
    private var userHighEq = 0f

    interface TelemetryListener {
        fun onRmsUpdated(rmsDb: Float)
        fun onGainReductionUpdated(reductionDb: Float)
        fun onDoseUpdated(dosePercentage: Float)
        fun onProfileSwitched(profile: AudioProfileType)
    }

    inner class LocalBinder : Binder() {
        fun getService(): UNALForegroundService = this@UNALForegroundService
    }

    private val shizukuCallback = object : IUNALServiceCallback.Stub() {
        override fun onRmsUpdated(rmsDb: Float) {
            telemetryListener?.onRmsUpdated(rmsDb)
        }

        override fun onGainReductionUpdated(reductionDb: Float) {
            telemetryListener?.onGainReductionUpdated(reductionDb)
        }

        override fun onDoseUpdated(dosePercentage: Float) {
            telemetryListener?.onDoseUpdated(dosePercentage)
        }
    }

    private val shizukuConnectionCallback = object : ShizukuManager.ConnectionCallback {
        override fun onConnected(service: IUNALUserService) {
            Log.i(TAG, "Foreground Service enlazado exitosamente a Shizuku UNALUserService")
            try {
                service.registerCallback(shizukuCallback)
                service.startEngine()
                applyCurrentParameters() // Enviar configuraciones iniciales
            } catch (e: Exception) {
                Log.e(TAG, "Error al arrancar el motor Shizuku: ${e.message}")
            }
        }

        override fun onDisconnected() {
            Log.w(TAG, "Desconectado del servicio Shizuku")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Foreground Service onCreate")
        createServiceNotificationChannel()
        
        // Conectar a Shizuku bajo proceso elevado
        connectToShizukuEngine()
        
        // Innovación 2: Escuchar reactivamente los cambios de aplicación activa (perfiles)
        monitorAudioProfiles()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Compresor UNAL Activo")
            .setContentText("Protegiendo tus oídos en tiempo real...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Foreground Service onDestroy - Liberando recursos")
        serviceScope.cancel()
        ShizukuManager.unbindService()
    }

    fun setTelemetryListener(listener: TelemetryListener?) {
        this.telemetryListener = listener
        // Notificar perfil inicial al conectar la UI
        listener?.onProfileSwitched(ContextManager.activeProfile.value)
    }

    /**
     * Actualiza las configuraciones personalizables de la UI (Perfil GENERAL).
     */
    fun updateCustomParameters(
        threshold: Float,
        attack: Float,
        release: Float,
        makeupGain: Float,
        adaptiveGain: Boolean,
        dosimeterEnabled: Boolean,
        lowEq: Float,
        midEq: Float,
        highEq: Float
    ) {
        userThreshold = threshold
        userAttack = attack
        userRelease = release
        userMakeupGain = makeupGain
        isAdaptiveGainEnabled = adaptiveGain
        isDosimeterEnabled = dosimeterEnabled
        userLowEq = lowEq
        userMidEq = midEq
        userHighEq = highEq

        if (ContextManager.activeProfile.value == AudioProfileType.GENERAL) {
            applyCurrentParameters()
        }
    }

    private fun connectToShizukuEngine() {
        if (ShizukuManager.isShizukuAvailable() && ShizukuManager.hasPermission()) {
            ShizukuManager.bindService(shizukuConnectionCallback)
        } else {
            Log.e(TAG, "Shizuku no disponible o sin permisos en el servicio de primer plano")
        }
    }

    private fun monitorAudioProfiles() {
        serviceScope.launch {
            ContextManager.activeProfile.collectLatest { profileType ->
                Log.i(TAG, "Detección de Contexto: Cambiando a perfil $profileType")
                
                // 1. Aplicar parámetros del perfil
                applyCurrentParameters()
                
                // 2. Notificar cambio al listener de UI
                telemetryListener?.onProfileSwitched(profileType)
                
                // 3. Modificar texto de la notificación para retroalimentación al usuario
                updateNotificationForProfile(profileType)
            }
        }
    }

    private fun applyCurrentParameters() {
        val service = ShizukuManager.getService() ?: return
        val profile = ContextManager.activeProfile.value

        // Seleccionar parámetros según el contexto
        val params = if (profile == AudioProfileType.GENERAL) {
            LimiterParameters(userThreshold, userAttack, userRelease, userMakeupGain)
        } else {
            LimiterParameters.getParametersFor(profile)
        }

        val lowEq = if (profile == AudioProfileType.GENERAL) userLowEq else 0f
        val midEq = if (profile == AudioProfileType.GENERAL) userMidEq else 0f
        val highEq = if (profile == AudioProfileType.GENERAL) userHighEq else 0f

        try {
            service.updateParameters(
                params.thresholdDb,
                params.attackMs,
                params.releaseMs,
                params.makeupGainDb,
                isAdaptiveGainEnabled,
                isDosimeterEnabled,
                lowEq,
                midEq,
                highEq
            )
            Log.d(TAG, "Parámetros aplicados al motor: Profile=$profile, Thr=${params.thresholdDb}dB, Att=${params.attackMs}ms, EQ: Low=$lowEq, Mid=$midEq, High=$highEq")
        } catch (e: Exception) {
            Log.e(TAG, "Error al enviar parámetros al motor Shizuku: ${e.message}")
        }
    }

    private fun updateNotificationForProfile(profile: AudioProfileType) {
        val profileName = when (profile) {
            AudioProfileType.MUSIC -> "Perfil Música (Spotify)"
            AudioProfileType.DIALOGUE -> "Perfil Diálogos (YouTube/TikTok)"
            AudioProfileType.GENERAL -> "Perfil General (Personalizado)"
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Compresor UNAL Activo")
            .setContentText("Modo actual: $profileName")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createServiceNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Canal de Servicio de Compresión UNAL",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }
}