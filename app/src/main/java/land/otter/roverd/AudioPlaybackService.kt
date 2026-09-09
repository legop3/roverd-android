package land.otter.roverd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import kotlin.math.min

class AudioPlaybackService : Service() {
    companion object {
        private const val CHANNEL_ID = "roverd-audio-playback"
        private const val NOTIFICATION_ID = 4
        private const val RESTART_BASE_MS = 1_000L
        private const val RESTART_MAX_MS = 15_000L

        const val ACTION_START = "audio_playback_start"
        const val ACTION_RESTART = "audio_playback_restart"
        const val ACTION_STOP = "audio_playback_stop"
        const val ACTION_APPLY_LEVEL = "audio_playback_apply_level"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var generation = 0L
    private var restartAttempt = 0
    private var pendingRestart: Runnable? = null
    private var manualStop = false
    private var destroying = false

    override fun onCreate() {
        super.onCreate()
        RoverRuntimeState.initialize(this)
        promoteToForeground("Reverse audio ready")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                manualStop = true
                cancelRestart()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                manualStop = false
                restartAttempt = 0
                cancelRestart()
                startPipeline(forceRestart = true)
            }
            ACTION_APPLY_LEVEL -> applyCurrentVolume()
            ACTION_START, null -> {
                manualStop = false
                if (player == null) startPipeline(forceRestart = false)
            }
        }
        return START_STICKY
    }

    private fun startPipeline(forceRestart: Boolean) {
        if (!forceRestart && player != null) return
        cancelRestart()
        stopPipeline(markStopped = false)

        val cfg = RoverSettings.load(this)
        if (!cfg.audioPlaybackEnabled) {
            AudioPlaybackRuntimeState.stopped("Disabled")
            stopSelf()
            return
        }

        val url = runCatching { MediaUrl.audioPlaybackUrl(cfg) }.getOrElse {
            val error = it.message ?: it.javaClass.simpleName
            AudioPlaybackRuntimeState.stopped("Invalid reverse-audio URL", error)
            RoverRuntimeState.log("AUDIO playback URL failure: ${it.stackTraceToString()}")
            scheduleRestart("URL error: $error")
            return
        }

        val gen = ++generation
        try {
            AudioPlaybackRuntimeState.running = true
            AudioPlaybackRuntimeState.playing = false
            AudioPlaybackRuntimeState.lastError = ""
            AudioPlaybackRuntimeState.setConfig(url, cfg.audioPlaybackVolume, cfg.audioPlaybackNetworkCachingMs)
            AudioPlaybackRuntimeState.event("Opening RTSP/TCP", false)

            val options = arrayListOf(
                "--network-caching=${cfg.audioPlaybackNetworkCachingMs.coerceIn(0, 2_000)}",
                "--no-video-title-show",
            )
            val vlc = LibVLC(applicationContext, options)
            val mp = MediaPlayer(vlc)
            mp.setEventListener(MediaPlayer.EventListener { event ->
                handler.post {
                    if (gen == generation && !destroying) handlePlayerEvent(gen, event)
                }
            })
            mp.setAudioOutput("android_audiotrack")
            mp.setAudioOutputDevice("stereo")

            val media = Media(vlc, Uri.parse(url)).apply {
                addOption(":rtsp-tcp")
                addOption(":network-caching=${cfg.audioPlaybackNetworkCachingMs.coerceIn(0, 2_000)}")
                addOption(":no-video")
            }
            mp.media = media
            media.release()

            libVlc = vlc
            player = mp
            applyCurrentVolume()

            RoverRuntimeState.log(
                "AUDIO playback starting generation=$gen url=$url volume=${cfg.audioPlaybackVolume} " +
                    "cacheMs=${cfg.audioPlaybackNetworkCachingMs}",
            )
            mp.play()
            updateNotification("Listening to ${cfg.name}-fwd")
        } catch (t: Throwable) {
            if (gen != generation) return
            val error = t.message ?: t.javaClass.simpleName
            RoverRuntimeState.log("AUDIO playback startup failure: ${t.stackTraceToString()}")
            AudioPlaybackRuntimeState.stopped("Playback startup failed", error)
            updateNotification("Reverse audio failed: $error")
            stopPipeline(markStopped = false)
            scheduleRestart("startup failure: $error")
        }
    }

    private fun handlePlayerEvent(gen: Long, event: MediaPlayer.Event) {
        if (gen != generation) return
        when (event.type) {
            MediaPlayer.Event.Opening -> AudioPlaybackRuntimeState.event("Opening RTSP/TCP", false)
            MediaPlayer.Event.Buffering -> AudioPlaybackRuntimeState.event("Buffering", false)
            MediaPlayer.Event.Playing -> {
                restartAttempt = 0
                AudioPlaybackRuntimeState.event("Playing reverse Opus audio", true)
                RoverRuntimeState.log("AUDIO playback active generation=$gen")
                updateNotification("Reverse audio playing")
            }
            MediaPlayer.Event.Paused -> AudioPlaybackRuntimeState.event("Paused", false)
            MediaPlayer.Event.Stopped -> {
                AudioPlaybackRuntimeState.event("Stopped by decoder", false)
                if (!manualStop && !destroying) scheduleRestart("LibVLC stopped")
            }
            MediaPlayer.Event.EndReached -> {
                AudioPlaybackRuntimeState.event("RTSP stream ended", false)
                if (!manualStop && !destroying) scheduleRestart("RTSP stream ended")
            }
            MediaPlayer.Event.EncounteredError -> {
                AudioPlaybackRuntimeState.event("LibVLC playback error", false, "LibVLC EncounteredError")
                if (!manualStop && !destroying) scheduleRestart("LibVLC playback error")
            }
        }
    }

    private fun applyCurrentVolume() {
        val cfg = RoverSettings.load(this)
        AudioPlaybackRuntimeState.configuredVolume = cfg.audioPlaybackVolume
        AudioPlaybackRuntimeState.applyForwardGain(RoverAudioController.forwardGain)
        val effective = AudioPlaybackRuntimeState.effectiveVolume
        player?.let { runCatching { it.setVolume(effective) } }
        RoverRuntimeState.log(
            "AUDIO playback volume cfg=${cfg.audioPlaybackVolume} forwardGain=${RoverAudioController.forwardGain} effective=$effective",
        )
    }

    private fun scheduleRestart(reason: String) {
        if (manualStop || destroying || !RoverSettings.load(this).audioPlaybackEnabled || pendingRestart != null) return
        val attempt = restartAttempt++
        val delay = min(RESTART_BASE_MS * (1L shl attempt.coerceAtMost(4)), RESTART_MAX_MS)
        AudioPlaybackRuntimeState.reconnects += 1
        AudioPlaybackRuntimeState.playing = false
        AudioPlaybackRuntimeState.state = "Reconnecting in ${delay}ms"
        AudioPlaybackRuntimeState.lastError = reason
        RoverRuntimeState.log("AUDIO playback restart in ${delay}ms attempt=${attempt + 1}: $reason")
        updateNotification("Reverse audio reconnecting")
        val task = Runnable {
            pendingRestart = null
            if (!manualStop && !destroying && RoverSettings.load(this).audioPlaybackEnabled) {
                startPipeline(forceRestart = true)
            }
        }
        pendingRestart = task
        handler.postDelayed(task, delay)
    }

    private fun cancelRestart() {
        pendingRestart?.let { handler.removeCallbacks(it) }
        pendingRestart = null
    }

    private fun stopPipeline(markStopped: Boolean) {
        ++generation
        val oldPlayer = player
        val oldVlc = libVlc
        player = null
        libVlc = null
        oldPlayer?.let {
            runCatching { it.setEventListener(null) }
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        oldVlc?.let { runCatching { it.release() } }
        if (markStopped) AudioPlaybackRuntimeState.stopped()
    }

    @Suppress("DEPRECATION")
    private fun promoteToForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(text))
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Rover reverse audio", NotificationManager.IMPORTANCE_LOW),
                )
            }
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Roverd Android speaker")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        destroying = true
        manualStop = true
        cancelRestart()
        stopPipeline(markStopped = true)
        RoverRuntimeState.log("AUDIO playback service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
