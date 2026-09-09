package land.otter.roverd

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class CameraPublisherService : Service() {
    companion object {
        private const val CHANNEL_ID = "roverd-camera"
        private const val NOTIFICATION_ID = 2

        const val ACTION_START = "camera_start"
        const val ACTION_RESTART = "camera_restart"
        const val ACTION_STOP = "camera_stop"
    }

    private var streamer: Camera2H264Streamer? = null

    override fun onCreate() {
        super.onCreate()
        RoverRuntimeState.initialize(this)
        promoteToForeground("Camera publisher ready")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                RoverRuntimeState.log("CAMERA manual stop requested")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                RoverRuntimeState.log("CAMERA restart requested")
                startPipeline(forceRestart = true)
            }
            ACTION_START, null -> {
                if (streamer == null) {
                    RoverRuntimeState.log("CAMERA start requested")
                    startPipeline(forceRestart = false)
                } else {
                    RoverRuntimeState.log("CAMERA start ignored; pipeline already running")
                }
            }
        }
        return START_STICKY
    }

    private fun startPipeline(forceRestart: Boolean) {
        if (!forceRestart && streamer != null) return
        streamer?.close()
        streamer = null
        RoverRuntimeState.resetCameraCounters()

        val config = RoverSettings.load(this)
        if (!config.cameraEnabled) {
            RoverRuntimeState.setCameraPipelineState(running = false, state = "Disabled", error = "")
            stopSelf()
            return
        }
        if (Build.VERSION.SDK_INT < 21) {
            RoverRuntimeState.setCameraPipelineState(
                running = false,
                state = "Unsupported Android version",
                error = "Camera streaming currently requires Android 5.0 / API 21+",
            )
            RoverRuntimeState.log("CAMERA stream not started: API ${Build.VERSION.SDK_INT} < 21")
            stopSelf()
            return
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            RoverRuntimeState.setCameraPipelineState(running = false, state = "Camera permission missing", error = "CAMERA permission not granted")
            RoverRuntimeState.log("CAMERA stream not started: CAMERA permission missing")
            stopSelf()
            return
        }

        try {
            RoverRuntimeState.log("CAMERA publisher service starting")
            streamer = Camera2H264Streamer(this, config).also { it.start() }
            updateNotification("Camera ${config.cameraId} -> MediaMTX")
        } catch (t: Throwable) {
            RoverRuntimeState.setCameraPipelineState(
                running = false,
                state = "Camera startup failed",
                error = t.message ?: t.javaClass.simpleName,
            )
            RoverRuntimeState.log("CAMERA startup failure: ${t.stackTraceToString()}")
            updateNotification("Camera failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    @Suppress("DEPRECATION")
    private fun promoteToForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Rover camera", NotificationManager.IMPORTANCE_LOW),
                )
            }
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Roverd Android camera")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        streamer?.close()
        streamer = null
        RoverRuntimeState.setCameraPipelineState(running = false, state = "Stopped", error = "")
        RoverRuntimeState.log("CAMERA publisher service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
