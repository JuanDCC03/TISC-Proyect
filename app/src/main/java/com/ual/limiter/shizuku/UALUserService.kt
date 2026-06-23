package com.ual.limiter.shizuku

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Visualizer
import android.util.Log
import com.ual.limiter.IUALServiceCallback
import com.ual.limiter.IUALUserService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

class UALUserService : IUALUserService.Stub() {

    private val TAG = "UALUserService"
    
    // Audio engine components
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var visualizer: Visualizer? = null
    
    // Callback to notify the app process of telemetry
    private var callback: IUALServiceCallback? = null
    
    // Executor for periodic tasks (e.g., Dosimetry calculation)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    
    // Audio parameters (thread-safe state)
    @Volatile private var currentThresholdDb = -12f
    @Volatile private var currentAttackMs = 5f
    @Volatile private var currentReleaseMs = 50f
    @Volatile private var currentMakeupGainDb = 0f
    @Volatile private var isAdaptiveGainEnabled = false
    @Volatile private var isDosimeterEnabled = false
    
    // Dosimetry state
    private var accumulatedDose = 0.0f
    private val maxDoseThreshold = 100.0f // Arbitrary unit representing safe exposure limit
    private var dosimetryThresholdAttenuation = 0f // Asymptotic reduction of threshold

    init {
        Log.d(TAG, "UALUserService instanciado con privilegios: UID=${android.os.Process.myUid()}")
        startDosimetryLoop()
    }

    override fun startEngine() {
        Log.i(TAG, "Iniciando Dynamics Engine en la Sesión 0...")
        try {
            initDynamicsProcessing()
            initVisualizer()
        } catch (e: Exception) {
            Log.e(TAG, "Error al inicializar el Dynamics Engine: ${e.message}", e)
            throw e
        }
    }

    override fun stopEngine() {
        Log.i(TAG, "Deteniendo Dynamics Engine...")
        releaseResources()
    }

    override fun updateParameters(
        threshold: Float,
        attack: Float,
        release: Float,
        makeupGain: Float,
        adaptiveGain: Boolean,
        dosimeterEnabled: Boolean
    ) {
        currentThresholdDb = threshold
        currentAttackMs = attack
        currentReleaseMs = release
        currentMakeupGainDb = makeupGain
        isAdaptiveGainEnabled = adaptiveGain
        isDosimeterEnabled = dosimeterEnabled

        Log.d(TAG, "Parámetros actualizados: Thr=$threshold, Att=$attack, Rel=$release, MakeUp=$makeupGain, Adaptive=$adaptiveGain, Dosimeter=$dosimeterEnabled")
        applyDynamicsConfig()
    }

    override fun registerCallback(cb: IUALServiceCallback?) {
        this.callback = cb
        Log.d(TAG, "Callback registrado para telemetría")
    }

    override fun exit() {
        Log.i(TAG, "Cerrando proceso de servicio de usuario UAL...")
        releaseResources()
        scheduler.shutdownNow()
        System.exit(0)
    }

    private fun initDynamicsProcessing() {
        // Configuramos DynamicsProcessing con 2 canales (Stereo)
        // Variante enfocada en resolución temporal (favor time resolution) para limitar transitorios rápidos
        val configBuilder = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,
            false, 0, // preEQ desactivado
            false, 0, // MBC desactivado (procesaremos banda completa con el Limiter)
            false, 0, // postEQ desactivado
            true      // Limiter activado
        )

        val config = configBuilder.build()
        
        // Asignamos a la sesión 0 (global) y prioridad 1000
        dynamicsProcessing = DynamicsProcessing(1000, 0, config)
        
        applyDynamicsConfig()
        dynamicsProcessing?.enabled = true
        Log.d(TAG, "DynamicsProcessing creado y habilitado en la Sesión 0")
    }

    private fun applyDynamicsConfig() {
        val dp = dynamicsProcessing ?: return
        
        // Calculamos el umbral efectivo aplicando la atenuación protectora del dosímetro
        val effectiveThreshold = currentThresholdDb - dosimetryThresholdAttenuation

        // Construimos la etapa de limitación para canal estéreo (canal 0 y 1)
        val limiterConfig = DynamicsProcessing.Limiter(
            true,               // inUse
            true,               // enabled
            0,                  // linkGroup
            currentAttackMs,    // attackTime (ms)
            currentReleaseMs,   // releaseTime (ms)
            10.0f,              // ratio (relación de compresión alta para actuar como limitador)
            effectiveThreshold, // threshold (dB)
            currentMakeupGainDb // postGain (makeup gain base)
        )

        try {
            dp.setLimiterAllChannelsTo(limiterConfig)
            Log.d(TAG, "Configuración del Limitador aplicada: LimiterThreshold=$effectiveThreshold dB")
        } catch (e: Exception) {
            Log.e(TAG, "Error al configurar los canales del limitador: ${e.message}")
        }
    }

    private fun initVisualizer() {
        try {
            visualizer = Visualizer(0).apply {
                // Captura el tamaño mínimo para reducir latencia
                captureSize = Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (waveform != null) {
                            processAudioSamples(waveform)
                        }
                    }

                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {}
                }, Visualizer.getMaxCaptureRate() / 2, true, false)
                
                enabled = true
            }
            Log.d(TAG, "Visualizer iniciado en la Sesión 0")
        } catch (e: Exception) {
            Log.e(TAG, "Error al inicializar Visualizer: ${e.message}")
        }
    }

    private fun processAudioSamples(waveform: ByteArray) {
        if (waveform.isEmpty()) return

        // 1. Cálculo del valor RMS de la señal actual
        var sumSquares = 0.0
        for (i in waveform.indices) {
            // Convertir byte con signo (-128 a 127) a escala flotante normalizada (-1.0 a 1.0)
            val sample = waveform[i].toFloat() / 128f
            sumSquares += (sample * sample)
        }
        val rms = sqrt(sumSquares / waveform.size).toFloat()
        
        // Conversión a escala logarítmica dBFS (evitando log(0))
        val rmsDb = if (rms > 1e-5f) 20f * log10(rms) else -100f

        // Notificar RMS actual a través de la interfaz AIDL
        try {
            callback?.onRmsUpdated(rmsDb)
        } catch (e: Exception) {
            // Callback roto, el proceso principal pudo haberse pausado
        }

        // Innovación 1: Adaptive Make-up Gain
        if (isAdaptiveGainEnabled) {
            applyAdaptiveGain(rmsDb)
        }
    }

    private fun applyAdaptiveGain(rmsDb: Float) {
        val dp = dynamicsProcessing ?: return
        
        // Si el volumen está por debajo de un umbral cómodo (ej. -30 dB) y el limitador no está atenuando en exceso,
        // aumentamos dinámicamente la ganancia post-procesamiento.
        val targetSilentThreshold = -30f
        val maxGainCompensation = 12f // Máximo 12dB de boost para evitar ruido blanco

        val adaptiveGain = if (rmsDb < targetSilentThreshold && rmsDb > -60f) {
            // Elevación inversamente proporcional: a menor volumen, mayor ganancia
            val ratio = (targetSilentThreshold - rmsDb) / (targetSilentThreshold - (-60f))
            ratio * maxGainCompensation
        } else {
            0f
        }

        // Modificamos el post-gain del limitador sumando la ganancia adaptativa a la base
        val finalMakeupGain = currentMakeupGainDb + adaptiveGain

        try {
            // Actualizamos la ganancia de salida
            for (channel in 0..1) {
                val currentLimiter = dp.getLimiterByChannelIndex(channel)
                currentLimiter.postGain = finalMakeupGain
                dp.setLimiterByChannelIndex(channel, currentLimiter)
            }
        } catch (e: Exception) {
            // Silenciar excepciones por actualización rápida
        }
    }

    private fun startDosimetryLoop() {
        // Monitoreo periódico del dosímetro cada 1 segundo
        scheduler.scheduleWithFixedDelay({
            if (isDosimeterEnabled) {
                runDosimetryCalculation()
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun runDosimetryCalculation() {
        if (dynamicsProcessing == null) return
        
        // Innovación 3: Dosímetro de Fatiga Auditiva
        // Estimamos la dosis acumulada usando un modelo simplificado basado en el postGain y el threshold efectivo.
        // Si hay una salida de volumen alta y sostenida en el tiempo, incrementamos la dosis.
        val currentEffectiveOutputDb = currentThresholdDb + currentMakeupGainDb
        
        if (currentEffectiveOutputDb > -15f) {
            // A mayor nivel por encima de -15dB, el incremento de la dosis es exponencial (Leyes de dosis de ruido OSHA/NIOSH)
            val excessDb = currentEffectiveOutputDb - (-15f)
            accumulatedDose += (1.0f + (excessDb * 0.2f))
        } else {
            // Tasa de recuperación asintótica (reducción de la fatiga en pasajes silenciosos o inactividad)
            accumulatedDose = (accumulatedDose - 0.5f).coerceAtLeast(0.0f)
        }

        // Si excedemos el umbral de fatiga acústica, reducimos el techo máximo (Threshold)
        if (accumulatedDose > maxDoseThreshold) {
            val overloadRatio = (accumulatedDose - maxDoseThreshold) / maxDoseThreshold
            // La atenuación crece asintóticamente hacia un máximo de 6dB de reducción protectora
            dosimetryThresholdAttenuation = (6.0f * (1.0f - Math.exp(-overloadRatio.toDouble()).toFloat()))
            applyDynamicsConfig()
        } else {
            if (dosimetryThresholdAttenuation > 0f) {
                dosimetryThresholdAttenuation = (dosimetryThresholdAttenuation - 0.1f).coerceAtLeast(0f)
                applyDynamicsConfig()
            }
        }

        // Notificar dosis estimada (porcentaje de fatiga)
        val dosePercentage = (accumulatedDose / maxDoseThreshold * 100f).coerceAtMost(100f)
        try {
            callback?.onDoseUpdated(dosePercentage)
        } catch (e: Exception) {
            // Ignorar fallos de callback
        }
    }

    private fun releaseResources() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
            visualizer = null
            Log.d(TAG, "Visualizer liberado")
        } catch (e: Exception) {
            Log.e(TAG, "Error al liberar Visualizer: ${e.message}")
        }

        try {
            dynamicsProcessing?.enabled = false
            dynamicsProcessing?.release()
            dynamicsProcessing = null
            Log.d(TAG, "DynamicsProcessing liberado")
        } catch (e: Exception) {
            Log.e(TAG, "Error al liberar DynamicsProcessing: ${e.message}")
        }
    }
}
