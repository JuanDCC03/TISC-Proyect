package com.almeria.limiter.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.util.Log

class UNALContextService : AccessibilityService() {

    private val TAG = "UNALContextService"

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // Listen to window state changes (app switches)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            Log.d(TAG, "Cambio de aplicación detectado: $packageName")
            ContextManager.updateForegroundPackage(packageName)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Servicio de accesibilidad UNAL interrumpido")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Servicio de accesibilidad UNAL conectado de forma segura")
    }
}
