package land.otter.roverd

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class WifiRecoveryWatchdog(
    context: Context,
    private val serverConnected: () -> Boolean,
    private val emitEvent: (String, Map<String, Any>) -> Unit,
) {
    companion object {
        private const val CHECK_INTERVAL_MS = 15_000L
        private const val UNVALIDATED_TIMEOUT_MS = 60_000L
        private const val WIFI_OFF_MS = 2_000L
        private const val RECOVERY_COOLDOWN_MS = 5 * 60_000L
        private const val WIFI_ENABLE_RETRY_MS = 2_000L
        private const val MAX_PENDING_ALERTS = 10
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val devicePolicy = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private var running = false
    private var unvalidatedSinceMs = 0L
    private var lastRecoveryAtMs = 0L
    private var recoveryInProgress = false
    private var unavailableReported = false
    private val pendingAlerts = mutableListOf<Pair<String, Map<String, Any>>>()

    private val checkTask = object : Runnable {
        override fun run() {
            if (!running) return
            checkNow()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(checkTask)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        unvalidatedSinceMs = 0L
        recoveryInProgress = false
        unavailableReported = false
        pendingAlerts.clear()
    }

    fun onServerConnected() {
        if (pendingAlerts.isEmpty()) return
        val copy = pendingAlerts.toList()
        pendingAlerts.clear()
        copy.forEach { (event, data) -> emitEvent(event, data) }
    }

    private fun checkNow() {
        if (Build.VERSION.SDK_INT < 23) return
        if (recoveryInProgress) return

        if (!isWifiAssociatedButUnvalidated()) {
            unvalidatedSinceMs = 0L
            unavailableReported = false
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (unvalidatedSinceMs == 0L) {
            unvalidatedSinceMs = now
            RoverRuntimeState.log("WIFI watchdog: associated Wi-Fi lost Internet validation")
            return
        }

        val badForMs = now - unvalidatedSinceMs
        if (badForMs < UNVALIDATED_TIMEOUT_MS) return
        if (lastRecoveryAtMs != 0L && now - lastRecoveryAtMs < RECOVERY_COOLDOWN_MS) return

        if (!canControlWifi()) {
            if (!unavailableReported) {
                unavailableReported = true
                RoverRuntimeState.log(
                    "WIFI watchdog: recovery needed after ${badForMs}ms but Roverd is not device owner/profile owner",
                )
                sendOrQueueAlert(
                    "phoneWifi.recovery unavailable: Roverd is not device owner",
                    mapOf(
                        "unvalidatedMs" to badForMs,
                        "action" to "wifiRadioCycle",
                        "deviceOwner" to false,
                    ),
                )
            }
            return
        }

        cycleWifi(badForMs)
    }

    private fun isWifiAssociatedButUnvalidated(): Boolean {
        if (!wifi.isWifiEnabled) return false
        if (Build.VERSION.SDK_INT < 23) return false

        var sawInternetWifi = false
        for (network in connectivity.allNetworks) {
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            sawInternetWifi = true
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return false
            }
        }
        return sawInternetWifi
    }

    private fun canControlWifi(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return devicePolicy.isDeviceOwnerApp(appContext.packageName) ||
            devicePolicy.isProfileOwnerApp(appContext.packageName)
    }

    @Suppress("DEPRECATION")
    private fun cycleWifi(badForMs: Long) {
        val now = SystemClock.elapsedRealtime()
        lastRecoveryAtMs = now
        unvalidatedSinceMs = 0L
        recoveryInProgress = true
        unavailableReported = false

        val event = "phoneWifi.recovery cycling Wi-Fi after ${badForMs / 1000}s without Internet"
        val data = mapOf<String, Any>(
            "unvalidatedMs" to badForMs,
            "action" to "wifiRadioCycle",
            "offMs" to WIFI_OFF_MS,
            "deviceOwner" to devicePolicy.isDeviceOwnerApp(appContext.packageName),
        )
        sendOrQueueAlert(event, data)
        RoverRuntimeState.log("WIFI watchdog: cycling Wi-Fi after ${badForMs}ms without validated Internet")

        val disabled = runCatching { wifi.setWifiEnabled(false) }
            .onFailure { RoverRuntimeState.log("WIFI watchdog: disable failed: ${it.stackTraceToString()}") }
            .getOrDefault(false)

        if (!disabled) {
            recoveryInProgress = false
            sendOrQueueAlert(
                "phoneWifi.recovery failed to disable Wi-Fi",
                mapOf("unvalidatedMs" to badForMs, "action" to "wifiRadioCycle"),
            )
            return
        }

        handler.postDelayed({ ensureWifiEnabled() }, WIFI_OFF_MS)
    }

    @Suppress("DEPRECATION")
    private fun ensureWifiEnabled() {
        if (!running) return
        if (wifi.isWifiEnabled) {
            recoveryInProgress = false
            RoverRuntimeState.log("WIFI watchdog: Wi-Fi enabled again")
            return
        }

        runCatching { wifi.setWifiEnabled(true) }
            .onFailure { RoverRuntimeState.log("WIFI watchdog: enable request failed: ${it.message}") }

        handler.postDelayed({ ensureWifiEnabled() }, WIFI_ENABLE_RETRY_MS)
    }

    private fun sendOrQueueAlert(event: String, data: Map<String, Any>) {
        if (serverConnected()) {
            emitEvent(event, data)
            return
        }

        if (pendingAlerts.size >= MAX_PENDING_ALERTS) pendingAlerts.removeAt(0)
        pendingAlerts += event to data.toMap()
        RoverRuntimeState.log("WIFI watchdog: queued rover alert until server reconnects: $event")
    }
}
