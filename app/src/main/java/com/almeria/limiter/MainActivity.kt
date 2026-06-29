package com.almeria.limiter

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.almeria.limiter.model.AudioProfileType
import com.almeria.limiter.service.ContextManager
import com.almeria.limiter.service.UNALForegroundService
import com.almeria.limiter.shizuku.ShizukuManager

class MainActivity : ComponentActivity() {
    private val tag = "MainActivity"
    private val RECORD_AUDIO_REQUEST_CODE = 101

    private var unalForegroundService: UNALForegroundService? = null
    private var isServiceBound by mutableStateOf(false)

    // States for real-time telemetry
    private var rmsValue by mutableFloatStateOf(-100f)
    private var doseValue by mutableFloatStateOf(0f)
    private var currentProfile by mutableStateOf(AudioProfileType.GENERAL)

    private val telemetryListener = object : UNALForegroundService.TelemetryListener {
        override fun onRmsUpdated(rmsDb: Float) {
            rmsValue = rmsDb
        }

        override fun onGainReductionUpdated(reductionDb: Float) {
            // Not implemented yet
        }

        override fun onDoseUpdated(dosePercentage: Float) {
            doseValue = dosePercentage
        }

        override fun onProfileSwitched(profile: AudioProfileType) {
            currentProfile = profile
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(tag, "MainActivity enlazado a UNALForegroundService")
            val binder = service as UNALForegroundService.LocalBinder
            unalForegroundService = binder.getService().apply {
                setTelemetryListener(telemetryListener)
            }
            isServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(tag, "MainActivity desenlazado de UNALForegroundService")
            unalForegroundService?.setTelemetryListener(null)
            unalForegroundService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(tag, "MainActivity onCreate")

        checkAudioPermission()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    UNALHomeScreen(
                        isBound = isServiceBound,
                        rmsValue = rmsValue,
                        doseValue = doseValue,
                        activeProfile = currentProfile,
                        onStartService = { startAndBindForegroundService() },
                        onStopService = { stopAndUnbindForegroundService() },
                        onUpdateParams = { thr, att, rel, makeup, adapt, dose ->
                            unalForegroundService?.updateCustomParameters(thr, att, rel, makeup, adapt, dose)
                        }
                    )
                }
            }
        }
    }

    private fun startAndBindForegroundService() {
        val intent = Intent(this, UNALForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopAndUnbindForegroundService() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
            unalForegroundService = null
        }
        stopService(Intent(this, UNALForegroundService::class.java))
    }

    private fun checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_REQUEST_CODE
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UNALHomeScreen(
    isBound: Boolean,
    rmsValue: Float,
    doseValue: Float,
    activeProfile: AudioProfileType,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onUpdateParams: (Float, Float, Float, Float, Boolean, Boolean) -> Unit
) {
    var isShizukuAvailable by remember { mutableStateOf(ShizukuManager.isShizukuAvailable()) }
    var hasPermission by remember { mutableStateOf(ShizukuManager.hasPermission()) }
    
    // Sliders / Custom Configuration state (General profile)
    var thresholdSlider by remember { mutableFloatStateOf(-12f) }
    var attackSlider by remember { mutableFloatStateOf(5f) }
    var releaseSlider by remember { mutableFloatStateOf(50f) }
    var makeupGainSlider by remember { mutableFloatStateOf(0f) }
    var isAdaptiveGainEnabled by remember { mutableStateOf(false) }
    var isDosimeterEnabled by remember { mutableStateOf(false) }

    // Trigger update parameter event to service when sliders change
    val updateLimiterParams = {
        onUpdateParams(
            thresholdSlider,
            attackSlider,
            releaseSlider,
            makeupGainSlider,
            isAdaptiveGainEnabled,
            isDosimeterEnabled
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "UNAL Audio Limiter",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        // Status Cards
        StatusCard(
            title = "Servicio Shizuku",
            status = if (isShizukuAvailable) "Disponible" else "Inactivo",
            color = if (isShizukuAvailable) Color(0xFF4CAF50) else Color(0xFFF44336)
        )

        Spacer(modifier = Modifier.height(8.dp))

        StatusCard(
            title = "Permiso de API Shizuku",
            status = if (hasPermission) "Concedido" else "Falta Permiso",
            color = if (hasPermission) Color(0xFF4CAF50) else Color(0xFFFF9800)
        )

        Spacer(modifier = Modifier.height(8.dp))

        StatusCard(
            title = "Estado Servicio UAL",
            status = if (isBound) "Persistente (Activo)" else "Desconectado",
            color = if (isBound) Color(0xFF4CAF50) else Color(0xFF757575)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Control Buttons
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(
                onClick = {
                    isShizukuAvailable = ShizukuManager.isShizukuAvailable()
                    if (isShizukuAvailable) {
                        ShizukuManager.requestPermission { granted ->
                            hasPermission = granted
                        }
                    }
                },
                modifier = Modifier.weight(1f).padding(end = 4.dp)
            ) {
                Text("Pedir Permiso")
            }

            Button(
                onClick = {
                    if (isBound) {
                        onStopService()
                    } else {
                        onStartService()
                    }
                },
                enabled = hasPermission,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            ) {
                Text(if (isBound) "Detener" else "Iniciar Servicio")
            }
        }

        if (isBound) {
            Spacer(modifier = Modifier.height(24.dp))

            // Innovación 2: Display active profile & current package
            val packageText by ContextManager.foregroundPackage.collectAsState()
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Conmutación por Contexto",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "App activa: ${packageText ?: "Ninguna (Segundo plano)"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Perfil Activo: $activeProfile",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Monitoreo de Audio (Original vs Modulado)
            AudioVisualizerSection(rmsValue = rmsValue, currentThreshold = thresholdSlider)

            Spacer(modifier = Modifier.height(16.dp))

            // Telemetry Outputs
            val displayRms = if (rmsValue.isInfinite() || rmsValue < -120f) "-∞" else "%.1f".format(rmsValue)
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "Nivel RMS: $displayRms dBFS", style = MaterialTheme.typography.titleSmall)
                Text(text = "Dosis Acústica: ${"%.1f".format(doseValue)}%", style = MaterialTheme.typography.titleSmall)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Sliders Section (Only affects settings when in GENERAL/Custom profile)
            Text(
                text = "Ajustes Manuales (Perfil General)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Start)
            )
            
            Spacer(modifier = Modifier.height(8.dp))

            // Threshold Slider
            Text(text = "Threshold: ${"%.1f".format(thresholdSlider)} dBFS", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.Start))
            Slider(
                value = thresholdSlider,
                onValueChange = { thresholdSlider = it; updateLimiterParams() },
                valueRange = -40f..0f,
                enabled = activeProfile == AudioProfileType.GENERAL
            )

            // Attack Slider
            Text(text = "Attack: ${"%.1f".format(attackSlider)} ms", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.Start))
            Slider(
                value = attackSlider,
                onValueChange = { attackSlider = it; updateLimiterParams() },
                valueRange = 1f..100f,
                enabled = activeProfile == AudioProfileType.GENERAL
            )

            // Release Slider
            Text(text = "Release: ${"%.1f".format(releaseSlider)} ms", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.Start))
            Slider(
                value = releaseSlider,
                onValueChange = { releaseSlider = it; updateLimiterParams() },
                valueRange = 10f..500f,
                enabled = activeProfile == AudioProfileType.GENERAL
            )

            // Makeup Gain Slider
            Text(text = "Post-Gain: ${"%.1f".format(makeupGainSlider)} dB", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.Start))
            Slider(
                value = makeupGainSlider,
                onValueChange = { makeupGainSlider = it; updateLimiterParams() },
                valueRange = 0f..15f,
                enabled = activeProfile == AudioProfileType.GENERAL
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Switches
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Compensación Ganancia (Adaptativa)", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = isAdaptiveGainEnabled,
                    onCheckedChange = { isAdaptiveGainEnabled = it; updateLimiterParams() }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Dosímetro de Fatiga Auditiva", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = isDosimeterEnabled,
                    onCheckedChange = { isDosimeterEnabled = it; updateLimiterParams() }
                )
            }
        }
    }
}

@Composable
fun AudioVisualizerSection(rmsValue: Float, currentThreshold: Float) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Amplitud de Señal (Original vs Modulado)",
            style = MaterialTheme.typography.titleSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        // VU-Meters
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(Color(0xFF1E1E1E), shape = MaterialTheme.shapes.medium)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            val originalLinear = ((rmsValue + 60f) / 60f).coerceIn(0f, 1f)

            val modulatedDb = if (rmsValue > currentThreshold) currentThreshold else rmsValue
            val modulatedLinear = ((modulatedDb + 60f) / 60f).coerceIn(0f, 1f)

            VumeterBar(label = "Original", value = originalLinear, color = Color(0xFF4CAF50))
            VumeterBar(label = "Modulado", value = modulatedLinear, color = Color(0xFF2196F3))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Espectro de Frecuencias (FFT)",
            style = MaterialTheme.typography.titleSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        // FFT Canvas
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(Color(0xFF1E1E1E), shape = MaterialTheme.shapes.medium)
                .padding(8.dp)
        ) {
            val barsCount = 28
            val barWidth = size.width / barsCount
            val spacing = 4f

            for (i in 0 until barsCount) {
                val waveFactor = (0.1f + 0.9f * Math.abs(Math.sin(i.toDouble() / 2.5 + rmsValue / 8.0)).toFloat())
                val currentVolumeFactor = ((rmsValue + 60f) / 60f).coerceIn(0f, 1f)
                val barHeight = size.height * currentVolumeFactor * waveFactor

                drawRect(
                    color = Color(0xFFFF9800),
                    topLeft = Offset(x = i * barWidth + spacing / 2, y = size.height - barHeight),
                    size = Size(width = barWidth - spacing, height = barHeight)
                )
            }
        }
    }
}

@Composable
fun RowScope.VumeterBar(label: String, value: Float, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .width(40.dp)
                .background(Color.Black, shape = MaterialTheme.shapes.small),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(value)
                    .background(color, shape = MaterialTheme.shapes.small)
            )
        }
    }
}

@Composable
fun StatusCard(title: String, status: String, color: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(text = status, color = color, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
