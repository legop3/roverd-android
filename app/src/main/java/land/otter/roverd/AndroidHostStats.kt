package land.otter.roverd

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import kotlin.math.round

/** Android equivalent of Pi roverd host_stats.go.
 *
 * Only fields that Android can actually observe are emitted. Pi-only core-voltage and
 * throttling flags are intentionally omitted rather than fabricated.
 */
class AndroidHostStats(context: Context) {
    private val appContext = context.applicationContext

    private data class CpuSample(val total: Long, val idle: Long)
    private var previousCpu: CpuSample? = null

    private data class NetSample(val rx: Long, val tx: Long, val atMs: Long)
    private var previousNet: NetSample? = null

    @Synchronized
    fun collect(): JSONObject {
        val stats = JSONObject()
        val errors = JSONObject()

        stats.put("uptimeSec", round1(SystemClock.elapsedRealtime() / 1000.0))

        runCatching { readCpuSample() }
            .onSuccess { current ->
                val old = previousCpu
                previousCpu = current
                if (old != null) {
                    val totalDelta = current.total - old.total
                    val idleDelta = current.idle - old.idle
                    if (totalDelta > 0) {
                        stats.put("cpuUsedPct", round1((1.0 - idleDelta.toDouble() / totalDelta.toDouble()) * 100.0))
                    }
                }
            }
            .onFailure { errors.put("cpuUsed", it.message ?: it.javaClass.simpleName) }

        runCatching { File("/proc/loadavg").readText().trim().split(Regex("\\s+")).first().toDouble() }
            .onSuccess { stats.put("loadAvg1m", round2(it)) }
            .onFailure { errors.put("loadAvg", it.message ?: it.javaClass.simpleName) }

        runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val total = info.totalMem
            val available = info.availMem
            stats.put("memoryTotalKb", total / 1024L)
            stats.put("memoryAvailableKb", available / 1024L)
            if (total > 0) stats.put("memoryUsedPct", round1((total - available).toDouble() / total.toDouble() * 100.0))
        }.onFailure { errors.put("memory", it.message ?: it.javaClass.simpleName) }

        runCatching {
            val fs = StatFs(appContext.filesDir.absolutePath)
            val blockSize = if (Build.VERSION.SDK_INT >= 18) fs.blockSizeLong else @Suppress("DEPRECATION") fs.blockSize.toLong()
            val blocks = if (Build.VERSION.SDK_INT >= 18) fs.blockCountLong else @Suppress("DEPRECATION") fs.blockCount.toLong()
            val availableBlocks = if (Build.VERSION.SDK_INT >= 18) fs.availableBlocksLong else @Suppress("DEPRECATION") fs.availableBlocks.toLong()
            val total = blocks * blockSize
            val free = availableBlocks * blockSize
            stats.put("diskTotalBytes", total)
            stats.put("diskFreeBytes", free)
            if (total > 0) stats.put("diskUsedPct", round1((total - free).toDouble() / total.toDouble() * 100.0))
        }.onFailure { errors.put("disk", it.message ?: it.javaClass.simpleName) }

        readCpuTemperatureC()?.let { stats.put("cpuTempC", round1(it)) }

        runCatching { collectWifiAndNetwork() }
            .onSuccess { wifi -> if (wifi.length() > 0) stats.put("wifi", wifi) }
            .onFailure { errors.put("wifi", it.message ?: it.javaClass.simpleName) }

        if (errors.length() > 0) stats.put("errors", errors)
        return stats
    }

    private fun readCpuSample(): CpuSample {
        val fields = File("/proc/stat").useLines { lines ->
            lines.firstOrNull { it.startsWith("cpu ") }?.trim()?.split(Regex("\\s+"))
        } ?: throw IllegalStateException("missing /proc/stat cpu line")
        if (fields.size < 5) throw IllegalStateException("invalid /proc/stat cpu line")
        val values = fields.drop(1).map { it.toLong() }
        val total = values.sum()
        val idle = values.getOrElse(3) { 0L } + values.getOrElse(4) { 0L }
        return CpuSample(total, idle)
    }

    private fun readCpuTemperatureC(): Double? {
        val thermalRoot = File("/sys/class/thermal")
        val zones = thermalRoot.listFiles { file -> file.name.startsWith("thermal_zone") } ?: return null
        val preferred = zones.mapNotNull { zone ->
            val type = runCatching { File(zone, "type").readText().trim().lowercase() }.getOrNull() ?: return@mapNotNull null
            if (listOf("cpu", "soc", "ap", "big", "little").none { it in type }) return@mapNotNull null
            val raw = runCatching { File(zone, "temp").readText().trim().toDouble() }.getOrNull() ?: return@mapNotNull null
            normalizeTemperature(raw)
        }.firstOrNull { it in 0.0..150.0 }
        return preferred
    }

    private fun normalizeTemperature(raw: Double): Double = if (raw > 1000.0) raw / 1000.0 else raw

    @Suppress("DEPRECATION")
    private fun collectWifiAndNetwork(): JSONObject {
        val wifi = JSONObject()
        val manager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = manager.connectionInfo

        if (info != null) {
            val ssid = info.ssid?.trim('"')
            if (!ssid.isNullOrBlank() && !ssid.equals("<unknown ssid>", true)) wifi.put("ssidSample", ssid)
            if (info.rssi > -127) {
                wifi.put("signalDbm", info.rssi.toDouble())
                wifi.put("quality", WifiManager.calculateSignalLevel(info.rssi, 101).toDouble())
                wifi.put("qualityMax", 100.0)
            }
            if (Build.VERSION.SDK_INT >= 21 && info.frequency > 0) wifi.put("frequencyMhz", info.frequency)
            if (info.linkSpeed > 0) {
                // Android exposes one negotiated link-speed value here, not separate RX/TX rates.
                wifi.put("txBitrateMbit", info.linkSpeed.toDouble())
            }
        }

        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx != TrafficStats.UNSUPPORTED.toLong() && tx != TrafficStats.UNSUPPORTED.toLong() && rx >= 0 && tx >= 0) {
            wifi.put("rxBytes", rx)
            wifi.put("txBytes", tx)
            val now = SystemClock.elapsedRealtime()
            val old = previousNet
            previousNet = NetSample(rx, tx, now)
            if (old != null && now > old.atMs && rx >= old.rx && tx >= old.tx) {
                val seconds = (now - old.atMs) / 1000.0
                wifi.put("downloadMbps", round2((rx - old.rx) * 8.0 / seconds / 1_000_000.0))
                wifi.put("uploadMbps", round2((tx - old.tx) * 8.0 / seconds / 1_000_000.0))
            }
        }
        return wifi
    }

    private fun round1(value: Double): Double = round(value * 10.0) / 10.0
    private fun round2(value: Double): Double = round(value * 100.0) / 100.0
}
