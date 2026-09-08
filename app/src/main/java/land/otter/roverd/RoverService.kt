package land.otter.roverd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class RoverService : Service() {
    companion object {
        private const val CHANNEL_ID = "roverd"
        private const val NOTIFICATION_ID = 1

        const val ACTION_RESTART = "restart"
        const val ACTION_RECONNECT_USB = "reconnect_usb"
        const val ACTION_RESTART_SENSOR_STREAM = "restart_sensor_stream"
        const val ACTION_PULSE_BRC = "pulse_brc"
    }

    private var roomba: UsbRoomba? = null
    private var server: RoverServerClient? = null

    override fun onCreate() {
        super.onCreate()
        RoverRuntimeState.initialize(this)
        installCrashLogger()
        startForeground(NOTIFICATION_ID, buildNotification("Starting rover"))
        startRuntime()
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

        server = RoverServerClient(
            config = config,
            roombaProvider = { roomba },
            onStatus = ::status,
        ).also { it.start() }

        roomba = UsbRoomba(
            context = this,
            config = config,
            onSensorFrame = { frame -> server?.sendSensorFrame(frame) },
            onStatus = ::status,
            onConnectionChanged = { connected ->
                RoverRuntimeState.log("USB optional subsystem connected=$connected")
            },
        ).also { it.connect() }
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
        when (intent?.action) {
            ACTION_RESTART -> {
                RoverRuntimeState.log("MANUAL full rover runtime restart")
                startRuntime()
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
        stopRuntime()
        RoverRuntimeState.update("Stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is RoverCrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(RoverCrashHandler(previous))
    }

    private class RoverCrashHandler(
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            RoverRuntimeState.log("FATAL thread=${thread.name}: ${throwable.stackTraceToString()}")
            previous?.uncaughtException(thread, throwable)
        }
    }
}
