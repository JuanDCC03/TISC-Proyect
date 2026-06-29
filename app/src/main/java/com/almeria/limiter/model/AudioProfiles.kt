package com.almeria.limiter.model

enum class AudioProfileType {
    MUSIC,      // Spotify, YT Music (dynamic protection, slow release)
    DIALOGUE,   // YouTube, TikTok (fast peak limiting, dialog leveling)
    GENERAL     // Default custom limiter
}

data class LimiterParameters(
    val thresholdDb: Float,
    val attackMs: Float,
    val releaseMs: Float,
    val makeupGainDb: Float
) {
    companion object {
        // Preset constants for each profile type
        val MusicPreset = LimiterParameters(
            thresholdDb = -12f,
            attackMs = 15f,
            releaseMs = 120f,
            makeupGainDb = 2f
        )

        val DialoguePreset = LimiterParameters(
            thresholdDb = -15f,
            attackMs = 5f,
            releaseMs = 45f,
            makeupGainDb = 5f // Boost low dialog passages while clipping high volume peaks
        )

        val GeneralPreset = LimiterParameters(
            thresholdDb = -10f,
            attackMs = 8f,
            releaseMs = 70f,
            makeupGainDb = 0f
        )

        fun getParametersFor(type: AudioProfileType): LimiterParameters {
            return when (type) {
                AudioProfileType.MUSIC -> MusicPreset
                AudioProfileType.DIALOGUE -> DialoguePreset
                AudioProfileType.GENERAL -> GeneralPreset
            }
        }
    }
}
