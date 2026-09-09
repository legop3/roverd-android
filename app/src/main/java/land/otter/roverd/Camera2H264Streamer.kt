package land.otter.roverd

import android.annotation.TargetApi
import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.os.Build
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtsp.RtspCamera2
import com.pedro.rtsp.rtsp.Protocol
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

@TargetApi(21)
class Camera2H264Streamer(
    context: Context,
    private val config: RoverConfig,
) : Closeable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)

    @Volatile
    private var stream: RtspCamera2? = null
    private var statsThread: Thread? = null

    private val publishUrl: String by lazy { MediaUrl.videoPublishUrl(config) }

    fun start() {
        check(Build.VERSION.SDK_INT >= 21) { "RootEncoder Camera2 streaming requires Android 5.0 / API 21+" }
        check(!closed.get()) { "Camera streamer is closed" }

        val rotation = resolveRotation()
        val fps = config.cameraFpsMax.coerceIn(1, 120)
        val rtsp = RtspCamera2(appContext, connectChecker())
        stream = rtsp

        rtsp.getStreamClient().apply {
            setProtocol(Protocol.TCP)
            setReTries(10_000)
            setCheckServerAlive(true)
            setLogs(true)
        }

        // Select the exact Android camera ID before the background camera is opened.
        rtsp.switchCamera(config.cameraId)

        rtsp.setCameraCallbacks(object : CameraCallbacks {
            override fun onCameraOpened() {
                RoverRuntimeState.log("CAMERA RootEncoder camera opened id=${config.cameraId}")
                applyCameraControls(rtsp)
            }

            override fun onCameraChanged(facing: CameraHelper.Facing) {
                RoverRuntimeState.log("CAMERA RootEncoder camera changed id=${rtsp.currentCameraId} facing=$facing")
            }

            override fun onCameraError(error: String) {
                RoverRuntimeState.setCameraPipelineState(running = false, state = "Camera error", error = error)
                RoverRuntimeState.log("CAMERA RootEncoder camera error: $error")
            }

            override fun onCameraDisconnected() {
                RoverRuntimeState.setCameraPipelineState(running = false, state = "Camera disconnected")
                RoverRuntimeState.log("CAMERA RootEncoder camera disconnected")
            }
        })

        rtsp.setFpsListener { actualFps ->
            RoverRuntimeState.setCameraPipelineState(fps = actualFps)
        }

        RoverRuntimeState.setCameraPipelineState(
            running = false,
            state = "Preparing RootEncoder",
            cameraId = config.cameraId,
            encoderName = "RootEncoder / MediaCodec AUTO",
            width = if (rotation == 90 || rotation == 270) config.cameraHeight else config.cameraWidth,
            height = if (rotation == 90 || rotation == 270) config.cameraWidth else config.cameraHeight,
            fps = fps,
            bitrate = config.cameraBitrate,
            publishUrl = publishUrl,
            error = "",
        )

        RoverRuntimeState.log(
            "CAMERA RootEncoder prepare id=${config.cameraId} source=${config.cameraWidth}x${config.cameraHeight} " +
                "fpsCeiling=$fps selectedRange=${config.cameraFpsMin}-${config.cameraFpsMax} bitrate=${config.cameraBitrate} " +
                "rotation=$rotation url=$publishUrl",
        )
        if (!config.cameraEncoderName.equals("AUTO", ignoreCase = true)) {
            RoverRuntimeState.log(
                "CAMERA encoder preference '${config.cameraEncoderName}' ignored; RootEncoder manages MediaCodec selection",
            )
        }

        val prepared = rtsp.prepareVideo(
            config.cameraWidth,
            config.cameraHeight,
            fps,
            config.cameraBitrate.coerceAtLeast(64_000),
            2,
            rotation,
        )
        if (!prepared) {
            throw IllegalStateException(
                "RootEncoder could not prepare H.264 ${config.cameraWidth}x${config.cameraHeight}@$fps",
            )
        }

        rtsp.startStream(publishUrl)
        RoverRuntimeState.setCameraPipelineState(
            running = true,
            state = "RootEncoder camera + H264 running",
            error = "",
        )
        startStatsThread(rtsp)
    }

    private fun resolveRotation(): Int {
        val requested = config.cameraRotation
        if (requested >= 0) return normalizeRotation(requested)

        // AUTO is resolved exactly once when streaming starts. Later Activity/UI auto-rotation
        // cannot change the stream geometry.
        val resolved = CameraHelper.getCameraOrientation(appContext)
        RoverRuntimeState.log("CAMERA AUTO stream rotation resolved once to ${resolved}°")
        return normalizeRotation(resolved)
    }

    private fun normalizeRotation(value: Int): Int =
        (((value % 360) + 360) % 360 / 90) * 90

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
            }
        }

        override fun onDisconnect() {
            RoverRuntimeState.setCameraPublisherState(false, "RootEncoder RTSP disconnected", "")
            RoverRuntimeState.log("CAMERA RootEncoder RTSP disconnected")
        }

        override fun onAuthError() {
            RoverRuntimeState.setCameraPublisherState(false, "RootEncoder RTSP auth error", "RTSP authentication error")
            RoverRuntimeState.log("CAMERA RootEncoder RTSP authentication error")
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
            while (!closed.get()) {
                runCatching {
                    RoverRuntimeState.setCameraLibraryCounters(
                        sentFrames = client.getSentVideoFrames(),
                        sentBytes = client.getBytesSend(),
                        droppedFrames = client.getDroppedVideoFrames(),
                    )
                }.onFailure {
                    if (!closed.get()) RoverRuntimeState.log("CAMERA RootEncoder stats failed: ${it.message}")
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
