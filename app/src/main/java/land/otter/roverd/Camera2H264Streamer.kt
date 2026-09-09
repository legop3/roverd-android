package land.otter.roverd

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import com.pedro.encoder.input.gl.render.filters.RotationFilterRender
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.utils.CodecUtil
import com.pedro.library.view.GlStreamInterface
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

    @Volatile private var stream: RoverH264Stream? = null
    private var statsThread: Thread? = null

    private val publishUrl: String by lazy { MediaUrl.videoPublishUrl(config) }

    // The encoded rover stream is always landscape. Camera rotation is rendered into this fixed
    // canvas before MediaCodec, so rotation can never swap the H.264 dimensions.
    private val outputWidth = max(config.cameraWidth, config.cameraHeight)
    private val outputHeight = min(config.cameraWidth, config.cameraHeight)

    private data class OrientationPlan(
        val sensorMount: Int,
        val displayRotation: Int,
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
        val roverStream = RoverH264Stream(appContext, source)
        stream = roverStream

        val codecPreference = when (config.cameraEncoderName.uppercase(Locale.US)) {
            "HARDWARE" -> CodecUtil.CodecType.HARDWARE
            "SOFTWARE" -> CodecUtil.CodecType.SOFTWARE
            "CBR_PRIORITY" -> CodecUtil.CodecType.CBR_PRIORITY
            else -> CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND
        }
        roverStream.forceCodecType(codecPreference, CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND)

        source.setCallbacks(object : CameraCallbacks {
            override fun onCameraOpened() {
                RoverRuntimeState.log("CAMERA Camera2 source opened id=${config.cameraId}")
                runCatching { source.applyAutomaticControls(config.cameraExposureCompensation) }
                    .onFailure { RoverRuntimeState.log("CAMERA controls failed: ${it.stackTraceToString()}") }
            }

            override fun onCameraChanged(facing: CameraHelper.Facing) {
                RoverRuntimeState.log("CAMERA source changed id=${source.currentCameraId()} facing=$facing")
            }

            override fun onCameraError(error: String) {
                RoverRuntimeState.setCameraPipelineState(running = false, state = "Camera error", error = error)
                RoverRuntimeState.log("CAMERA source error: $error")
                requestFullRestart("camera error: $error")
            }

            override fun onCameraDisconnected() {
                RoverRuntimeState.setCameraPipelineState(running = false, state = "Camera disconnected")
                RoverRuntimeState.log("CAMERA source disconnected")
                requestFullRestart("camera disconnected")
            }
        })

        roverStream.setFpsListener { actualFps ->
            RoverRuntimeState.setCameraPipelineState(fps = actualFps)
        }

        RoverRuntimeState.setCameraPipelineState(
            running = false,
            state = "Preparing Camera2 + RootEncoder",
            cameraId = config.cameraId,
            encoderName = "RootEncoder 2.8.1 encoder / ${codecPreference.name} + roverd RTSP",
            width = outputWidth,
            height = outputHeight,
            fps = fps,
            bitrate = config.cameraBitrate,
            publishUrl = publishUrl,
            error = "",
        )

        RoverRuntimeState.log(
            "CAMERA prepare id=${config.cameraId} selected=${config.cameraWidth}x${config.cameraHeight} " +
                "encodedCanvas=${outputWidth}x${outputHeight} fps=$fps bitrate=${config.cameraBitrate} " +
                "sensor=${orientation.sensorMount} display=${orientation.displayRotation} pixels=${orientation.pixelRotation} " +
                "automatic=${orientation.automatic} facing=${orientation.facing} codec=${codecPreference.name}",
        )

        val prepared = roverStream.prepareVideo(
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

        // RootEncoder 2.8.1's StreamBase starts AudioEncoder unconditionally, even when the
        // configured source is NoAudioSource. Prepare that encoder so startStream can run, while
        // RoverH264Stream still discards all audio and the RTSP publisher remains video-only.
        val audioShimPrepared = roverStream.prepareAudio(
            sampleRate = 44_100,
            isStereo = false,
            bitrate = 32_000,
        )
        if (!audioShimPrepared) {
            throw IllegalStateException("Could not prepare RootEncoder video-only audio shim")
        }
        RoverRuntimeState.log("CAMERA RootEncoder NoAudioSource encoder shim prepared")

        configureAspectSafeRotation(roverStream, orientation)
        roverStream.startStream(publishUrl)

        RoverRuntimeState.setCameraPipelineState(
            running = true,
            state = "Camera2 + RootEncoder H264 running",
            error = "",
        )
        startStatsThread()
    }

    private fun resolveOrientationPlan(): OrientationPlan {
        val catalog = runCatching { CameraDiagnostics.modeCatalog(appContext, config.cameraId) }.getOrNull()
        val sensor = normalizeRotation(catalog?.sensorOrientation ?: 0)
        val display = currentDisplayRotationDegrees()
        val facing = catalog?.facing ?: "UNKNOWN"
        val automatic = config.cameraRotation < 0

        val pixels = if (!automatic) {
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

    /**
     * Do not use RootEncoder's stream-rotation/portrait viewport path here. That path is designed
     * around phone portrait streaming and can change the effective viewport aspect.
     *
     * Instead, keep the encoder/output path completely landscape and unrotated, then use
     * RotationFilterRender.setRotationFixed(), which is RootEncoder's own aspect-preserving path
     * for 90/270 degree rotations. This keeps the camera image's geometry correct without changing
     * or squeezing the H.264 canvas.
     */
    private fun configureAspectSafeRotation(roverStream: RoverH264Stream, orientation: OrientationPlan) {
        val gl = roverStream.getGlInterface() as? GlStreamInterface ?: return

        gl.autoHandleOrientation = false
        gl.setRotation(0)
        gl.setEncoderSize(outputWidth, outputHeight)
        gl.setStreamIsPortrait(false)
        gl.setStreamRotation(0)
        gl.setStreamViewPort(null)

        val rotationFilter = RotationFilterRender().apply {
            setRotationFixed(
                orientation.pixelRotation,
                outputWidth,
                outputHeight,
                false,
            )
        }
        gl.setFilter(rotationFilter)

        RoverRuntimeState.log(
            "CAMERA geometry canvas=${outputWidth}x${outputHeight} " +
                "filterRotation=${orientation.pixelRotation} streamRotation=0 portrait=false viewport=default " +
                "mode=RotationFilterRender.setRotationFixed",
        )
    }

    private fun startStatsThread() {
        statsThread = Thread({
            val startedAt = System.currentTimeMillis()
            var lastEncoded = -1L
            var lastPublished = -1L
            var lastEncodeProgressAt = startedAt
            var lastPublishProgressAt = startedAt

            while (!closed.get()) {
                val now = System.currentTimeMillis()
                val encoded = RoverRuntimeState.cameraEncodedFrames
                val published = RoverRuntimeState.cameraPublishedFrames

                if (encoded > lastEncoded) {
                    lastEncoded = encoded
                    lastEncodeProgressAt = now
                }
                if (published > lastPublished) {
                    lastPublished = published
                    lastPublishProgressAt = now
                }

                if (now - startedAt >= 12_000L) {
                    if (now - lastEncodeProgressAt >= 8_000L) {
                        requestFullRestart("encoder/camera produced no frames for ${now - lastEncodeProgressAt}ms")
                    } else if (now - lastPublishProgressAt >= 8_000L) {
                        requestFullRestart(
                            "MediaMTX publish made no progress for ${now - lastPublishProgressAt}ms " +
                                "encoded=$encoded published=$published",
                        )
                    }
                }

                try {
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "roverd-camera-watchdog").apply {
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
        stream?.let { roverStream ->
            runCatching { if (roverStream.isStreaming) roverStream.stopStream() }
            runCatching { roverStream.release() }
            runCatching { roverStream.closeTransport() }
        }
        stream = null
        RoverRuntimeState.setCameraPipelineState(running = false, state = "Stopped", error = "")
        RoverRuntimeState.setCameraPublisherState(false, "Stopped", "")
        RoverRuntimeState.log("CAMERA pipeline stopped")
    }
}
