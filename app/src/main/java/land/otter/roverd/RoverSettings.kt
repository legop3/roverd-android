package land.otter.roverd

import android.content.Context
import java.util.Locale

data class PrivateSafetyConfig(
    val speedLimitEnabled: Boolean,
    val speedLimitMaxWheelSpeed: Int,
    val hardOvercurrentEnabled: Boolean,
    val overcurrentStopMs: Int,
    val hardBumpEnabled: Boolean,
    val bumpBackoffSpeed: Int,
    val bumpBackoffMs: Int,
    val cliffEnabled: Boolean,
    val cliffBackoffSpeed: Int,
    val cliffBackoffMs: Int,
    val virtualWallEnabled: Boolean,
    val virtualWallBackoffSpeed: Int,
    val virtualWallBackoffMs: Int,
    val triggerCooldownMs: Int,
)

data class RoverConfig(
    val name: String,
    val description: String,
    val color: String,
    val serverUrl: String,
    val mediaRtspPort: Int,
    val baud: Int,
    val usbSerialPreference: String,
    val maxWheelSpeed: Int,
    val batteryFull: Int,
    val batteryWarn: Int,
    val batteryUrgent: Int,
    val brcLine: BrcLine,
    val brcActiveLow: Boolean,
    val brcPulseEveryMs: Long,
    val brcPulseWidthMs: Long,
    val autoSideBrushEnabled: Boolean,
    val autoSideBrushSpeed: Int,
    val privateEnabled: Boolean,
    val privateSafety: PrivateSafetyConfig,
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
    val cameraPublishUrl: String,
    val micEnabled: Boolean,
    val micAudioSource: Int,
    val micSampleRate: Int,
    val micChannels: Int,
    val micBitrate: Int,
    val micGainDb: Int,
    val micEchoCanceler: Boolean,
    val micNoiseSuppressor: Boolean,
    val micPublishUrl: String,
    val audioPlaybackEnabled: Boolean,
    val audioPlaybackVolume: Int,
    val audioPlaybackNetworkCachingMs: Int,
    val audioPlaybackUrl: String,
    val ttsEnabled: Boolean,
    val ttsPitch: Float,
    val ttsRate: Float,
    val ttsVoice: String,
    val ttsVolume: Float,
    val hornEnabled: Boolean,
    val hornVolume: Float,
    val hornSineGain: Float,
    val hornSawGain: Float,
    val hornMaxDurationMs: Long,
    val headlightEnabled: Boolean,
    val headlightInitialOn: Boolean,
) {
    val cameraFps: Int get() = cameraFpsMax
}

enum class BrcLine { RTS, DTR }

object RoverSettings {
    private const val PREFS = "roverd"
    private val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")

    fun normalizeColor(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        require(HEX_COLOR.matches(trimmed)) { "Rover color must be blank or #RRGGBB" }
        return trimmed.uppercase(Locale.US)
    }

    fun load(context: Context): RoverConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val legacyFps = p.getInt("cameraFps", 30)
        val fpsMin = if (p.contains("cameraFpsMin")) p.getInt("cameraFpsMin", legacyFps) else -1
        val fpsMax = if (p.contains("cameraFpsMax")) p.getInt("cameraFpsMax", legacyFps) else legacyFps

        // Older Android builds had one RTSP port field on each media page. MediaMTX has one
        // RTSP listener, so migrate to a single rover-level port while retaining all URL overrides.
        val mediaRtspPort = if (p.contains("mediaRtspPort")) {
            p.getInt("mediaRtspPort", 8554)
        } else {
            p.getInt("cameraRtspPort", p.getInt("micRtspPort", p.getInt("audioPlaybackRtspPort", 8554)))
        }.coerceIn(1, 65_535)

        return RoverConfig(
            name = p.getString("name", "android-rover") ?: "android-rover",
            description = p.getString("description", "Android phone rover") ?: "Android phone rover",
            color = runCatching { normalizeColor(p.getString("color", "#4DB6AC") ?: "#4DB6AC") }.getOrDefault("#4DB6AC"),
            serverUrl = p.getString("serverUrl", "wss://rover.otter.land/rover") ?: "wss://rover.otter.land/rover",
            mediaRtspPort = mediaRtspPort,
            baud = p.getInt("baud", 115200),
            usbSerialPreference = p.getString("usbSerialPreference", "AUTO") ?: "AUTO",
            maxWheelSpeed = p.getInt("maxWheelSpeed", 500).coerceIn(1, 500),
            batteryFull = p.getInt("batteryFull", 2068).coerceAtLeast(1),
            batteryWarn = p.getInt("batteryWarn", 1700).coerceAtLeast(0),
            batteryUrgent = p.getInt("batteryUrgent", 1650).coerceAtLeast(0),
            brcLine = runCatching { BrcLine.valueOf(p.getString("brcLine", "RTS") ?: "RTS") }.getOrDefault(BrcLine.RTS),
            brcActiveLow = p.getBoolean("brcActiveLow", true),
            brcPulseEveryMs = p.getLong("brcPulseEveryMs", 60_000L).coerceAtLeast(1_000L),
            brcPulseWidthMs = p.getLong("brcPulseWidthMs", 1_000L).coerceAtLeast(1L),
            autoSideBrushEnabled = p.getBoolean("autoSideBrushEnabled", true),
            autoSideBrushSpeed = p.getInt("autoSideBrushSpeed", 20).coerceIn(-127, 127),
            privateEnabled = p.getBoolean("privateEnabled", false),
            privateSafety = PrivateSafetyConfig(
                speedLimitEnabled = p.getBoolean("privateSpeedLimitEnabled", false),
                speedLimitMaxWheelSpeed = p.getInt("privateSpeedLimitMaxWheelSpeed", 250).coerceIn(1, 500),
                hardOvercurrentEnabled = p.getBoolean("privateHardOvercurrentEnabled", false),
                overcurrentStopMs = p.getInt("privateOvercurrentStopMs", 300).coerceIn(0, 5_000),
                hardBumpEnabled = p.getBoolean("privateHardBumpEnabled", false),
                bumpBackoffSpeed = p.getInt("privateBumpBackoffSpeed", 250).coerceIn(1, 500),
                bumpBackoffMs = p.getInt("privateBumpBackoffMs", 350).coerceIn(0, 5_000),
                cliffEnabled = p.getBoolean("privateCliffEnabled", false),
                cliffBackoffSpeed = p.getInt("privateCliffBackoffSpeed", 250).coerceIn(1, 500),
                cliffBackoffMs = p.getInt("privateCliffBackoffMs", 500).coerceIn(0, 5_000),
                virtualWallEnabled = p.getBoolean("privateVirtualWallEnabled", true),
                virtualWallBackoffSpeed = p.getInt("privateVirtualWallBackoffSpeed", 250).coerceIn(1, 500),
                virtualWallBackoffMs = p.getInt("privateVirtualWallBackoffMs", 500).coerceIn(0, 5_000),
                triggerCooldownMs = p.getInt("privateTriggerCooldownMs", 800).coerceIn(0, 10_000),
            ),
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
            cameraPublishUrl = p.getString("cameraPublishUrl", "") ?: "",
            micEnabled = p.getBoolean("micEnabled", false),
            micAudioSource = p.getInt("micAudioSource", 0),
            micSampleRate = p.getInt("micSampleRate", 48_000),
            micChannels = p.getInt("micChannels", 1).coerceIn(1, 2),
            micBitrate = p.getInt("micBitrate", 128_000),
            micGainDb = p.getInt("micGainDb", 12).coerceIn(-20, 30),
            micEchoCanceler = p.getBoolean("micEchoCanceler", false),
            micNoiseSuppressor = p.getBoolean("micNoiseSuppressor", false),
            micPublishUrl = p.getString("micPublishUrl", "") ?: "",
            audioPlaybackEnabled = p.getBoolean("audioPlaybackEnabled", true),
            audioPlaybackVolume = p.getInt("audioPlaybackVolume", 100).coerceIn(0, 200),
            audioPlaybackNetworkCachingMs = p.getInt("audioPlaybackNetworkCachingMs", 100).coerceIn(0, 2_000),
            audioPlaybackUrl = p.getString("audioPlaybackUrl", "") ?: "",
            ttsEnabled = p.getBoolean("ttsEnabled", true),
            ttsPitch = p.getFloat("ttsPitch", 1.0f).coerceIn(0.1f, 3.0f),
            ttsRate = p.getFloat("ttsRate", 1.0f).coerceIn(0.25f, 3.0f),
            ttsVoice = p.getString("ttsVoice", "") ?: "",
            ttsVolume = p.getFloat("ttsVolume", 1.0f).coerceIn(0f, 1f),
            hornEnabled = p.getBoolean("hornEnabled", true),
            hornVolume = p.getFloat("hornVolume", 0.25f).coerceIn(0f, 1f),
            hornSineGain = p.getFloat("hornSineGain", 1.0f).coerceIn(0f, 4f),
            hornSawGain = p.getFloat("hornSawGain", 0.7f).coerceIn(0f, 4f),
            hornMaxDurationMs = p.getLong("hornMaxDurationMs", 10_000L).coerceIn(100L, 60_000L),
            headlightEnabled = p.getBoolean("headlightEnabled", true),
            headlightInitialOn = p.getBoolean("headlightInitialOn", false),
        )
    }

    fun save(context: Context, config: RoverConfig) {
        val color = normalizeColor(config.color)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name", config.name)
            .putString("description", config.description)
            .putString("color", color)
            .putString("serverUrl", config.serverUrl)
            .putInt("mediaRtspPort", config.mediaRtspPort.coerceIn(1, 65_535))
            // Keep legacy keys mirrored so downgrading the app does not silently change endpoints.
            .putInt("cameraRtspPort", config.mediaRtspPort.coerceIn(1, 65_535))
            .putInt("micRtspPort", config.mediaRtspPort.coerceIn(1, 65_535))
            .putInt("audioPlaybackRtspPort", config.mediaRtspPort.coerceIn(1, 65_535))
            .putInt("baud", config.baud)
            .putString("usbSerialPreference", config.usbSerialPreference)
            .putInt("maxWheelSpeed", config.maxWheelSpeed)
            .putInt("batteryFull", config.batteryFull)
            .putInt("batteryWarn", config.batteryWarn)
            .putInt("batteryUrgent", config.batteryUrgent)
            .putString("brcLine", config.brcLine.name)
            .putBoolean("brcActiveLow", config.brcActiveLow)
            .putLong("brcPulseEveryMs", config.brcPulseEveryMs)
            .putLong("brcPulseWidthMs", config.brcPulseWidthMs)
            .putBoolean("autoSideBrushEnabled", config.autoSideBrushEnabled)
            .putInt("autoSideBrushSpeed", config.autoSideBrushSpeed)
            .putBoolean("privateEnabled", config.privateEnabled)
            .putBoolean("privateSpeedLimitEnabled", config.privateSafety.speedLimitEnabled)
            .putInt("privateSpeedLimitMaxWheelSpeed", config.privateSafety.speedLimitMaxWheelSpeed)
            .putBoolean("privateHardOvercurrentEnabled", config.privateSafety.hardOvercurrentEnabled)
            .putInt("privateOvercurrentStopMs", config.privateSafety.overcurrentStopMs)
            .putBoolean("privateHardBumpEnabled", config.privateSafety.hardBumpEnabled)
            .putInt("privateBumpBackoffSpeed", config.privateSafety.bumpBackoffSpeed)
            .putInt("privateBumpBackoffMs", config.privateSafety.bumpBackoffMs)
            .putBoolean("privateCliffEnabled", config.privateSafety.cliffEnabled)
            .putInt("privateCliffBackoffSpeed", config.privateSafety.cliffBackoffSpeed)
            .putInt("privateCliffBackoffMs", config.privateSafety.cliffBackoffMs)
            .putBoolean("privateVirtualWallEnabled", config.privateSafety.virtualWallEnabled)
            .putInt("privateVirtualWallBackoffSpeed", config.privateSafety.virtualWallBackoffSpeed)
            .putInt("privateVirtualWallBackoffMs", config.privateSafety.virtualWallBackoffMs)
            .putInt("privateTriggerCooldownMs", config.privateSafety.triggerCooldownMs)
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
            .putString("cameraPublishUrl", config.cameraPublishUrl)
            .putBoolean("micEnabled", config.micEnabled)
            .putInt("micAudioSource", config.micAudioSource)
            .putInt("micSampleRate", config.micSampleRate)
            .putInt("micChannels", config.micChannels)
            .putInt("micBitrate", config.micBitrate)
            .putInt("micGainDb", config.micGainDb)
            .putBoolean("micEchoCanceler", config.micEchoCanceler)
            .putBoolean("micNoiseSuppressor", config.micNoiseSuppressor)
            .putString("micPublishUrl", config.micPublishUrl)
            .putBoolean("audioPlaybackEnabled", config.audioPlaybackEnabled)
            .putInt("audioPlaybackVolume", config.audioPlaybackVolume)
            .putInt("audioPlaybackNetworkCachingMs", config.audioPlaybackNetworkCachingMs)
            .putString("audioPlaybackUrl", config.audioPlaybackUrl)
            .putBoolean("ttsEnabled", config.ttsEnabled)
            .putFloat("ttsPitch", config.ttsPitch)
            .putFloat("ttsRate", config.ttsRate)
            .putString("ttsVoice", config.ttsVoice)
            .putFloat("ttsVolume", config.ttsVolume)
            .putBoolean("hornEnabled", config.hornEnabled)
            .putFloat("hornVolume", config.hornVolume)
            .putFloat("hornSineGain", config.hornSineGain)
            .putFloat("hornSawGain", config.hornSawGain)
            .putLong("hornMaxDurationMs", config.hornMaxDurationMs)
            .putBoolean("headlightEnabled", config.headlightEnabled)
            .putBoolean("headlightInitialOn", config.headlightInitialOn)
            .apply()
    }
}
