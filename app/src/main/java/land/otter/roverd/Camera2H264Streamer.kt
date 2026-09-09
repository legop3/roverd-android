package land.otter.roverd

import android.annotation.TargetApi
import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.utils.CodecUtil
import com.pedro.library.rtsp.RtspCamera2
import com.pedro.library.view.GlStreamInterface
import com.pedro.rtsp.rtsp.Protocol
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@TargetApi(21)
class Camera2H264Streamer(
    context: Context,
    private val config: RoverConfig,
    private val onRestartNeeded: (String) -> Unit = {},
) : Closeable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val restartRequested = AtomicBoolean(false)

    @Volatile
    private var stream: RtspCamera2? = null
    private var statsThread: Thread? = null

    private val publishUrl: String by lazy { MediaUrl.videoPublishUrl(config) }

    private data class OrientationPlan(
        val sensorMount: Int,
        val phoneMount: Int,
        val pixelRotation: Int,
        val facing: String,
        val automatic: Boolean,
    )

    fun start() {
        check(Build.VERSION.SDK_INT >= 21) { "RootEncoder Camera2 streaming requires Android 5.0 / API 21+" }
        check(!closed.get()) { "Camera streamer is closed" }

        val orientation = resolveOrientationPlan()
        val fps = config.cameraFpsMax.coerceIn(1, 120)
        val rtsp = RtspCamera2(appContext, connectChecker())
        stream = rtsp

        rtsp.getStreamClient().apply {
            setProtocol(Protocol.TCP)
            setOnlyVideo(true)
            setReTries(100)
            // RootEncoder's optional server-alive probe is ICMP/Echo-style reachability, not RTSP
            // health. It can kill a perfectly healthy MediaMTX TCP publish session after seconds.
            setCheckServerAlive(false)
            setLogs(true)
        }

        val codecPreference = when (config.cameraEncoderName.uppercase(Locale.US)) {
            "HARDWARE" -> CodecUtil.CodecType.HARDWARE
            "SOFTWARE" -> CodecUtil.CodecType.SOFTWARE
            "CBR_PRIORITY" -> CodecUtil.CodecType.CBR_PRIORITY
            else -> CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND
        }
        rtsp.forceCodecType(codecPreference, CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND)

        // Select the exact Android camera ID before the background camera is opened.
        rtsp.switchCamera(config.cameraId)

        rtsp.setCameraCallbacks(object : CameraCallbacks {
            override fun onCameraOpened() {
                RoverRuntimeState.log("CAMERA RootEncoder camera opened id=${config.cameraId}")
                applyCameraControls(rtsp)
                applyFixedOutputGeometry(rtsp, orientation)
            }

            override fun onCameraChanged(facing: CameraHelper.Facing) {
                RoverRuntimeState.log("CAMERA RootEncoder camera changed id=${rtsp.currentCameraId} facing=$facing")
            }

            override fun onCameraError(error: String) {
                RoverRuntimeState.setCameraPipelineState(running = false, state = "Camera error", error = error)
                RoverRuntimeState.log("CAMERA RootEncoder camera error: $error")
                requestFullRestart("camera error: $error")
            }

            override fun onCameraDisconnected() {
                RoverRuntimeState.setCameraPipelineState(running = false, state = "Camera disconnected")
                RoverRuntimeState.log("CAMERA RootEncoder camera disconnected")
                requestFullRestart("camera disconnected")
            }
        })

        rtsp.setFpsListener { actualFps ->
            RoverRuntimeState.setCameraPipelineState(fps = actualFps)
        }

        RoverRuntimeState.setCameraPipelineState(
            running = false,
            state = "Preparing RootEncoder",
            cameraId = config.cameraId,
            encoderName = "RootEncoder / ${codecPreference.name}",
            // Output geometry is deliberately fixed. Rotating the image must never silently turn
            // a 640x480 stream into a 480x640 stream.
            width = config.cameraWidth,
            height = config.cameraHeight,
            fps = fps,
            bitrate = config.cameraBitrate,
            publishUrl = publishUrl,
            error = "",
        )

        RoverRuntimeState.log(
            "CAMERA RootEncoder prepare id=${config.cameraId} output=${config.cameraWidth}x${config.cameraHeight} " +
                "fpsCeiling=$fps selectedRange=${config.cameraFpsMin}-${config.cameraFpsMax} bitrate=${config.cameraBitrate} " +
                "sensorMount=${orientation.sensorMount} phoneRotation=${orientation.phoneMount} " +
                "pixelRotation=${orientation.pixelRotation} automatic=${orientation.automatic} facing=${orientation.facing} " +
                "codec=${codecPreference.name} url=$publishUrl",
        )

        // Keep encoder/container dimensions fixed. RootEncoder swaps width/height when its encoder
        // rotation is 90/270, which is exactly the vertical-container failure we don't want.
        val prepared = rtsp.prepareVideo(
            config.cameraWidth,
            config.cameraHeight,
            fps,
            config.cameraBitrate.coerceAtLeast(64_000),
            2,
            0,
        )
        if (!prepared) {
            throw IllegalStateException(
                "RootEncoder could not prepare H.264 ${config.cameraWidth}x${config.cameraHeight}@$fps",
            )
        }

        rtsp.startStream(publishUrl)
        // Camera opening is asynchronous. Apply immediately and again from onCameraOpened so
        // RootEncoder's own prepareGlView cannot win a race and restore legacy rotation mapping.
        applyFixedOutputGeometry(rtsp, orientation)

        RoverRuntimeState.setCameraPipelineState(
            running = true,
            state = "RootEncoder camera + H264 running",
            error = "",
        )
        startStatsThread(rtsp)
    }

    /**
     * Manual values are literal final-image rotations. AUTO alone uses Camera2 sensor mounting plus
     * the phone/display rotation sampled once at stream startup. Later UI auto-rotation cannot
     * mutate the stream.
     */
    private fun resolveOrientationPlan(): OrientationPlan {
        val catalog = runCatching { CameraDiagnostics.modeCatalog(appContext, config.cameraId) }.getOrNull()
        val sensorMount = normalizeRotation(catalog?.sensorOrientation ?: 0)
        val phoneRotation = currentPhoneRotationDegrees()
        val facing = catalog?.facing ?: "UNKNOWN"
        val automatic = config.cameraRotation < 0

        val pixelRotation = if (!automatic) {
            // 0/90/180/270 in the UI mean exactly those pixel rotations. This deliberately bypasses
            // vendor metadata so there is always a deterministic manual correction available.
            normalizeRotation(config.cameraRotation)
        } else if (facing.equals("FRONT", ignoreCase = true)) {
            // Android Camera2 relative image orientation for a front-facing sensor. Mirroring is a
            // separate presentation choice and is not mixed into encoded-frame geometry.
            normalizeRotation(sensorMount + phoneRotation)
        } else {
            // Android Camera2 relative image orientation for back/external sensors.
            normalizeRotation(sensorMount - phoneRotation)
        }

        return OrientationPlan(
            sensorMount = sensorMount,
            phoneMount = phoneRotation,
            pixelRotation = pixelRotation,
            facing = facing,
            automatic = automatic,
        )
    }

    @Suppress("DEPRECATION")
    private fun currentPhoneRotationDegrees(): Int {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return when (wm.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun normalizeRotation(value: Int): Int =
        (((value % 360) + 360) % 360 / 90) * 90

    private fun applyFixedOutputGeometry(rtsp: RtspCamera2, orientation: OrientationPlan) {
        val gl = rtsp.glInterface as? GlStreamInterface
        if (gl == null) {
            RoverRuntimeState.log("CAMERA RootEncoder GL interface is not GlStreamInterface; fixed orientation unavailable")
            return
        }

        // Neutral camera FBO + final-screen rotation. The viewport calculator receives whether this
        // is a quarter-turn so it letter/pillarboxes instead of stretching a portrait image across a
        // landscape canvas. H.264 dimensions remain exactly the selected dimensions.
        gl.autoHandleOrientation = false
        gl.setRotation(0)
        gl.setStreamRotation(orientation.pixelRotation)
        gl.setStreamIsPortrait(orientation.pixelRotation == 90 || orientation.pixelRotation == 270)

        RoverRuntimeState.log(
            "CAMERA fixed geometry output=${config.cameraWidth}x${config.cameraHeight} " +
                "screenRotation=${orientation.pixelRotation} quarterTurn=${orientation.pixelRotation == 90 || orientation.pixelRotation == 270}",
        )
    }

    private fun applyCameraControls(rtsp: RtspCamera2) {
        runCatching {
            val ae = rtsp.enableAutoExposure()
            val awb = rtsp.enableAutoWhiteBalance(CaptureRequest.CONTROL_AWB_MODE_AUTO)
            val af = rtsp.enableAutoFocus()
            rtsp.setExposure(config.cameraExposureCompensation)
            RoverRuntimeState.log(
                "CAMERA RootEncoder controls AE=$ae AWB=$awb AF=$af exposureComp=${config.cameraExposureCompensation}",
            )
        }.onFailure {
            RoverRuntimeState.log("CAMERA RootEncoder control apply failed: ${it.stackTraceToString()}")
        }
    }

    private fun connectChecker(): ConnectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            RoverRuntimeState.setCameraPublisherState(false, "RootEncoder connecting RTSP", "")
            RoverRuntimeState.log("CAMERA RootEncoder RTSP connecting $url")
        }

        override fun onConnectionSuccess() {
            RoverRuntimeState.setCameraPublisherState(true, "RootEncoder publishing RTSP/TCP", "")
            RoverRuntimeState.log("CAMERA RootEncoder RTSP connected")
        }

        override fun onConnectionFailed(reason: String) {
            if (closed.get()) return
            RoverRuntimeState.setCameraPublisherState(false, "RootEncoder RTSP failed", reason)
            RoverRuntimeState.log("CAMERA RootEncoder RTSP failed: $reason")
            val client = stream?.getStreamClient() ?: return
            if (client.reTry(2_000, reason, null)) {
                RoverRuntimeState.recordCameraReconnect()
                RoverRuntimeState.log("CAMERA RootEncoder RTSP retry scheduled")
            } else {
                requestFullRestart("RTSP retry unavailable: $reason")
            }
        }

        override fun onDisconnect() {
            RoverRuntimeState.setCameraPublisherState(false, "RootEncoder RTSP disconnected", "")
            RoverRuntimeState.log("CAMERA RootEncoder RTSP disconnected")
            if (!closed.get()) requestFullRestart("RTSP disconnected")
        }

        override fun onAuthError() {
            RoverRuntimeState.setCameraPublisherState(false, "RootEncoder RTSP auth error", "RTSP authentication error")
            RoverRuntimeState.log("CAMERA RootEncoder RTSP authentication error")
            requestFullRestart("RTSP authentication error")
        }

        override fun onAuthSuccess() {
            RoverRuntimeState.log("CAMERA RootEncoder RTSP authentication success")
        }

        override fun onNewBitrate(bitrate: Long) {
            RoverRuntimeState.setCameraPipelineState(bitrate = bitrate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }

    private fun startStatsThread(rtsp: RtspCamera2) {
        statsThread = Thread({
            val client = rtsp.getStreamClient()
            val startedAt = System.currentTimeMillis()
            var lastSentFrames = -1L
            var lastProgressAt = startedAt

            while (!closed.get()) {
                runCatching {
                    val sentFrames = client.getSentVideoFrames()
                    val sentBytes = client.getBytesSend()
                    val dropped = client.getDroppedVideoFrames()
                    val now = System.currentTimeMillis()

                    RoverRuntimeState.setCameraLibraryCounters(
                        sentFrames = sentFrames,
                        sentBytes = sentBytes,
                        droppedFrames = dropped,
                    )

                    if (sentFrames > lastSentFrames) {
                        lastSentFrames = sentFrames
                        lastProgressAt = now
                    } else if (
                        now - startedAt >= 12_000L &&
                        now - lastProgressAt >= 8_000L
                    ) {
                        requestFullRestart(
                            "no video frame progress for ${now - lastProgressAt}ms " +
                                "(sent=$sentFrames connected=${RoverRuntimeState.cameraPublisherConnected})",
                        )
                    }
                }.onFailure {
                    if (!closed.get()) {
                        RoverRuntimeState.log("CAMERA RootEncoder stats failed: ${it.stackTraceToString()}")
                        requestFullRestart("stats/read failure: ${it.message}")
                    }
                }
                try {
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "roverd-camera-rootencoder-stats").apply {
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
        stream?.let { rtsp ->
            runCatching { rtsp.stopStream() }
        }
        stream = null
        RoverRuntimeState.setCameraPipelineState(running = false, state = "Stopped", error = "")
        RoverRuntimeState.setCameraPublisherState(false, "Stopped", "")
        RoverRuntimeState.log("CAMERA RootEncoder pipeline stopped")
    }
}
