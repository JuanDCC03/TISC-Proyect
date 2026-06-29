package com.almeria.limiter.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.almeria.limiter.IUNALUserService
import rikka.shizuku.Shizuku

object ShizukuManager {

    private const val TAG = "ShizukuManager"
    private const val SHIZUKU_PERMISSION_CODE = 9991

    // Binder reference to UNALUserService
    private var unalUserService: IUNALUserService? = null
    
    // Connection callbacks for clients (e.g. UNALForegroundService)
    interface ConnectionCallback {
        fun onConnected(service: IUNALUserService)
        fun onDisconnected()
    }

    private var clientCallback: ConnectionCallback? = null

    // ServiceConnection for Shizuku UserService
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Conexión Binder exitosa con UNALUserService")
            if (service != null && service.pingBinder()) {
                unalUserService = IUNALUserService.Stub.asInterface(service)
                unalUserService?.let { clientCallback?.onConnected(it) }
            } else {
                Log.e(TAG, "El Binder recibido no es válido o está muerto")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Servicio UNALUserService desconectado abruptamente")
            unalUserService = null
            clientCallback?.onDisconnected()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Binder de Shizuku recibido (Shizuku está activo)")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "El Binder de Shizuku ha muerto")
        unalUserService = null
        clientCallback?.onDisconnected()
    }

    init {
        // Registrar listeners globales para controlar el estado de Shizuku
        try {
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error al registrar listeners de Shizuku: ${e.message}")
        }
    }

    /**
     * Comprueba si el servicio Shizuku está ejecutándose y accesible en el dispositivo.
     */
    fun isShizukuAvailable(): Boolean {
        return Shizuku.pingBinder()
    }

    /**
     * Comprueba si la aplicación ya tiene permisos otorgados para usar la API de Shizuku.
     */
    fun hasPermission(): Boolean {
        if (!isShizukuAvailable()) return false
        
        return if (Shizuku.isPreV11()) {
            // Versiones antiguas de Shizuku (pre-v11) utilizan el gestor de permisos estándar de Android
            false 
        } else {
            // API v11+ verifica el permiso interno de Shizuku
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Solicita permisos de Shizuku al usuario. Esto activará la ventana emergente de Shizuku.
     */
    fun requestPermission(onRequestResult: (granted: Boolean) -> Unit) {
        if (!isShizukuAvailable()) {
            onRequestResult(false)
            return
        }

        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == SHIZUKU_PERMISSION_CODE) {
                    val granted = grantResult == PackageManager.PERMISSION_GRANTED
                    Log.i(TAG, "Resultado de la solicitud de permiso Shizuku: $granted")
                    onRequestResult(granted)
                    Shizuku.removeRequestPermissionResultListener(this)
                }
            }
        }

        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Error al solicitar permisos: ${e.message}")
            Shizuku.removeRequestPermissionResultListener(listener)
            onRequestResult(false)
        }
    }

    /**
     * Se conecta al UNALUserService (que corre con privilegios elevados) usando Shizuku.
     */
    fun bindService(callback: ConnectionCallback) {
        this.clientCallback = callback

        if (!isShizukuAvailable()) {
            Log.e(TAG, "Imposible conectar: Shizuku no está disponible")
            callback.onDisconnected()
            return
        }

        if (!hasPermission()) {
            Log.e(TAG, "Imposible conectar: Falta permiso de Shizuku")
            callback.onDisconnected()
            return
        }

        if (unalUserService != null) {
            Log.i(TAG, "Ya existe una conexión activa a UNALUserService")
            callback.onConnected(unalUserService!!)
            return
        }

        Log.i(TAG, "Vinculando servicio de usuario con Shizuku...")
        
        // Configuramos argumentos del servicio de Shizuku
        val serviceArgs = Shizuku.UserServiceArgs(
            ComponentName("com.almeria.limiter", UNALUserService::class.java.name)
        ).apply {
            processNameSuffix("unal_service")
            debuggable(true) // Habilitar debug
            version(1)
        }

        try {
            Shizuku.bindUserService(serviceArgs, serviceConnection)
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al llamar bindUserService: ${e.message}", e)
            callback.onDisconnected()
        }
    }

    /**
     * Se desconecta del servicio de usuario y detiene el proceso Binder elevado.
     */
    fun unbindService() {
        if (unalUserService != null) {
            Log.i(TAG, "Desvinculando y ordenando salida del servicio de usuario...")
            try {
                // Ordenar al proceso Shizuku que finalice (System.exit) para no fugar recursos
                unalUserService?.exit()
            } catch (e: Exception) {
                Log.e(TAG, "Error al llamar exit() en el servicio Binder: ${e.message}")
            }
            unalUserService = null
        }
        clientCallback?.onDisconnected()
        clientCallback = null
    }

    /**
     * Retorna la referencia activa del servicio Binder, si existe.
     */
    fun getService(): IUNALUserService? = unalUserService
}
