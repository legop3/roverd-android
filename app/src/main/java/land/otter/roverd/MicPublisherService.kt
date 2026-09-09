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
import com.pedro.encoder.utils.CodecUtil
import com.pedro.library.rtsp.RtspOnlyAudio
import com.pedro.rtsp.rtsp.Protocol
import kotlin.math.min

class MicPublisherService : Service() {
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
    private var pendingRestart: Runnable? = null
    private var statsTask: Runnable? = null
    private var restartAttempt = 0
    private var manualStop = false
    private var destroying = false

    private var generationCounter = 0L
    @Volatile private var activeGeneration = 0L

    override fun onCreate() {
        super.onCreate()
        RoverRuntimeState.initialize(this)
        promoteToForeground("Microphone publisher ready")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> {
                    RoverRuntimeState.log("MIC manual stop requested")
                    manualStop = true
                    cancelRestart()
                    invalidateCurrentGeneration()
                    stopSelf()
                    return START_NOT_STICKY
                }
                ACTION_RESTART -> {
                    RoverRuntimeState.log("MIC in-service restart requested")
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
        } catch (t: Throwable) {
            RoverRuntimeState.log("MIC onStartCommand failure: ${t.stackTraceToString()}")
            MicRuntimeState.markStopped("Microphone service command failed", t.message ?: t.javaClass.simpleName)
            if (!manualStop && !destroying && RoverSettings.load(this).micEnabled) {
                scheduleRestart("service command failure: ${t.message ?: t.javaClass.simpleName}", activeGeneration)
            }
        }
        return START_STICKY
    }

    private fun startPipeline(forceRestart: Boolean) {
        if (!forceRestart && stream != null) return

        cancelRestart()

        // Retire the old stream before asking RootEncoder to stop it. Its RTSP callbacks can arrive
        // asynchronously during teardown; generation checks below make those callbacks harmless.
        val generation = ++generationCounter
        activeGeneration = generation
        stopPipeline(markStopped = false)

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
            scheduleRestart("URL error: ${it.message}", generation)
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
                gain = cfg.micGainDb,
                encoderName = opusEncoders.joinToString(),
            )

            val audio = RtspOnlyAudio(connectCheckerFor(generation))
            audio.setAudioCodec(AudioCodec.OPUS)
            audio.forceCodecType(CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND)
            audio.getStreamClient().apply {
                setProtocol(Protocol.TCP)
                setCheckServerAlive(false)
                setReTries(0)
                setSocketTimeout(5_000)
                setLogs(false)
            }

            audio.setCustomAudioEffect(
                MicGainEffect(cfg.micGainDb) { value ->
                    if (generation == activeGeneration) MicRuntimeState.amplitude = value
                },
            )

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
            RoverRuntimeState.log(
                "MIC starting generation=$generation source=${cfg.micAudioSource} rate=${cfg.micSampleRate} " +
                    "channels=${cfg.micChannels} bitrate=${cfg.micBitrate} gain=${cfg.micGainDb}dB " +
                    "echo=${cfg.micEchoCanceler} noise=${cfg.micNoiseSuppressor} url=$url encoders=${opusEncoders.joinToString()}",
            )
            audio.startStream(url)
            startStats(generation)
            updateNotification("Opus ${cfg.micSampleRate}Hz/${cfg.micChannels}ch +${cfg.micGainDb}dB -> MediaMTX")
        } catch (t: Throwable) {
            if (generation != activeGeneration) return
            RoverRuntimeState.log("MIC startup failure generation=$generation: ${t.stackTraceToString()}")
            MicRuntimeState.markStopped("Microphone startup failed", t.message ?: t.javaClass.simpleName)
            updateNotification("Mic failed: ${t.message ?: t.javaClass.simpleName}")
            stopPipeline(markStopped = false)
            scheduleRestart("startup failure: ${t.message ?: t.javaClass.simpleName}", generation)
        }
    }

    private fun connectCheckerFor(generation: Long): ConnectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) = dispatchForGeneration(generation) {
            MicRuntimeState.running = true
            MicRuntimeState.connected = false
            MicRuntimeState.state = "Connecting RTSP/TCP"
            RoverRuntimeState.log("MIC RTSP connecting generation=$generation url=$url")
        }

        override fun onConnectionSuccess() = dispatchForGeneration(generation) {
            restartAttempt = 0
            MicRuntimeState.running = true
            MicRuntimeState.setConnection(true, "Publishing Opus RTSP/TCP")
            MicRuntimeState.lastError = ""
            RoverRuntimeState.log("MIC RTSP connected generation=$generation")
            updateNotification("Microphone publishing to MediaMTX")
        }

        override fun onConnectionFailed(reason: String) = dispatchForGeneration(generation) {
            MicRuntimeState.setConnection(false, "RTSP connection failed", reason)
            RoverRuntimeState.log("MIC RTSP connection failed generation=$generation: $reason")
            scheduleRestart("RTSP failure: $reason", generation)
        }

        override fun onDisconnect() = dispatchForGeneration(generation) {
            MicRuntimeState.connected = false
            MicRuntimeState.state = "RTSP disconnected"
            RoverRuntimeState.log("MIC RTSP disconnected generation=$generation")
            if (!manualStop && !destroying) scheduleRestart("RTSP disconnected", generation)
        }

        override fun onAuthError() = dispatchForGeneration(generation) {
            MicRuntimeState.setConnection(false, "RTSP auth error", "authentication rejected")
            scheduleRestart("RTSP authentication rejected", generation)
        }

        override fun onAuthSuccess() = dispatchForGeneration(generation) {
            RoverRuntimeState.log("MIC RTSP auth success generation=$generation")
        }

        override fun onNewBitrate(bitrate: Long) {
            if (generation == activeGeneration) MicRuntimeState.measuredBitrate = bitrate
        }
    }

    private fun dispatchForGeneration(generation: Long, block: () -> Unit) {
        handler.post {
            if (generation != activeGeneration || destroying) {
                RoverRuntimeState.log("MIC ignoring callback from retired generation=$generation active=$activeGeneration")
                return@post
            }
            runCatching(block).onFailure {
                RoverRuntimeState.log("MIC callback failure generation=$generation: ${it.stackTraceToString()}")
                scheduleRestart("callback failure: ${it.message ?: it.javaClass.simpleName}", generation)
            }
        }
    }

    private fun startStats(generation: Long) {
        statsTask?.let { handler.removeCallbacks(it) }
        var lastFrames = 0L
        var lastProgressAt = System.currentTimeMillis()
        val task = object : Runnable {
            override fun run() {
                if (generation != activeGeneration || destroying) return
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
                    scheduleRestart("audio publisher made no packet progress for ${System.currentTimeMillis() - lastProgressAt}ms", generation)
                    return
                }
                handler.postDelayed(this, 1_000L)
            }
        }
        statsTask = task
        handler.post(task)
    }

    private fun scheduleRestart(reason: String, generation: Long) {
        if (generation != activeGeneration) return
        if (manualStop || destroying || !RoverSettings.load(this).micEnabled || pendingRestart != null) return
        val attempt = restartAttempt++
        val delay = min(RESTART_BASE_MS * (1L shl attempt.coerceAtMost(4)), RESTART_MAX_MS)
        MicRuntimeState.reconnects += 1
        MicRuntimeState.connected = false
        MicRuntimeState.state = "Restarting microphone in ${delay}ms"
        MicRuntimeState.lastError = reason
        RoverRuntimeState.log("MIC full pipeline restart scheduled generation=$generation in ${delay}ms attempt=${attempt + 1}: $reason")
        updateNotification("Mic recovering in ${delay / 1000}s")
        val task = Runnable {
            pendingRestart = null
            if (generation == activeGeneration && !manualStop && !destroying && RoverSettings.load(this).micEnabled) {
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

    private fun invalidateCurrentGeneration() {
        activeGeneration = ++generationCounter
    }

    private fun stopPipeline(markStopped: Boolean) {
        statsTask?.let { handler.removeCallbacks(it) }
        statsTask = null
        val old = stream
        stream = null
        if (old != null) {
            runCatching {
                if (old.isStreaming) old.stopStream()
            }.onFailure {
                RoverRuntimeState.log("MIC teardown failure ignored: ${it.stackTraceToString()}")
            }
        }
        if (markStopped) MicRuntimeState.markStopped()
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
        invalidateCurrentGeneration()
        stopPipeline(markStopped = true)
        RoverRuntimeState.log("MIC publisher service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
