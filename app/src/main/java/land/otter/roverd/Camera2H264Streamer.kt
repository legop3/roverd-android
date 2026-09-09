package land.otter.roverd

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class Camera2H264Streamer(
    context: Context,
    private val config: RoverConfig,
) : Closeable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraThread = HandlerThread("roverd-camera2").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var drainThread: Thread? = null
    private var publisher: RtspH264Publisher? = null
    private var selectedSize: Size? = null
    private var lastKeyFrameRequestMs = 0L

    fun start() {
        check(!closed.get()) { "Camera streamer is closed" }
        val ids = cameraManager.cameraIdList.toList()
        require(config.cameraId in ids) {
            "Camera ID ${config.cameraId} not found. Available=${ids.joinToString(",")}" 
        }
        val chars = cameraManager.getCameraCharacteristics(config.cameraId)
        selectedSize = chooseSize(chars, config.cameraWidth, config.cameraHeight)
        val size = selectedSize!!
        val encoderName = chooseEncoderName()
        RoverRuntimeState.log(
            "CAMERA start id=${config.cameraId} requested=${config.cameraWidth}x${config.cameraHeight}@${config.cameraFps} " +
                "actual=${size.width}x${size.height} bitrate=${config.cameraBitrate} encoder=$encoderName",
        )
        RoverRuntimeState.setCameraPipelineState(
            running = false,
            state = "Configuring encoder",
            cameraId = config.cameraId,
            encoderName = encoderName,
            width = size.width,
            height = size.height,
            fps = config.cameraFps,
            bitrate = config.cameraBitrate,
            publishUrl = MediaUrl.videoPublishUrl(config),
            error = "",
        )

        val codec = createConfiguredEncoder(encoderName, size)
        encoder = codec
        encoderSurface = codec.createInputSurface()
        codec.start()

        publisher = RtspH264Publisher(MediaUrl.videoPublishUrl(config))
        startDrainThread(codec)
        openCamera(chars, encoderSurface!!)
    }

    private fun chooseSize(chars: CameraCharacteristics, requestedWidth: Int, requestedHeight: Int): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(requestedWidth.coerceAtLeast(16), requestedHeight.coerceAtLeast(16))
        val sizes = map.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(requestedWidth.coerceAtLeast(16), requestedHeight.coerceAtLeast(16))
        sizes.firstOrNull { it.width == requestedWidth && it.height == requestedHeight }?.let { return it }

        val targetAspect = requestedWidth.toDouble() / requestedHeight.coerceAtLeast(1).toDouble()
        val targetArea = requestedWidth.toLong() * requestedHeight.toLong()
        return sizes.minByOrNull { size ->
            val aspect = size.width.toDouble() / size.height.toDouble()
            val area = size.width.toLong() * size.height.toLong()
            (abs(aspect - targetAspect) * 10_000_000.0 + abs(area - targetArea).toDouble()).toLong()
        } ?: sizes.first()
    }

    private fun chooseEncoderName(): String {
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .filter { info ->
                runCatching {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        .colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                }.getOrDefault(false)
            }
        if (codecs.isEmpty()) throw IllegalStateException("No H.264 encoder with Surface input")
        val names = codecs.map { it.name }
        RoverRuntimeState.log("CAMERA H264 encoders=${names.joinToString(",")}")
        return codecs.minByOrNull { softwarePenalty(it.name) }!!.name
    }

    private fun softwarePenalty(name: String): Int {
        val n = name.lowercase()
        return if (n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("c2.google.")) 100 else 0
    }

    private fun createConfiguredEncoder(name: String, size: Size): MediaCodec {
        fun configure(includeProfile: Boolean): MediaCodec {
            val codec = MediaCodec.createByCodecName(name)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, config.cameraBitrate.coerceAtLeast(64_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, config.cameraFps.coerceIn(1, 120))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 4)
                if (includeProfile) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                }
            }
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return codec
            } catch (t: Throwable) {
                runCatching { codec.release() }
                throw t
            }
        }

        return runCatching { configure(true) }
            .onFailure { RoverRuntimeState.log("CAMERA baseline profile configure failed; retrying encoder default: ${it.message}") }
            .getOrElse { configure(false) }
    }

    @Suppress("MissingPermission")
    private fun openCamera(chars: CameraCharacteristics, surface: Surface) {
        RoverRuntimeState.setCameraPipelineState(state = "Opening camera")
        cameraManager.openCamera(
            config.cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed.get()) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    createSession(camera, chars, surface)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    RoverRuntimeState.setCameraPipelineState(running = false, state = "Camera disconnected", error = "Camera device disconnected")
                    RoverRuntimeState.log("CAMERA device disconnected id=${config.cameraId}")
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    RoverRuntimeState.setCameraPipelineState(running = false, state = "Camera error", error = "CameraDevice error=$error")
                    RoverRuntimeState.log("CAMERA device error id=${config.cameraId} code=$error")
                    camera.close()
                }
            },
            cameraHandler,
        )
    }

    private fun createSession(camera: CameraDevice, chars: CameraCharacteristics, surface: Surface) {
        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (closed.get()) {
                        session.close()
                        return
                    }
                    captureSession = session
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            chooseFpsRange(chars, config.cameraFps)?.let {
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                            }
                        }.build()
                        session.setRepeatingRequest(request, null, cameraHandler)
                        RoverRuntimeState.setCameraPipelineState(running = true, state = "Camera + H264 running", error = "")
                        RoverRuntimeState.log("CAMERA capture repeating request active id=${config.cameraId}")
                    } catch (t: Throwable) {
                        RoverRuntimeState.setCameraPipelineState(running = false, state = "Capture start failed", error = t.message ?: t.javaClass.simpleName)
                        RoverRuntimeState.log("CAMERA capture start failure: ${t.stackTraceToString()}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    RoverRuntimeState.setCameraPipelineState(running = false, state = "Capture session failed", error = "Camera capture session configuration failed")
                    RoverRuntimeState.log("CAMERA capture session configuration failed")
                }
            },
            cameraHandler,
        )
    }

    private fun chooseFpsRange(chars: CameraCharacteristics, target: Int): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        if (ranges.isEmpty()) return null
        return ranges.minByOrNull { range ->
            val containsPenalty = if (target in range.lower..range.upper) 0 else 1_000_000
            val fixedPenalty = if (range.lower == target && range.upper == target) 0 else abs(range.upper - range.lower) * 100
            containsPenalty + fixedPenalty + abs(range.upper - target)
        }
    }

    private fun startDrainThread(codec: MediaCodec) {
        drainThread = Thread({
            val info = MediaCodec.BufferInfo()
            while (!closed.get()) {
                try {
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> handleOutputFormat(codec.outputFormat)
                        index >= 0 -> {
                            val buffer = codec.getOutputBuffer(index)
                            if (buffer != null && info.size > 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                buffer.get(bytes)
                                val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                                if (codecConfig) {
                                    updateCodecConfig(bytes)
                                } else {
                                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                    publisher?.enqueue(bytes, info.presentationTimeUs, key)
                                    RoverRuntimeState.recordCameraEncodedFrame(bytes.size, key)
                                }
                            }
                            codec.releaseOutputBuffer(index, false)
                        }
                    }

                    val p = publisher
                    if (p != null && !p.isConnected()) maybeRequestKeyFrame(codec)
                } catch (t: Throwable) {
                    if (!closed.get()) {
                        RoverRuntimeState.setCameraPipelineState(running = false, state = "Encoder drain failed", error = t.message ?: t.javaClass.simpleName)
                        RoverRuntimeState.log("CAMERA encoder drain failure: ${t.stackTraceToString()}")
                    }
                    break
                }
            }
        }, "roverd-h264-drain").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleOutputFormat(format: MediaFormat) {
        val csd0 = byteBufferBytes(format.getByteBuffer("csd-0"))
        val csd1 = byteBufferBytes(format.getByteBuffer("csd-1"))
        val (sps, pps) = H264Nals.codecParameterSets(csd0, csd1)
        if (sps != null && pps != null) {
            publisher?.setCodecConfig(sps, pps)
        } else {
            RoverRuntimeState.log("CAMERA output format missing recognizable SPS/PPS format=$format")
        }
        RoverRuntimeState.log("CAMERA encoder output format=$format")
    }

    private fun updateCodecConfig(bytes: ByteArray) {
        val (sps, pps) = H264Nals.codecParameterSets(bytes)
        if (sps != null && pps != null) publisher?.setCodecConfig(sps, pps)
    }

    private fun byteBufferBytes(buffer: ByteBuffer?): ByteArray {
        if (buffer == null) return ByteArray(0)
        val copy = buffer.duplicate()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }

    private fun maybeRequestKeyFrame(codec: MediaCodec) {
        val now = System.currentTimeMillis()
        if (now - lastKeyFrameRequestMs < 2000) return
        lastKeyFrameRequestMs = now
        runCatching {
            codec.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        RoverRuntimeState.log("CAMERA stopping pipeline")
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        publisher?.close()
        publisher = null
        drainThread?.interrupt()
        drainThread = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { encoderSurface?.release() }
        encoderSurface = null
        cameraThread.quitSafely()
        RoverRuntimeState.setCameraPipelineState(running = false, state = "Stopped", error = "")
    }
}
