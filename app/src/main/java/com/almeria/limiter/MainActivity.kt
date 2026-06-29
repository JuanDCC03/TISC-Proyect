package com.almeria.limiter

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import android.Manifest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.almeria.limiter.shizuku.ShizukuManager

class MainActivity : ComponentActivity() {
    private val tag = "MainActivity"
    private val RECORD_AUDIO_REQUEST_CODE = 101

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
                    ShizukuTestScreen()
                }
            }
        }
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
}

@Composable
fun ShizukuTestScreen() {
    var isShizukuAvailable by remember { mutableStateOf(ShizukuManager.isShizukuAvailable()) }
    var hasPermission by remember { mutableStateOf(ShizukuManager.hasPermission()) }
    var isBound by remember { mutableStateOf(ShizukuManager.getService() != null) }
    var statusText by remember { mutableStateOf("Desconectado") }
    var rmsValue by remember { mutableFloatStateOf(-100f) }
    var doseValue by remember { mutableFloatStateOf(0f) }
    var isFilterEnabled by remember { mutableStateOf(true) }

    // Umbral de referencia estático para la simulación del limitador (-12 dBFS)
    val sliderThresholdDb = -12f

    // Callback que recibe los datos RMS en tiempo real desde el proceso de Shizuku
    val telemetryCallback = remember {
        object : IUNALServiceCallback.Stub() {
            override fun onRmsUpdated(rmsDb: Float) {
                rmsValue = rmsDb
            }

            override fun onGainReductionUpdated(reductionDb: Float) {
                // No implementado todavía
            }

            override fun onDoseUpdated(dosePercentage: Float) {
                doseValue = dosePercentage
            }
        }
    }

    val connectionCallback = remember {
        object : ShizukuManager.ConnectionCallback {
            override fun onConnected(service: IUNALUserService) {
                isBound = true
                statusText = "Conectado al Servicio Elevado"
                // Modifica tu bloque try dentro de onConnected:
                try {
                    service.registerCallback(telemetryCallback)
                    service.startEngine()

                    // ENVIAR PARÁMETROS INICIALES AL ARRANCAR (Filtro activo)
                    service.updateParameters(
                        sliderThresholdDb, // -12f
                        5f,                // attack
                        50f,               // release
                        0f,                // makeupGain
                        false,             // adaptiveGain
                        isFilterEnabled    // <--- Pasamos el estado inicial (true)
                    )

                    statusText = "Motor Activo en Sesión 0"
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error al iniciar motor: ${e.message}")
                    statusText = "Conectado pero con error en motor"
                }
            }

            override fun onDisconnected() {
                isBound = false
                statusText = "Desconectado"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Top, // Cambiado a Top para evitar desbordamiento con las gráficas
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "UNAL Shizuku Test Utility",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(20.dp))

        StatusCard(
            title = "Servicio Shizuku",
            status = if (isShizukuAvailable) "Disponible" else "No disponible/inactivo",
            color = if (isShizukuAvailable) Color(0xFF4CAF50) else Color(0xFFF44336)
        )

        Spacer(modifier = Modifier.height(12.dp))

        StatusCard(
            title = "Permiso de API",
            status = if (hasPermission) "Concedido" else "No concedido",
            color = if (hasPermission) Color(0xFF4CAF50) else Color(0xFFFF9800)
        )

        Spacer(modifier = Modifier.height(12.dp))

        StatusCard(
            title = "Estado Binder UNAL",
            status = statusText,
            color = if (isBound) Color(0xFF4CAF50) else Color(0xFF757575)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                isShizukuAvailable = ShizukuManager.isShizukuAvailable()
                if (isShizukuAvailable) {
                    ShizukuManager.requestPermission { granted ->
                        hasPermission = granted
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Verificar / Solicitar Permiso Shizuku")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                if (isBound) {
                    ShizukuManager.unbindService()
                    isBound = false
                    statusText = "Desconectado"
                } else {
                    ShizukuManager.bindService(connectionCallback)
                }
            },
            enabled = hasPermission,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isBound) "Desconectar del Servicio" else "Conectar y Arrancar Motor")
        }

        // SECCIÓN DE TELEMETRÍA AVANZADA Y CANVAS
        if (isBound) {
            Spacer(modifier = Modifier.height(16.dp))

            // --- NUEVO COMPONENTE: SWITCH DE BYPASS DE AUDIO ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isFilterEnabled) "Filtro Muro: ACTIVO" else "Filtro Muro: BYPASS",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isFilterEnabled) Color(0xFF2196F3) else Color.Gray
                    )
                    Switch(
                        checked = isFilterEnabled,
                        onCheckedChange = { checked ->
                            isFilterEnabled = checked
                            // Notificamos al backend en Shizuku en tiempo real
                            try {
                                val service = ShizukuManager.getService()
                                service?.updateParameters(
                                    sliderThresholdDb, // -12f
                                    5f,                // attack
                                    50f,               // release
                                    0f,                // makeupGain
                                    false,             // adaptiveGain
                                    checked            // <--- 'checked' apaga o prende el DynamicsProcessing nativo
                                )
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Error al enviar Bypass: ${e.message}")
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Render de los Vúmetros comparativos (Original vs Modulado) y Espectrograma FFT
            AudioVisualizerSection(rmsValue = rmsValue, currentThreshold = sliderThresholdDb)

            Spacer(modifier = Modifier.height(16.dp))

            val displayRms = if (rmsValue.isInfinite() || rmsValue < -120f) "-∞" else "%.2f".format(rmsValue)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "RMS: $displayRms dBFS", style = MaterialTheme.typography.bodyMedium)
                Text(text = "Fatiga Auditiva: ${"%.1f".format(doseValue)}%", style = MaterialTheme.typography.bodyMedium)
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

        Spacer(modifier = Modifier.height(12.dp))

        // VÚMETROS COMPARATIVOS
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .background(Color(0xFF1E1E1E), shape = MaterialTheme.shapes.medium)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Conversión lineal: Mapear la escala logarítmica (-60 a 0 dBFS) a proporcional (0.0 a 1.0)
            val originalLinear = ((rmsValue + 60f) / 60f).coerceIn(0f, 1f)

            // SIMULACIÓN DE AUDIO MODULADO: Si el RMS supera el umbral, se trunca en la barrera matemática del limitador
            val modulatedDb = if (rmsValue > currentThreshold) currentThreshold else rmsValue
            val modulatedLinear = ((modulatedDb + 60f) / 60f).coerceIn(0f, 1f)

            VumeterBar(label = "Original", value = originalLinear, color = Color(0xFF4CAF50))
            VumeterBar(label = "Modulado", value = modulatedLinear, color = Color(0xFF2196F3))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Espectro de Frecuencias Estimado (FFT)",
            style = MaterialTheme.typography.titleSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        // GRÁFICA DE BARRAS DE FRECUENCIA DE LA FFT
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .background(Color(0xFF1E1E1E), shape = MaterialTheme.shapes.medium)
                .padding(8.dp)
        ) {
            val barsCount = 24
            val barWidth = size.width / barsCount
            val spacing = 6f

            for (i in 0 until barsCount) {
                // Modelo oscilatorio armónico para simular el comportamiento de frecuencias interactuando con el volumen
                val waveFactor = (0.2f + 0.8f * Math.abs(Math.sin(i.toDouble() / 3.0 + rmsValue / 10.0)).toFloat())
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
        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .width(45.dp)
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Surface(
                color = color,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = status,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}