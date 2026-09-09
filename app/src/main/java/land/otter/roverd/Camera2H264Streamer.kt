package land.otter.roverd

import android.annotation.TargetApi
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
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

@TargetApi(21)
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
    private var glBridge: CameraGlBridge? = null
    private var drainThread: Thread? = null
    private var publisher: RtspH264Publisher? = null
    private var selectedCaptureSize: Size? = null
    private var selectedOutputSize: Size? = null
    private var selectedRotation: Int = 0
    private var selectedFpsRange: Range<Int>? = null
    private var lastKeyFrameRequestMs = 0L
    private var lastCaptureLogMs = 0L

    fun start() {
        check(!closed.get()) { "Camera streamer is closed" }
        val ids = cameraManager.cameraIdList.toList()
        require(config.cameraId in ids) {
            "Camera ID ${config.cameraId} not found. Available=${ids.joinToString(",")}" 
        }
        val chars = cameraManager.getCameraCharacteristics(config.cameraId)
        selectedCaptureSize = chooseSize(chars, config.cameraWidth, config.cameraHeight)
        val captureSize = selectedCaptureSize!!
        selectedRotation = resolveRotation(chars)
        val outputSize = if (selectedRotation == 90 || selectedRotation == 270) {
            Size(captureSize.height, captureSize.width)
        } else {
            captureSize
        }
        selectedOutputSize = outputSize
        selectedFpsRange = chooseFpsRange(chars)
        val encoderName = chooseEncoderName(outputSize)
        RoverRuntimeState.log(
            "CAMERA start id=${config.cameraId} capture=${captureSize.width}x${captureSize.height} " +
                "output=${outputSize.width}x${outputSize.height} rotation=$selectedRotation " +
                "fps=${selectedFpsRange ?: "auto"} exposureComp=${config.cameraExposureCompensation} " +
                "bitrate=${config.cameraBitrate} encoder=$encoderName",
        )
        RoverRuntimeState.setCameraPipelineState(
            running = false,
            state = "Configuring encoder",
            cameraId = config.cameraId,
            encoderName = encoderName,
            width = outputSize.width,
            height = outputSize.height,
            fps = selectedFpsRange?.upper ?: config.cameraFps,
            bitrate = config.cameraBitrate,
            publishUrl = MediaUrl.videoPublishUrl(config),
            error = "",
        )

        val codec = createConfiguredEncoder(encoderName, outputSize)
        encoder = codec
        encoderSurface = codec.createInputSurface()
        codec.start()

        glBridge = CameraGlBridge(
            inputWidth = captureSize.width,
            inputHeight = captureSize.height,
            outputWidth = outputSize.width,
            outputHeight = outputSize.height,
            rotationDegrees = selectedRotation,
            outputSurface = encoderSurface!!,
        )

        publisher = RtspH264Publisher(MediaUrl.videoPublishUrl(config))
        startDrainThread(codec)
        openCamera(chars, glBridge!!.cameraSurface)
    }

    private fun chooseSize(chars: CameraCharacteristics, requestedWidth: Int, requestedHeight: Int): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(requestedWidth.coerceAtLeast(16), requestedHeight.coerceAtLeast(16))
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
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

    private fun resolveRotation(chars: CameraCharacteristics): Int {
        val requested = config.cameraRotation
        val raw = if (requested < 0) chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0 else requested
        return (((raw % 360) + 360) % 360 / 90) * 90
    }

    private fun availableEncoders(): List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .filter { info ->
                runCatching {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        .colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                }.getOrDefault(false)
            }

    private fun chooseEncoderName(size: Size): String {
        val codecs = availableEncoders()
        if (codecs.isEmpty()) throw IllegalStateException("No H.264 encoder with Surface input")
        val names = codecs.map { it.name }
        RoverRuntimeState.log("CAMERA H264 encoders=${names.joinToString(",")}")

        if (!config.cameraEncoderName.equals("AUTO", true)) {
            val explicit = codecs.firstOrNull { it.name == config.cameraEncoderName }
                ?: throw IllegalStateException("Configured H.264 encoder not found: ${config.cameraEncoderName}")
            return explicit.name
        }

        return codecs.minByOrNull { info ->
            val software = softwarePenalty(info.name)
            val sizePenalty = runCatching {
                val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                if (caps.isSizeSupported(size.width, size.height)) 0 else 10_000
            }.getOrDefault(0)
            software + sizePenalty
        }!!.name
    }

    private fun softwarePenalty(name: String): Int {
        val n = name.lowercase()
        return if (n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("c2.google.")) 100 else 0
    }

    private fun createConfiguredEncoder(name: String, size: Size): MediaCodec {
        val codec = MediaCodec.createByCodecName(name)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.cameraBitrate.coerceAtLeast(64_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, (selectedFpsRange?.upper ?: config.cameraFps).coerceIn(1, 120))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 4)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            RoverRuntimeState.log("CAMERA encoder configured name=$name format=$format")
            return codec
        } catch (t: Throwable) {
            runCatching { codec.release() }
            throw t
        }
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
                        val request = buildCaptureRequest(camera, chars, surface)
                        session.setRepeatingRequest(
                            request,
                            object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(
                                    session: CameraCaptureSession,
                                    request: CaptureRequest,
                                    result: TotalCaptureResult,
                                ) {
                                    logCaptureState(result)
                                }
                            },
                            cameraHandler,
                        )
                        RoverRuntimeState.setCameraPipelineState(running = true, state = "Camera + GL + H264 running", error = "")
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

    private fun buildCaptureRequest(camera: CameraDevice, chars: CameraCharacteristics, surface: Surface): CaptureRequest {
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        builder.addTarget(surface)
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        val aeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf()
        if (CaptureRequest.CONTROL_AE_MODE_ON in aeModes) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        if (chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true) {
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        }
        val compRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        if (compRange != null) {
            builder.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                config.cameraExposureCompensation.coerceIn(compRange.lower, compRange.upper),
            )
        }
        selectedFpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }

        val awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        if (CaptureRequest.CONTROL_AWB_MODE_AUTO in awbModes) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }
        if (chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        }

        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        when {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            CaptureRequest.CONTROL_AF_MODE_AUTO in afModes ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        }

        val effects = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS) ?: intArrayOf()
        if (CaptureRequest.CONTROL_EFFECT_MODE_OFF in effects) {
            builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
        }

        RoverRuntimeState.log(
            "CAMERA request AE=ON AWB=AUTO AF=${when {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes -> "CONTINUOUS_VIDEO"
                CaptureRequest.CONTROL_AF_MODE_AUTO in afModes -> "AUTO"
                else -> "device default"
            }} effect=OFF fps=${selectedFpsRange ?: "device default"} exposureComp=${config.cameraExposureCompensation}",
        )
        return builder.build()
    }

    private fun chooseFpsRange(chars: CameraCharacteristics): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        if (ranges.isEmpty()) return null

        if (config.cameraFpsMin >= 0) {
            ranges.firstOrNull { it.lower == config.cameraFpsMin && it.upper == config.cameraFpsMax }?.let { return it }
        }

        val targetMax = config.cameraFpsMax.coerceAtLeast(1)
        return ranges.minByOrNull { range ->
            val maxPenalty = abs(range.upper - targetMax) * 10_000
            val tooHighPenalty = if (range.upper > targetMax) 1_000 else 0
            val lowMinBonus = range.lower
            maxPenalty + tooHighPenalty + lowMinBonus
        }
    }

    private fun logCaptureState(result: TotalCaptureResult) {
        val now = System.currentTimeMillis()
        if (now - lastCaptureLogMs < 2000) return
        lastCaptureLogMs = now
        val ae = result.get(CaptureResult.CONTROL_AE_STATE)
        val awb = result.get(CaptureResult.CONTROL_AWB_STATE)
        val af = result.get(CaptureResult.CONTROL_AF_STATE)
        val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val frameDuration = result.get(CaptureResult.SENSOR_FRAME_DURATION)
        RoverRuntimeState.log(
            "CAMERA capture AE=$ae AWB=$awb AF=$af exposureNs=$exposureNs ISO=$iso frameDurationNs=$frameDuration",
        )
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
        glBridge?.close()
        glBridge = null
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
