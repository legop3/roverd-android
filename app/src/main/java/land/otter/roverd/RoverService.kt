package land.otter.roverd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

class RoverService : Service() {
    companion object {
        private const val CHANNEL_ID = "roverd"
        private const val NOTIFICATION_ID = 1

        const val ACTION_RESTART = "restart"
        const val ACTION_RECOVER_ALL = "recover_all"
        const val ACTION_RECONNECT_SERVER = "reconnect_server"
        const val ACTION_RECONNECT_USB = "reconnect_usb"
        const val ACTION_RESTART_SENSOR_STREAM = "restart_sensor_stream"
        const val ACTION_PULSE_BRC = "pulse_brc"
    }

    private var roomba: UsbRoomba? = null
    private var server: RoverServerClient? = null
    private var autoCharge: AutoChargeController? = null
    private var wifiRecoveryWatchdog: WifiRecoveryWatchdog? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var lastPhoneBatteryLevel: Int? = null

    override fun onCreate() {
        super.onCreate()
        RoverRuntimeState.initialize(this)
        CrashLogger.install(this)
        startForeground(NOTIFICATION_ID, buildNotification("Starting rover"))
        acquireRuntimeLocks()
        startRuntime()
        startBatteryMonitor()
        startWifiRecoveryWatchdog()
    }

    private fun startRuntime() {
        stopRuntime()
        val config = RoverSettings.load(this)
        RoverRuntimeState.update("Starting ${config.name}")
        RoverRuntimeState.log(
            "CONFIG name=${config.name} server=${config.serverUrl} baud=${config.baud} maxWheelSpeed=${config.maxWheelSpeed} " +
                "brcLine=${config.brcLine} brcActiveLow=${config.brcActiveLow} brcEveryMs=${config.brcPulseEveryMs} " +
                "brcWidthMs=${config.brcPulseWidthMs}",
        )

        autoCharge = AutoChargeController(
            seekDock = {
                val activeRoomba = roomba
                if (activeRoomba == null || !activeRoomba.isConnected()) {
                    throw IllegalStateException("USB serial not connected")
                }
                activeRoomba.seekDock()
            },
            emitEvent = ::emitEvent,
        )

        startServer(config)

        roomba = UsbRoomba(
            context = this,
            config = config,
            onSensorFrame = { frame ->
                autoCharge?.onSensorFrame(frame)
                server?.sendSensorFrame(frame)
            },
            onStatus = ::status,
            onConnectionChanged = { connected ->
                RoverRuntimeState.log("USB optional subsystem connected=$connected")
            },
        ).also { it.connect() }
    }

    private fun emitEvent(event: String, data: Map<String, Any>) {
        val activeServer = server
        if (activeServer != null) {
            activeServer.sendEvent(event, data)
        } else {
            RoverRuntimeState.log("EVENT event=$event data=$data (server unavailable)")
        }
    }

    @Suppress("DEPRECATION")
    private fun startBatteryMonitor() {
        if (batteryReceiver != null) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_BATTERY_CHANGED) return

                val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (rawLevel < 0 || scale <= 0) return

                val levelPercent = ((rawLevel * 100) / scale).coerceIn(0, 100)
                if (lastPhoneBatteryLevel == levelPercent) return
                lastPhoneBatteryLevel = levelPercent

                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

                RoverRuntimeState.log(
                    "PHONE BATTERY level=${levelPercent}% charging=$charging plugged=$plugged status=$status",
                )
                emitEvent(
                    "phoneBattery.levelChanged ${levelPercent}%",
                    mapOf(
                        "levelPercent" to levelPercent,
                        "charging" to charging,
                        "plugged" to (plugged != 0),
                        "plugType" to plugged,
                        "status" to status,
                    ),
                )
            }
        }

        batteryReceiver = receiver
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        }.onFailure {
            batteryReceiver = null
            RoverRuntimeState.log("PHONE BATTERY monitor registration failed: ${it.stackTraceToString()}")
        }
    }

    private fun stopBatteryMonitor() {
        val receiver = batteryReceiver ?: return
        runCatching { unregisterReceiver(receiver) }
            .onFailure { RoverRuntimeState.log("PHONE BATTERY monitor unregister failed: ${it.message}") }
        batteryReceiver = null
        lastPhoneBatteryLevel = null
    }

    private fun startWifiRecoveryWatchdog() {
        if (wifiRecoveryWatchdog != null) return
        wifiRecoveryWatchdog = WifiRecoveryWatchdog(
            context = this,
            serverConnected = { RoverRuntimeState.serverConnected },
            emitEvent = ::emitEvent,
        ).also { it.start() }
    }

    private fun stopWifiRecoveryWatchdog() {
        wifiRecoveryWatchdog?.stop()
        wifiRecoveryWatchdog = null
    }

    private fun startServer(config: RoverConfig = RoverSettings.load(this)) {
        stopServer()
        server = RoverServerClient(
            context = this,
            config = config,
            roombaProvider = { roomba },
            onStatus = { message ->
                status(message)
                if (message == "Server connected") {
                    wifiRecoveryWatchdog?.onServerConnected()
                }
            },
        ).also { it.start() }
    }

    private fun recoverAllRuntime() {
        RoverRuntimeState.log("RECOVERY restarting rover runtime and all configured media pipelines")
        startRuntime()
        val cfg = RoverSettings.load(this)

        if (cfg.cameraEnabled && Build.VERSION.SDK_INT >= 21) {
            startServiceCompat(Intent(this, CameraPublisherService::class.java).setAction(CameraPublisherService.ACTION_RESTART))
        } else {
            stopService(Intent(this, CameraPublisherService::class.java))
        }

        if (cfg.micEnabled) {
            startServiceCompat(Intent(this, MicPublisherService::class.java).setAction(MicPublisherService.ACTION_RESTART))
        } else {
            stopService(Intent(this, MicPublisherService::class.java))
        }

        if (cfg.audioPlaybackEnabled) {
            startServiceCompat(Intent(this, AudioPlaybackService::class.java).setAction(AudioPlaybackService.ACTION_RESTART))
        } else {
            stopService(Intent(this, AudioPlaybackService::class.java))
        }
        status("Full rover runtime recovered")
    }

    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    @Suppress("DEPRECATION")
    private fun acquireRuntimeLocks() {
        if (wakeLock?.isHeld != true) {
            runCatching {
                val power = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "land.otter.roverd:RoverRuntime",
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure {
                RoverRuntimeState.log("POWER wake lock acquire failed: ${it.stackTraceToString()}")
            }
        }

        if (wifiLock?.isHeld != true) {
            runCatching {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wifi.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "land.otter.roverd:RoverWifi",
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure {
                RoverRuntimeState.log("POWER Wi-Fi lock acquire failed: ${it.stackTraceToString()}")
            }
        }

        RoverRuntimeState.setPowerLocks(
            wakeHeld = wakeLock?.isHeld == true,
            wifiHeld = wifiLock?.isHeld == true,
        )

        if (Build.VERSION.SDK_INT >= 23) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            RoverRuntimeState.log(
                "POWER batteryOptimizationExempt=${power.isIgnoringBatteryOptimizations(packageName)}",
            )
        }
    }

    private fun releaseRuntimeLocks() {
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }.onFailure {
            RoverRuntimeState.log("POWER Wi-Fi lock release failed: ${it.stackTraceToString()}")
        }
        wifiLock = null

        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }.onFailure {
            RoverRuntimeState.log("POWER wake lock release failed: ${it.stackTraceToString()}")
        }
        wakeLock = null
        RoverRuntimeState.setPowerLocks(false, false)
    }

    @Synchronized
    private fun stopServer() {
        server?.close()
        server = null
    }

    private fun stopRuntime() {
        stopServer()
        roomba?.close()
        roomba = null
        autoCharge = null
    }

    private fun status(message: String) {
        RoverRuntimeState.update(message)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Rover runtime", NotificationManager.IMPORTANCE_LOW),
                )
            }
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Roverd Android")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireRuntimeLocks()
        when (intent?.action) {
            ACTION_RESTART -> {
                RoverRuntimeState.log("MANUAL full rover runtime restart")
                startRuntime()
            }
            ACTION_RECOVER_ALL -> recoverAllRuntime()
            ACTION_RECONNECT_SERVER -> {
                RoverRuntimeState.log("MANUAL rover WebSocket reconnect")
                startServer()
            }
            ACTION_RECONNECT_USB -> {
                status("Manual USB reconnect requested")
                roomba?.reconnect()
            }
            ACTION_RESTART_SENSOR_STREAM -> {
                status("Manual sensor stream restart requested")
                roomba?.restartSensorStreamNow()
            }
            ACTION_PULSE_BRC -> {
                status("Manual BRC pulse requested")
                roomba?.pulseBrcNow()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopWifiRecoveryWatchdog()
        stopBatteryMonitor()
        stopRuntime()
        releaseRuntimeLocks()
        RoverRuntimeState.update("Stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
