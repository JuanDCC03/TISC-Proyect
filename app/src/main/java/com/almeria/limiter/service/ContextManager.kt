package com.almeria.limiter.service

import com.almeria.limiter.model.AudioProfileType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ContextManager {
    
    private val _foregroundPackage = MutableStateFlow<String?>(null)
    val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()

    private val _activeProfile = MutableStateFlow(AudioProfileType.GENERAL)
    val activeProfile: StateFlow<AudioProfileType> = _activeProfile.asStateFlow()

    /**
     * Updates the active foreground package name and automatically maps it 
     * to a preset UAL/UNAL Audio Profile (Innovation 2).
     */
    fun updateForegroundPackage(packageName: String?) {
        if (packageName == _foregroundPackage.value) return
        
        _foregroundPackage.value = packageName
        _activeProfile.value = when (packageName) {
            // Music Apps
            "com.spotify.music",
            "com.spotify.lite",
            "com.google.android.apps.youtube.music",
            "com.apple.android.music",
            "deezer.android.app" -> AudioProfileType.MUSIC

            // Video / Dialog Apps
            "com.google.android.youtube",
            "com.zhiliaoapp.musically", // TikTok Global
            "com.zhiliaoapp.musically.go",
            "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient", // Prime Video
            "com.disney.disneyplus" -> AudioProfileType.DIALOGUE

            // Default
            else -> AudioProfileType.GENERAL
        }
    }
}
