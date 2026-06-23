package com.ual.limiter

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ual.limiter.shizuku.ShizukuManager


class MainActivity : ComponentActivity() {

    private val tag = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(tag, "MainActivity onCreate")

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
}

@Composable
fun ShizukuTestScreen() {
    var isShizukuAvailable by remember { mutableStateOf(ShizukuManager.isShizukuAvailable()) }
    var hasPermission by remember { mutableStateOf(ShizukuManager.hasPermission()) }
    var isBound by remember { mutableStateOf(ShizukuManager.getService() != null) }
    var statusText by remember { mutableStateOf("Desconectado") }
    var rmsValue by remember { mutableFloatStateOf(-100f) }
    var doseValue by remember { mutableFloatStateOf(0f) }

    // Callback to receive real-time audio data from the elevated process
    val telemetryCallback = remember {
        object : IUALServiceCallback.Stub() {
            override fun onRmsUpdated(rmsDb: Float) {
                rmsValue = rmsDb
            }

            override fun onGainReductionUpdated(reductionDb: Float) {
                // No implementado todavía en esta prueba rápida
            }

            override fun onDoseUpdated(dosePercentage: Float) {
                doseValue = dosePercentage
            }
        }
    }

    val connectionCallback = remember {
        object : ShizukuManager.ConnectionCallback {
            override fun onConnected(service: IUALUserService) {
                isBound = true
                statusText = "Conectado al Servicio Elevado"
                try {
                    // Registrar callback para recibir datos RMS y dosímetro
                    service.registerCallback(telemetryCallback)
                    // Arrancar el motor nativo de audio
                    service.startEngine()
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
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "UAL Shizuku Test Utility",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(24.dp))

        // Shizuku Availability Card
        StatusCard(
            title = "Servicio Shizuku",
            status = if (isShizukuAvailable) "Disponible" else "No disponible/inactivo",
            color = if (isShizukuAvailable) Color(0xFF4CAF50) else Color(0xFFF44336)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Permission Card
        StatusCard(
            title = "Permiso de API",
            status = if (hasPermission) "Concedido" else "No concedido",
            color = if (hasPermission) Color(0xFF4CAF50) else Color(0xFFFF9800)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // IPC Bind Status Card
        StatusCard(
            title = "Estado Binder UAL",
            status = statusText,
            color = if (isBound) Color(0xFF4CAF50) else Color(0xFF757575)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Action Buttons
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

        if (isBound) {
            Spacer(modifier = Modifier.height(32.dp))
            Text(text = "Monitoreo en tiempo real (Sesión 0):", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "RMS de Salida: ${"%.2f".format(rmsValue)} dBFS")
            Text(text = "Dosis de Fatiga Auditiva: ${"%.1f".format(doseValue)}%")
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
