package com.almeria.limiter.shizuku

import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Visualizer
import android.media.AudioManager
import android.util.Log
import com.almeria.limiter.IUNALServiceCallback
import com.almeria.limiter.IUNALUserService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

class UNALUserService : IUNALUserService.Stub() {

    private lateinit var audioManager: AudioManager

    private val TAG = "UNALUserService"

    private fun calcularThresholdSeguro(): Float {
        // 1. Obtener el volumen actual de archivos multimedia (Stream Music)
        val volActual = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        // 2. Calcular la proporción del volumen (0.0 a 1.0)
        val ratioVolumen = volActual.toFloat() / volMax.toFloat()

        // 3. Regla matemática adaptativa:
        // A mayor volumen físico, menor debe ser el techo digital (Threshold) para proteger el oído.
        // Si el volumen está al máximo (ratio = 1.0), el techo cae a -24 dBFS.
        // Si el volumen está al mínimo (ratio = 0.0), el techo se relaja a -6 dBFS.
        val thresholdBase = -6f - (ratioVolumen * 18f)

        return thresholdBase
    }

    // Componentes del motor de audio nativo
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var visualizer: Visualizer? = null

    // Callback para notificar la telemetría al proceso de la UI
    private var callback: IUNALServiceCallback? = null

    // Planificador para tareas periódicas (Cálculo del dosímetro)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    // Parámetros de audio (Estados seguros para hilos mediante @Volatile)
    @Volatile private var currentThresholdDb = -12f
    @Volatile private var currentAttackMs = 5f
    @Volatile private var currentReleaseMs = 50f
    @Volatile private var currentMakeupGainDb = 0f
    @Volatile private var isAdaptiveGainEnabled = false
    @Volatile private var isDosimeterEnabled = false

    // Estado del Dosímetro y telemetría en tiempo real
    private var accumulatedDose = 0.0f
    private val maxDoseThreshold = 100.0f // Unidad arbitraria que representa el límite de exposición segura
    private var dosimetryThresholdAttenuation = 0f // Atenuación asintótica del umbral por protección
    
    @Volatile private var lastMeasuredRmsDb = -100f
    @Volatile private var smoothedAdaptiveGain = 0f
    @Volatile private var lastAppliedGain = 0f
    @Volatile private var lastGainUpdateTimeMs = 0L

    init {
        Log.d(TAG, "UNALUserService instanciado con privilegios: UID=${android.os.Process.myUid()}")
        startDosimetryLoop()
    }

    override fun startEngine() {
        Log.i(TAG, "Iniciando Dynamics Engine y Visualizer en la Sesión 0...")
        try {
            // Usamos reflexión para acceder a AppGlobals (API oculta) y obtener un Contexto válido en el proceso Shizuku
            val context = Class.forName("android.app.AppGlobals")
                .getMethod("getInitialApplication")
                .invoke(null) as Context
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            initDynamicsProcessing()
            initVisualizer()
        } catch (e: Exception) {
            Log.e(TAG, "Error al inicializar el motor de audio: ${e.message}", e)
            throw e
        }
    }

    override fun stopEngine() {
        Log.i(TAG, "Deteniendo Dynamics Engine y Visualizer...")
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

        // CONTROL DE BYPASS: Si el limitador está "encendido" en la UI, aplicamos y habilitamos.
        // Poden usar la variable 'dosimeterEnabled' o añadir una nueva variable 'isLimiterEnabled' al AIDL.
        // Como prueba rápida, si 'dosimeterEnabled' está en true, encendemos el chip; si no, lo apagamos:
        dynamicsProcessing?.let { dp ->
            dp.enabled = dosimeterEnabled // Desactiva por completo el procesamiento de hardware si es false
            if (dp.enabled) {
                applyDynamicsConfig()
            } else {
                Log.d(TAG, "FILTER BYPASS: El procesador de audio ha sido desactivado para comparación.")
            }
        }
    }

    override fun registerCallback(cb: IUNALServiceCallback?) {
        this.callback = cb
        Log.d(TAG, "Callback registrado para telemetría")
    }

    override fun exit() {
        Log.i(TAG, "Cerrando proceso de servicio de usuario UNAL...")
        releaseResources()
        scheduler.shutdownNow()
        System.exit(0)
    }

    override fun onFftUpdated(frequencies: FloatArray?) {
        // De momento lo dejamos vacío o con un log para que compile
        // Este método usualmente se usaría si la UI le quisiera mandar frecuencias al servicio,
        // pero como el flujo es al revés (el servicio le manda a la UI), si lo necesitan en el callback
        // lo cambiaremos luego. Por ahora, esto quita el error de compilación.
        Log.d(TAG, "onFftUpdated recibido desde la UI con tamaño: ${frequencies?.size}")
    }

    private fun initDynamicsProcessing() {
        val configBuilder = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION, // <-- Resolución temporal ultra-rápida
            2,
            false, 0, // preEQ desactivado
            false, 0, // MBC desactivado
            false, 0, // postEQ desactivado
            true      // Limiter activado
        )

        val config = configBuilder.build()

        // Asignamos a la sesión 0 (global del sistema) y prioridad 1000
        dynamicsProcessing = DynamicsProcessing(1000, 0, config)

        applyDynamicsConfig()
        dynamicsProcessing?.enabled = true
        Log.d(TAG, "DynamicsProcessing creado (Modo Tiempo Real Bruto) en la Sesión 0")
    }

    private fun applyDynamicsConfig() {
        val dp = dynamicsProcessing ?: return

        // 1. Calculamos el umbral adaptativo inteligente según el volumen físico actual del cel
        val umbralBaseInmutable = calcularThresholdSeguro()

        // 2. Le restamos la atenuación acumulada del dosímetro si el usuario lleva mucho tiempo expuesto
        val effectiveThreshold = umbralBaseInmutable - dosimetryThresholdAttenuation

        // 3. Construimos la etapa de limitación para canal estéreo con efecto Muro Estricto (Ratio 50.0f)
        val limiterConfig = DynamicsProcessing.Limiter(
            true,               // inUse
            true,               // enabled
            0,                  // linkGroup
            currentAttackMs,    // attackTime (ms)
            currentReleaseMs,   // releaseTime (ms)
            50.0f,              // Ratio ultra-alto: Ahora actúa como un Brickwall Limiter real
            effectiveThreshold, // Umbral adaptativo corregido (dB)
            currentMakeupGainDb // postGain (makeup gain base)
        )

        try {
            dp.setLimiterAllChannelsTo(limiterConfig)
            Log.d(TAG, "Configuración del Limitador aplicada con Ratio de Muro: LimiterThreshold=$effectiveThreshold dB")
        } catch (e: Exception) {
            Log.e(TAG, "Error al configurar los canales del limitador: ${e.message}")
        }
    }

    private fun initVisualizer() {
        try {
            visualizer = Visualizer(0).apply {
                // Modificado a rango máximo (ej. 1024) para dar suficiente resolución a la FFT
                captureSize = Visualizer.getCaptureSizeRange()[1]

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

                    override fun onFftDataCapture(
                        v: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (fft != null) {
                            processFftSamples(fft)
                        }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, true, true) // Habilitados Waveform y FFT (true, true)

                enabled = true
            }
            Log.d(TAG, "Visualizer iniciado exitosamente en la Sesión 0 (Waveform + FFT)")
        } catch (e: Exception) {
            Log.e(TAG, "Error al inicializar el Visualizer global: ${e.message}")
        }
    }

    private fun processAudioSamples(waveform: ByteArray) {
        if (waveform.isEmpty()) return

        // Cálculo del valor RMS real de la señal
        var sumSquares = 0.0
        for (i in waveform.indices) {
            val sample = ((waveform[i].toInt() and 0xFF) - 128) / 128f
            sumSquares += (sample * sample)
        }
        val rms = sqrt(sumSquares / waveform.size).toFloat()

        // Conversión a escala logarítmica dBFS (evitando log(0) con piso en -100dB)
        val rmsDb = if (rms > 1e-5f) 20f * log10(rms) else -100f
        
        // Guardar RMS real para la dosimetría en tiempo real
        lastMeasuredRmsDb = rmsDb

        // Notificar el RMS actual en tiempo real a la interfaz gráfica por AIDL
        try {
            callback?.onRmsUpdated(rmsDb)
        } catch (e: Exception) {
            // Error de canal Binder (la interfaz puede estar minimizada)
        }

        // Innovación 1: Adaptive Make-up Gain
        if (isAdaptiveGainEnabled) {
            applyAdaptiveGain(rmsDb)
        } else {
            // Si se desactiva la ganancia adaptativa, asegurarse de limpiar la atenuación
            if (smoothedAdaptiveGain != 0f) {
                smoothedAdaptiveGain = 0f
                resetToDefaultMakeupGain()
            }
        }
    }

    private fun processFftSamples(fft: ByteArray) {
        if (fft.isEmpty() || callback == null) return

        // Espacio preparado para el cálculo de magnitudes de frecuencia (Barras del Ecualizador).
        // Cuando implementen el Canvas en Compose, podrán expandir su AIDL para enviar estas ráfagas.
    }

    private fun applyAdaptiveGain(rmsDb: Float) {
        val dp = dynamicsProcessing ?: return

        val targetSilentThreshold = -30f
        val maxGainCompensation = 12f // Máximo 12dB de amplificación para evitar amplificar ruido blanco

        // Calcular ganancia adaptativa deseada
        val targetAdaptiveGain = if (rmsDb < targetSilentThreshold && rmsDb > -60f) {
            val ratio = (targetSilentThreshold - rmsDb) / (targetSilentThreshold - (-60f))
            ratio * maxGainCompensation
        } else {
            0f
        }

        // Suavizar los cambios de ganancia con un seguidor de envolvente (coeficiente alfa = 0.1)
        val alpha = 0.1f
        smoothedAdaptiveGain = (1f - alpha) * smoothedAdaptiveGain + alpha * targetAdaptiveGain

        // Throttling: Evitar reconfigurar el hardware de audio en cada frame (10ms)
        // Solo enviamos la actualización al chip DSP si han pasado más de 100ms
        // o si la ganancia suavizada difiere de la última ganancia aplicada por más de 0.5 dB.
        val currentTimeMs = System.currentTimeMillis()
        val timeDiffMs = currentTimeMs - lastGainUpdateTimeMs
        val gainDiffDb = Math.abs(smoothedAdaptiveGain - lastAppliedGain)

        if (timeDiffMs > 100 || gainDiffDb > 0.5f) {
            val finalMakeupGain = currentMakeupGainDb + smoothedAdaptiveGain
            try {
                for (channel in 0..1) {
                    val currentLimiter = dp.getLimiterByChannelIndex(channel)
                    currentLimiter.postGain = finalMakeupGain
                    dp.setLimiterByChannelIndex(channel, currentLimiter)
                }
                lastAppliedGain = smoothedAdaptiveGain
                lastGainUpdateTimeMs = currentTimeMs
            } catch (e: Exception) {
                // Silenciar excepciones del hardware en caliente
            }
        }
    }

    private fun resetToDefaultMakeupGain() {
        val dp = dynamicsProcessing ?: return
        try {
            for (channel in 0..1) {
                val currentLimiter = dp.getLimiterByChannelIndex(channel)
                currentLimiter.postGain = currentMakeupGainDb
                dp.setLimiterByChannelIndex(channel, currentLimiter)
            }
            lastAppliedGain = 0f
        } catch (e: Exception) {
            // Ignorar
        }
    }

    private fun startDosimetryLoop() {
        scheduler.scheduleWithFixedDelay({
            if (isDosimeterEnabled) {
                runDosimetryCalculation()
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun runDosimetryCalculation() {
        if (dynamicsProcessing == null) return

        // Innovación 3: Dosimetría basada en RMS real medido
        // Usamos lastMeasuredRmsDb (que reporta -100dB si la música está pausada o en silencio).
        val rmsLevel = lastMeasuredRmsDb

        // Estimación de dosis de ruido (Umbral seguro estimado digital en -20 dBFS)
        if (rmsLevel > -20f) {
            val excessDb = rmsLevel - (-20f)
            // Incremento exponencial de dosis: a mayor volumen por encima del límite, mayor daño acumulado
            accumulatedDose += (1.0f + (excessDb * 0.2f))
        } else {
            // Tasa de recuperación: si está en silencio o por debajo de -20dBFS, el oído descansa
            accumulatedDose = (accumulatedDose - 0.5f).coerceAtLeast(0.0f)
        }

        // Si la dosis excede el umbral de fatiga acústica, reducimos asintóticamente el Threshold
        if (accumulatedDose > maxDoseThreshold) {
            val overloadRatio = (accumulatedDose - maxDoseThreshold) / maxDoseThreshold
            // La atenuación crece asintóticamente hacia un máximo de 6dB de reducción
            dosimetryThresholdAttenuation = (6.0f * (1.0f - Math.exp(-overloadRatio.toDouble()).toFloat()))
            applyDynamicsConfig()
        } else {
            if (dosimetryThresholdAttenuation > 0f) {
                // Recuperar el umbral original poco a poco
                dosimetryThresholdAttenuation = (dosimetryThresholdAttenuation - 0.1f).coerceAtLeast(0f)
                applyDynamicsConfig()
            }
        }

        val dosePercentage = (accumulatedDose / maxDoseThreshold * 100f).coerceAtMost(100f)
        try {
            callback?.onDoseUpdated(dosePercentage)
        } catch (e: Exception) {
            // Ignorar fallos de callback en segundo plano
        }
    }

    private fun releaseResources() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
            visualizer = null
            Log.d(TAG, "Visualizer liberado con éxito")
        } catch (e: Exception) {
            Log.e(TAG, "Error al liberar Visualizer: ${e.message}")
        }

        try {
            dynamicsProcessing?.enabled = false
            dynamicsProcessing?.release()
            dynamicsProcessing = null
            Log.d(TAG, "DynamicsProcessing liberado con éxito")
        } catch (e: Exception) {
            Log.e(TAG, "Error al liberar DynamicsProcessing: ${e.message}")
        }
    }
}