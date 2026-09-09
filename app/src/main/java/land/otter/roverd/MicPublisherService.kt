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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.audio.AmplitudeEffect
import com.pedro.encoder.utils.CodecUtil
import com.pedro.library.rtsp.RtspOnlyAudio
import com.pedro.rtsp.rtsp.Protocol
import kotlin.math.min

class MicPublisherService : Service(), ConnectChecker {
    companion object {
        private const val CHANNEL_ID = "roverd-mic"
        private const val NOTIFICATION_ID = 3
        private const val RESTART_BASE_MS = 2_000L
        private const val RESTART_MAX_MS = 30_000L

        const val ACTION_START = "mic_start"
        const val ACTION_RESTART = "mic_restart"
        const val ACTION_STOP = "mic_stop"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var stream: RtspOnlyAudio? = null
    private var amplitudeEffect: AmplitudeEffect? = null
    private var pendingRestart: Runnable? = null
    private var statsTask: Runnable? = null
    private var restartAttempt = 0
    private var manualStop = false
    private var destroying = false

    override fun onCreate() {
        super.onCreate()
        RoverRuntimeState.initialize(this)
        promoteToForeground("Microphone publisher ready")
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
            ACTION_START, null -> {
                manualStop = false
                if (stream == null) startPipeline(forceRestart = false)
            }
        }
        return START_STICKY
    }

    private fun startPipeline(forceRestart: Boolean) {
        if (!forceRestart && stream != null) return
        stopPipeline(markStopped = false)
        cancelRestart()

        val cfg = RoverSettings.load(this)
        if (!cfg.micEnabled) {
            MicRuntimeState.markStopped("Disabled")
            stopSelf()
            return
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            MicRuntimeState.markStopped("Microphone permission missing", "RECORD_AUDIO permission not granted")
            RoverRuntimeState.log("MIC stream not started: RECORD_AUDIO permission missing")
            stopSelf()
            return
        }

        val url = runCatching { MediaUrl.micPublishUrl(cfg) }.getOrElse {
            MicRuntimeState.markStopped("Invalid RTSP URL", it.message ?: it.javaClass.simpleName)
            RoverRuntimeState.log("MIC URL derivation failed: ${it.stackTraceToString()}")
            scheduleRestart("URL error: ${it.message}")
            return
        }

        try {
            val opusEncoders = MicDiagnostics.opusEncoders()
            if (opusEncoders.isEmpty()) {
                throw IllegalStateException("No Android MediaCodec Opus encoder is available")
            }
            if (!MicDiagnostics.captureConfigSupported(cfg.micSampleRate, cfg.micChannels)) {
                throw IllegalStateException("AudioRecord does not support ${cfg.micSampleRate} Hz / ${cfg.micChannels} ch")
            }

            MicRuntimeState.resetCounters()
            MicRuntimeState.running = true
            MicRuntimeState.state = "Preparing microphone + Opus"
            MicRuntimeState.lastError = ""
            MicRuntimeState.setConfig(
                url = url,
                rate = cfg.micSampleRate,
                channelCount = cfg.micChannels,
                bitrate = cfg.micBitrate,
                encoderName = opusEncoders.joinToString(),
            )

            val audio = RtspOnlyAudio(this)
            audio.setAudioCodec(AudioCodec.OPUS)
            audio.forceCodecType(CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND)
            audio.getStreamClient().apply {
                setProtocol(Protocol.TCP)
                setCheckServerAlive(false)
                setReTries(0)
                setSocketTimeout(5_000)
                setLogs(false)
            }

            val amp = AmplitudeEffect(object : AmplitudeEffect.Listener {
                override fun onAmplitude(value: Float) {
                    MicRuntimeState.amplitude = value
                }
            })
            audio.setCustomAudioEffect(amp)

            val prepared = audio.prepareAudio(
                cfg.micAudioSource,
                cfg.micBitrate.coerceIn(16_000, 510_000),
                cfg.micSampleRate,
                cfg.micChannels >= 2,
                cfg.micEchoCanceler,
                cfg.micNoiseSuppressor,
            )
            if (!prepared) {
                throw IllegalStateException(
                    "Could not prepare microphone/Opus ${cfg.micSampleRate} Hz ${cfg.micChannels} ch ${cfg.micBitrate} bps",
                )
            }

            stream = audio
            amplitudeEffect = amp
            amp.start()
            RoverRuntimeState.log(
                "MIC starting source=${cfg.micAudioSource} rate=${cfg.micSampleRate} channels=${cfg.micChannels} " +
                    "bitrate=${cfg.micBitrate} echo=${cfg.micEchoCanceler} noise=${cfg.micNoiseSuppressor} url=$url encoders=${opusEncoders.joinToString()}",
            )
            audio.startStream(url)
            startStats()
            updateNotification("Opus ${cfg.micSampleRate}Hz/${cfg.micChannels}ch -> MediaMTX")
        } catch (t: Throwable) {
            RoverRuntimeState.log("MIC startup failure: ${t.stackTraceToString()}")
            MicRuntimeState.markStopped("Microphone startup failed", t.message ?: t.javaClass.simpleName)
            updateNotification("Mic failed: ${t.message ?: t.javaClass.simpleName}")
            stopPipeline(markStopped = false)
            scheduleRestart("startup failure: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun startStats() {
        statsTask?.let { handler.removeCallbacks(it) }
        var lastFrames = 0L
        var lastProgressAt = System.currentTimeMillis()
        val task = object : Runnable {
            override fun run() {
                val audio = stream ?: return
                val client = audio.getStreamClient()
                val frames = client.getSentAudioFrames()
                val bytes = client.getBytesSend()
                val dropped = client.getDroppedAudioFrames()
                val queue = client.getItemsInCache()
                if (frames > lastFrames) {
                    lastFrames = frames
                    lastProgressAt = System.currentTimeMillis()
                }
                MicRuntimeState.updateStats(frames, bytes, dropped, queue)

                if (MicRuntimeState.connected && System.currentTimeMillis() - lastProgressAt > 8_000L) {
                    scheduleRestart("audio publisher made no packet progress for ${System.currentTimeMillis() - lastProgressAt}ms")
                    return
                }
                handler.postDelayed(this, 1_000L)
            }
        }
        statsTask = task
        handler.post(task)
    }

    private fun scheduleRestart(reason: String) {
        if (manualStop || destroying || !RoverSettings.load(this).micEnabled || pendingRestart != null) return
        val attempt = restartAttempt++
        val delay = min(RESTART_BASE_MS * (1L shl attempt.coerceAtMost(4)), RESTART_MAX_MS)
        MicRuntimeState.reconnects += 1
        MicRuntimeState.connected = false
        MicRuntimeState.state = "Restarting microphone in ${delay}ms"
        MicRuntimeState.lastError = reason
        RoverRuntimeState.log("MIC full pipeline restart scheduled in ${delay}ms attempt=${attempt + 1}: $reason")
        updateNotification("Mic recovering in ${delay / 1000}s")
        val task = Runnable {
            pendingRestart = null
            if (!manualStop && !destroying && RoverSettings.load(this).micEnabled) {
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
        statsTask?.let { handler.removeCallbacks(it) }
        statsTask = null
        amplitudeEffect?.let { runCatching { it.stop() } }
        amplitudeEffect = null
        stream?.let { audio ->
            runCatching { if (audio.isStreaming) audio.stopStream() }
        }
        stream = null
        if (markStopped) MicRuntimeState.markStopped()
    }

    override fun onConnectionStarted(url: String) {
        MicRuntimeState.running = true
        MicRuntimeState.connected = false
        MicRuntimeState.state = "Connecting RTSP/TCP"
        RoverRuntimeState.log("MIC RTSP connecting url=$url")
    }

    override fun onConnectionSuccess() {
        restartAttempt = 0
        MicRuntimeState.running = true
        MicRuntimeState.setConnection(true, "Publishing Opus RTSP/TCP")
        MicRuntimeState.lastError = ""
        RoverRuntimeState.log("MIC RTSP connected")
        updateNotification("Microphone publishing to MediaMTX")
    }

    override fun onConnectionFailed(reason: String) {
        MicRuntimeState.setConnection(false, "RTSP connection failed", reason)
        RoverRuntimeState.log("MIC RTSP connection failed: $reason")
        scheduleRestart("RTSP failure: $reason")
    }

    override fun onDisconnect() {
        MicRuntimeState.connected = false
        MicRuntimeState.state = "RTSP disconnected"
        RoverRuntimeState.log("MIC RTSP disconnected")
        if (!manualStop && !destroying) scheduleRestart("RTSP disconnected")
    }

    override fun onAuthError() {
        MicRuntimeState.setConnection(false, "RTSP auth error", "authentication rejected")
        scheduleRestart("RTSP authentication rejected")
    }

    override fun onAuthSuccess() {
        RoverRuntimeState.log("MIC RTSP auth success")
    }

    override fun onNewBitrate(bitrate: Long) {
        MicRuntimeState.measuredBitrate = bitrate
    }

    @Suppress("DEPRECATION")
    private fun promoteToForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
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
                    NotificationChannel(CHANNEL_ID, "Rover microphone", NotificationManager.IMPORTANCE_LOW),
                )
            }
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Roverd Android microphone")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        destroying = true
        manualStop = true
        cancelRestart()
        stopPipeline(markStopped = true)
        RoverRuntimeState.log("MIC publisher service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
