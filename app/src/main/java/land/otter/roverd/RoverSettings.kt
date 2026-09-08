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
    val cameraId: String,
)

enum class BrcLine { RTS, DTR }

object RoverSettings {
    private const val PREFS = "roverd"

    fun load(context: Context): RoverConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return RoverConfig(
            name = p.getString("name", "android-rover") ?: "android-rover",
            serverUrl = p.getString("serverUrl", "wss://rover.otter.land/rover") ?: "wss://rover.otter.land/rover",
            baud = p.getInt("baud", 115200),
            maxWheelSpeed = p.getInt("maxWheelSpeed", 500),
            brcLine = runCatching { BrcLine.valueOf(p.getString("brcLine", "RTS") ?: "RTS") }.getOrDefault(BrcLine.RTS),
            brcActiveLow = p.getBoolean("brcActiveLow", true),
            brcPulseEveryMs = p.getLong("brcPulseEveryMs", 60_000L),
            brcPulseWidthMs = p.getLong("brcPulseWidthMs", 1_000L),
            cameraId = p.getString("cameraId", "0") ?: "0",
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
            .putString("cameraId", config.cameraId)
            .apply()
    }
}
