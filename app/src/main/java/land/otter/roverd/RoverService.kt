package land.otter.roverd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

class RoverService : Service() {
    companion object {
        private const val CHANNEL_ID = "roverd"
        private const val NOTIFICATION_ID = 1
    }

    private var roomba: UsbRoomba? = null
    private var server: RoverServerClient? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("Starting rover"))
        startRuntime()
    }

    private fun startRuntime() {
        stopRuntime()
        val config = RoverSettings.load(this)
        RoverRuntimeState.update("Starting ${config.name}")

        lateinit var usb: UsbRoomba
        usb = UsbRoomba(
            context = this,
            config = config,
            onSensorFrame = { frame -> server?.sendSensorFrame(frame) },
            onStatus = ::status,
            onConnectionChanged = { connected ->
                if (connected) startServer(config, usb)
                else stopServer()
            },
        )
        roomba = usb
        usb.connect()
    }

    @Synchronized
    private fun startServer(config: RoverConfig, usb: UsbRoomba) {
        if (server != null) return
        server = RoverServerClient(
            config = config,
            roomba = usb,
            onStatus = ::status,
        ).also { it.start() }
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
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Rover runtime", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Roverd Android")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "restart") startRuntime()
        return START_STICKY
    }

    override fun onDestroy() {
        stopRuntime()
        RoverRuntimeState.update("Stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
