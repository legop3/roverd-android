package land.otter.roverd

import android.content.Context

data class RoverConfig(
    val name: String,
    val serverUrl: String,
    val baud: Int,
    val maxWheelSpeed: Int,
    val brcLine: BrcLine,
    val brcActiveLow: Boolean,
    val brcPulseEveryMs: Long,
    val brcPulseWidthMs: Long,
    val autoSideBrushEnabled: Boolean,
    val autoSideBrushSpeed: Int,
    val cameraId: String,
    val cameraEnabled: Boolean,
    val cameraWidth: Int,
    val cameraHeight: Int,
    val cameraFpsMin: Int,
    val cameraFpsMax: Int,
    val cameraBitrate: Int,
    val cameraRotation: Int,
    val cameraExposureCompensation: Int,
    val cameraEncoderName: String,
    val cameraRtspPort: Int,
    val cameraPublishUrl: String,
    val micEnabled: Boolean,
    val micAudioSource: Int,
    val micSampleRate: Int,
    val micChannels: Int,
    val micBitrate: Int,
    val micEchoCanceler: Boolean,
    val micNoiseSuppressor: Boolean,
    val micRtspPort: Int,
    val micPublishUrl: String,
) {
    val cameraFps: Int get() = cameraFpsMax
}

enum class BrcLine { RTS, DTR }

object RoverSettings {
    private const val PREFS = "roverd"

    fun load(context: Context): RoverConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val legacyFps = p.getInt("cameraFps", 30)
        val fpsMin = if (p.contains("cameraFpsMin")) p.getInt("cameraFpsMin", legacyFps) else -1
        val fpsMax = if (p.contains("cameraFpsMax")) p.getInt("cameraFpsMax", legacyFps) else legacyFps
        return RoverConfig(
            name = p.getString("name", "android-rover") ?: "android-rover",
            serverUrl = p.getString("serverUrl", "wss://rover.otter.land/rover") ?: "wss://rover.otter.land/rover",
            baud = p.getInt("baud", 115200),
            maxWheelSpeed = p.getInt("maxWheelSpeed", 500),
            brcLine = runCatching { BrcLine.valueOf(p.getString("brcLine", "RTS") ?: "RTS") }.getOrDefault(BrcLine.RTS),
            brcActiveLow = p.getBoolean("brcActiveLow", true),
            brcPulseEveryMs = p.getLong("brcPulseEveryMs", 60_000L),
            brcPulseWidthMs = p.getLong("brcPulseWidthMs", 1_000L),
            autoSideBrushEnabled = p.getBoolean("autoSideBrushEnabled", true),
            autoSideBrushSpeed = p.getInt("autoSideBrushSpeed", 20),
            cameraId = p.getString("cameraId", "0") ?: "0",
            cameraEnabled = p.getBoolean("cameraEnabled", false),
            cameraWidth = p.getInt("cameraWidth", 640),
            cameraHeight = p.getInt("cameraHeight", 480),
            cameraFpsMin = fpsMin,
            cameraFpsMax = fpsMax,
            cameraBitrate = p.getInt("cameraBitrate", 2_000_000),
            cameraRotation = p.getInt("cameraRotation", -1),
            cameraExposureCompensation = p.getInt("cameraExposureCompensation", 0),
            cameraEncoderName = p.getString("cameraEncoderName", "AUTO") ?: "AUTO",
            cameraRtspPort = p.getInt("cameraRtspPort", 8554),
            cameraPublishUrl = p.getString("cameraPublishUrl", "") ?: "",
            micEnabled = p.getBoolean("micEnabled", false),
            micAudioSource = p.getInt("micAudioSource", 0),
            micSampleRate = p.getInt("micSampleRate", 48_000),
            micChannels = p.getInt("micChannels", 1).coerceIn(1, 2),
            micBitrate = p.getInt("micBitrate", 128_000),
            micEchoCanceler = p.getBoolean("micEchoCanceler", false),
            micNoiseSuppressor = p.getBoolean("micNoiseSuppressor", false),
            micRtspPort = p.getInt("micRtspPort", 8554),
            micPublishUrl = p.getString("micPublishUrl", "") ?: "",
        )
    }

    fun save(context: Context, config: RoverConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name", config.name)
            .putString("serverUrl", config.serverUrl)
            .putInt("baud", config.baud)
            .putInt("maxWheelSpeed", config.maxWheelSpeed)
            .putString("brcLine", config.brcLine.name)
            .putBoolean("brcActiveLow", config.brcActiveLow)
            .putLong("brcPulseEveryMs", config.brcPulseEveryMs)
            .putLong("brcPulseWidthMs", config.brcPulseWidthMs)
            .putBoolean("autoSideBrushEnabled", config.autoSideBrushEnabled)
            .putInt("autoSideBrushSpeed", config.autoSideBrushSpeed)
            .putString("cameraId", config.cameraId)
            .putBoolean("cameraEnabled", config.cameraEnabled)
            .putInt("cameraWidth", config.cameraWidth)
            .putInt("cameraHeight", config.cameraHeight)
            .putInt("cameraFpsMin", config.cameraFpsMin)
            .putInt("cameraFpsMax", config.cameraFpsMax)
            .putInt("cameraFps", config.cameraFpsMax)
            .putInt("cameraBitrate", config.cameraBitrate)
            .putInt("cameraRotation", config.cameraRotation)
            .putInt("cameraExposureCompensation", config.cameraExposureCompensation)
            .putString("cameraEncoderName", config.cameraEncoderName)
            .putInt("cameraRtspPort", config.cameraRtspPort)
            .putString("cameraPublishUrl", config.cameraPublishUrl)
            .putBoolean("micEnabled", config.micEnabled)
            .putInt("micAudioSource", config.micAudioSource)
            .putInt("micSampleRate", config.micSampleRate)
            .putInt("micChannels", config.micChannels)
            .putInt("micBitrate", config.micBitrate)
            .putBoolean("micEchoCanceler", config.micEchoCanceler)
            .putBoolean("micNoiseSuppressor", config.micNoiseSuppressor)
            .putInt("micRtspPort", config.micRtspPort)
            .putString("micPublishUrl", config.micPublishUrl)
            .apply()
    }
}
