package land.otter.roverd

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.utils.CodecUtil
import com.pedro.library.generic.GenericStream
import com.pedro.library.view.GlStreamInterface
import com.pedro.rtsp.rtsp.Protocol
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

@TargetApi(21)
class Camera2H264Streamer(
    context: Context,
    private val config: RoverConfig,
    private val onRestartNeeded: (String) -> Unit = {},
) : Closeable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val restartRequested = AtomicBoolean(false)

    @Volatile private var stream: GenericStream? = null
    private var statsThread: Thread? = null

    private val publishUrl: String by lazy { MediaUrl.videoPublishUrl(config) }

    // A rover camera is always published in a landscape H.264 canvas. Camera sensor orientation is
    // allowed to rotate pixels inside that canvas, but it is never allowed to swap codec dimensions.
    private val outputWidth: Int = max(config.cameraWidth, config.cameraHeight)
    private val outputHeight: Int = min(config.cameraWidth, config.cameraHeight)

    private data class OrientationPlan(
        val sensorMount: Int,
        val phoneRotation: Int,
        val pixelRotation: Int,
        val facing: String,
        val automatic: Boolean,
    )

    fun start() {
        check(Build.VERSION.SDK_INT >= 21) { "Camera streaming requires Android 5.0 / API 21+" }
        check(!closed.get()) { "Camera streamer is closed" }

        val orientation = resolveOrientationPlan()
        val fps = config.cameraFpsMax.coerceIn(1, 120)
        val source = RoverCamera2Source(appContext, config.cameraId)
        val generic = GenericStream(appContext, connectChecker(), source, NoAudioSource())
        stream = generic

        generic.getStreamClient().apply {
            setProtocol(Protocol.TCP)
            setOnlyVideo(true)
            setReTries(100)
            setCheckServerAlive(false)
            setSocketTimeout(10_000)
            setLogs(true)
        }

        val codecPreference = when (config.cameraEncoderName.uppercase(Locale.US)) {
            "HARDWARE" -> CodecUtil.CodecType.HARDWARE
            "SOFTWARE" -> CodecUtil.CodecType.SOFTWARE
            "CBR_PRIORITY" -> CodecUtil.CodecType.CBR_PRIORITY
            else -> CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND
        }
        generic.forceCodecType(codecPreference, CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND)

        source.setCallbacks(object : CameraCallbacks {
            override fun onCameraOpened() {
                RoverRuntimeState.log("CAMERA GenericStream camera opened id=${config.cameraId}")
                runCatching { source.applyAutomaticControls(config.cameraExposureCompensation) }
                    .onFailure { RoverRuntimeState.log("CAMERA controls failed: ${it.stackTraceToString()}") }
                applyFixedLandscapeGeometry(generic, orientation)
            }

            override fun onCameraChanged(facing: CameraHelper.Facing) {
                RoverRuntimeState.log("CAMERA GenericStream camera changed id=${source.currentCameraId()} facing=$facing")
            }

            override fun onCameraError(error: String) {
                RoverRuntimeState.setCameraPipelineState(running = false, state = "Camera error", error = error)
                RoverRuntimeState.log("CAMERA GenericStream camera error: $error")
                requestFullRestart("camera error: $error")
            }

            override fun onCameraDisconnected() {
                RoverRuntimeState.setCameraPipelineState(running = false, state = "Camera disconnected")
                RoverRuntimeState.log("CAMERA GenericStream camera disconnected")
                requestFullRestart("camera disconnected")
            }
        })

        generic.setFpsListener { actualFps ->
            RoverRuntimeState.setCameraPipelineState(fps = actualFps)
        }

        RoverRuntimeState.setCameraPipelineState(
            running = false,
            state = "Preparing GenericStream",
            cameraId = config.cameraId,
            encoderName = "RootEncoder 2.8.1 GenericStream / ${codecPreference.name}",
            width = outputWidth,
            height = outputHeight,
            fps = fps,
            bitrate = config.cameraBitrate,
            publishUrl = publishUrl,
            error = "",
        )

        RoverRuntimeState.log(
            "CAMERA GenericStream prepare id=${config.cameraId} selected=${config.cameraWidth}x${config.cameraHeight} " +
                "encodedCanvas=${outputWidth}x${outputHeight} fps=$fps bitrate=${config.cameraBitrate} " +
                "sensor=${orientation.sensorMount} display=${orientation.phoneRotation} pixels=${orientation.pixelRotation} " +
                "automatic=${orientation.automatic} facing=${orientation.facing} codec=${codecPreference.name}",
        )

        // Always use rotation=0 here. RootEncoder intentionally swaps encoder width/height when
        // prepareVideo receives 90/270. Pixel rotation is applied afterwards in GLES instead.
        val prepared = generic.prepareVideo(
            width = outputWidth,
            height = outputHeight,
            bitrate = config.cameraBitrate.coerceAtLeast(64_000),
            fps = fps,
            iFrameInterval = 2,
            rotation = 0,
        )
        if (!prepared) {
            throw IllegalStateException("Could not prepare H.264 ${outputWidth}x${outputHeight}@$fps")
        }

        applyFixedLandscapeGeometry(generic, orientation)
        generic.startStream(publishUrl)
        // Source start is asynchronous; apply once more after start and from onCameraOpened.
        applyFixedLandscapeGeometry(generic, orientation)

        RoverRuntimeState.setCameraPipelineState(
            running = true,
            state = "GenericStream camera + H264 running",
            error = "",
        )
        startStatsThread(generic)
    }

    private fun resolveOrientationPlan(): OrientationPlan {
        val catalog = runCatching { CameraDiagnostics.modeCatalog(appContext, config.cameraId) }.getOrNull()
        val sensor = normalizeRotation(catalog?.sensorOrientation ?: 0)
        val display = currentDisplayRotationDegrees()
        val facing = catalog?.facing ?: "UNKNOWN"
        val automatic = config.cameraRotation < 0

        val pixels = if (!automatic) {
            // Manual setting is deliberately literal and independent of camera metadata.
            normalizeRotation(config.cameraRotation)
        } else if (facing.equals("FRONT", true)) {
            normalizeRotation(sensor + display)
        } else {
            normalizeRotation(sensor - display)
        }
        return OrientationPlan(sensor, display, pixels, facing, automatic)
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotationDegrees(): Int {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return when (wm.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun normalizeRotation(value: Int): Int = (((value % 360) + 360) % 360 / 90) * 90

    private fun applyFixedLandscapeGeometry(generic: GenericStream, orientation: OrientationPlan) {
        val gl = generic.getGlInterface() as? GlStreamInterface ?: return
        gl.autoHandleOrientation = false
        // RoverCamera2Source already neutralizes source orientation. Only rotate the final pixels.
        gl.setRotation(0)
        gl.setEncoderSize(outputWidth, outputHeight)
        gl.setStreamRotation(orientation.pixelRotation)
        // Quarter-turns are pillarboxed/letterboxed inside the LANDSCAPE canvas rather than stretched.
        gl.setStreamIsPortrait(orientation.pixelRotation == 90 || orientation.pixelRotation == 270)
        RoverRuntimeState.log(
            "CAMERA geometry canvas=${outputWidth}x${outputHeight} pixelRotation=${orientation.pixelRotation} " +
                "quarterTurn=${orientation.pixelRotation == 90 || orientation.pixelRotation == 270}",
        )
    }

    private fun connectChecker(): ConnectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            RoverRuntimeState.setCameraPublisherState(false, "Connecting RTSP", "")
            RoverRuntimeState.log("CAMERA RTSP connecting $url")
        }

        override fun onConnectionSuccess() {
            RoverRuntimeState.setCameraPublisherState(true, "Publishing RTSP/TCP", "")
            RoverRuntimeState.log("CAMERA RTSP connected")
        }

        override fun onConnectionFailed(reason: String) {
            if (closed.get()) return
            RoverRuntimeState.setCameraPublisherState(false, "RTSP failed", reason)
            RoverRuntimeState.log("CAMERA RTSP failed: $reason")
            val client = stream?.getStreamClient()
            if (client != null && client.reTry(2_000, reason, null)) {
                RoverRuntimeState.recordCameraReconnect()
                RoverRuntimeState.log("CAMERA RTSP retry scheduled")
            } else {
                requestFullRestart("RTSP retry unavailable: $reason")
            }
        }

        override fun onDisconnect() {
            RoverRuntimeState.setCameraPublisherState(false, "RTSP disconnected", "")
            RoverRuntimeState.log("CAMERA RTSP disconnected")
            if (!closed.get()) requestFullRestart("RTSP disconnected")
        }

        override fun onAuthError() {
            RoverRuntimeState.setCameraPublisherState(false, "RTSP auth error", "RTSP authentication error")
            requestFullRestart("RTSP authentication error")
        }

        override fun onAuthSuccess() {
            RoverRuntimeState.log("CAMERA RTSP authentication success")
        }

        override fun onNewBitrate(bitrate: Long) {
            RoverRuntimeState.setCameraPipelineState(bitrate = bitrate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }

    private fun startStatsThread(generic: GenericStream) {
        statsThread = Thread({
            val client = generic.getStreamClient()
            val startedAt = System.currentTimeMillis()
            var lastSentFrames = -1L
            var lastProgressAt = startedAt

            while (!closed.get()) {
                runCatching {
                    val sentFrames = client.getSentVideoFrames()
                    val sentBytes = client.getBytesSend()
                    val dropped = client.getDroppedVideoFrames()
                    val now = System.currentTimeMillis()

                    RoverRuntimeState.setCameraLibraryCounters(sentFrames, sentBytes, dropped)
                    if (sentFrames > lastSentFrames) {
                        lastSentFrames = sentFrames
                        lastProgressAt = now
                    } else if (now - startedAt >= 12_000L && now - lastProgressAt >= 8_000L) {
                        requestFullRestart(
                            "no sent-frame progress for ${now - lastProgressAt}ms " +
                                "sent=$sentFrames connected=${RoverRuntimeState.cameraPublisherConnected}",
                        )
                    }
                }.onFailure {
                    if (!closed.get()) requestFullRestart("stats failure: ${it.message}")
                }

                try {
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "roverd-camera-stats").apply {
            isDaemon = true
            start()
        }
    }

    private fun requestFullRestart(reason: String) {
        if (closed.get() || !restartRequested.compareAndSet(false, true)) return
        RoverRuntimeState.setCameraPipelineState(state = "Restart requested", error = reason)
        RoverRuntimeState.log("CAMERA watchdog requesting full pipeline restart: $reason")
        onRestartNeeded(reason)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        statsThread?.interrupt()
        statsThread = null
        stream?.let { generic ->
            runCatching { if (generic.isStreaming) generic.stopStream() }
            runCatching { generic.release() }
        }
        stream = null
        RoverRuntimeState.setCameraPipelineState(running = false, state = "Stopped", error = "")
        RoverRuntimeState.setCameraPublisherState(false, "Stopped", "")
        RoverRuntimeState.log("CAMERA GenericStream pipeline stopped")
    }
}
