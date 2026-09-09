package land.otter.roverd

import android.annotation.TargetApi
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build
import android.util.Size
import kotlin.math.roundToInt

@TargetApi(21)
object Camera2Diagnostics {
    fun cameraIds(context: Context): List<String> = cameraChoices(context).map { it.id }

    fun cameraChoices(context: Context): List<CameraChoice> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return runCatching {
            manager.cameraIdList.map { id ->
                val c = manager.getCameraCharacteristics(id)
                val facing = facingName(c.get(CameraCharacteristics.LENS_FACING))
                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.joinToString("/") { formatDecimal(it) }
                    .orEmpty()
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val largest = map?.getOutputSizes(SurfaceTexture::class.java)
                    ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                val lens = if (focal.isBlank()) "focal ?" else "${focal}mm"
                val size = largest?.let { "max ${it.width}x${it.height}" } ?: "size ?"
                CameraChoice(
                    id,
                    "ID $id — $facing — $lens — $size",
                )
            }
        }.getOrElse { emptyList() }
    }

    fun modeCatalog(context: Context, cameraId: String): CameraModeCatalog? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return runCatching {
            val c = manager.getCameraCharacteristics(cameraId)
            val facing = facingName(c.get(CameraCharacteristics.LENS_FACING))
            val orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            val rawSizes = map?.getOutputSizes(SurfaceTexture::class.java)
                ?.map { CameraSizeOption(it.width, it.height) }
                ?.distinctBy { it.width to it.height }
                ?.sortedWith(compareByDescending<CameraSizeOption> { it.width.toLong() * it.height }.thenByDescending { it.width })
                .orEmpty()
            val encoders = h264SurfaceEncoderInfos()
            val encodableSizes = rawSizes.filter { size ->
                encoders.any { info -> supportsAvcSize(info, size.width, size.height) }
            }
            // Some vendor codecs report incomplete VideoCapabilities. Never make the camera unusable
            // just because its codec metadata is broken; fall back to the raw SurfaceTexture list.
            val sizes = encodableSizes.ifEmpty { rawSizes }

            val fps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { CameraFpsOption(it.lower, it.upper) }
                ?.distinctBy { it.min to it.max }
                ?.sortedWith(compareBy<CameraFpsOption> { it.max }.thenBy { it.min })
                .orEmpty()
            val exposureRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val exposureStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            val effects = c.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS)
                ?.map { effectName(it) }
                .orEmpty()
            val label = cameraChoices(context).firstOrNull { it.id == cameraId }?.label ?: cameraId
            CameraModeCatalog(
                id = cameraId,
                label = label,
                facing = facing,
                sensorOrientation = orientation,
                sizes = sizes,
                fpsRanges = fps,
                exposureCompMin = exposureRange?.lower ?: 0,
                exposureCompMax = exposureRange?.upper ?: 0,
                exposureCompStep = exposureStep?.toFloat() ?: 0f,
                effectModes = effects,
            )
        }.getOrNull()
    }

    fun h264SurfaceEncoders(): List<String> = h264SurfaceEncoderInfos().map { it.name }.distinct()

    private fun h264SurfaceEncoderInfos(): List<MediaCodecInfo> = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals("video/avc", true) } }
            .filter { info ->
                runCatching {
                    info.getCapabilitiesForType("video/avc")
                        .colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                }.getOrDefault(false)
            }
    }.getOrElse { emptyList() }

    private fun supportsAvcSize(info: MediaCodecInfo, width: Int, height: Int): Boolean = runCatching {
        info.getCapabilitiesForType("video/avc").videoCapabilities.isSizeSupported(width, height)
    }.getOrDefault(false)

    fun snapshot(context: Context): String {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = runCatching { manager.cameraIdList.toList() }.getOrElse { err ->
            return "CAMERA2 inventory failed: ${err.stackTraceToString()}"
        }

        return buildString {
            appendLine("openable IDs   : ${if (ids.isEmpty()) "(none)" else ids.joinToString(", ")}")
            appendLine("camera count   : ${ids.size}")
            appendLine("NOTE           : Camera IDs are opaque Android identifiers, not lens numbers or zoom factors.")
            appendLine("NOTE           : SENSOR_ORIENTATION is sensor mounting metadata, not a stream-rotation command.")
            appendLine("H264 encoders  : ${h264SurfaceEncoders().joinToString().ifBlank { "(none reported)" }}")

            for (id in ids) {
                appendLine()
                appendLine("===== CAMERA ID [$id] =====")
                val c = runCatching { manager.getCameraCharacteristics(id) }.getOrElse { err ->
                    appendLine("characteristics failed: ${err.stackTraceToString()}")
                    continue
                }

                val facing = c.get(CameraCharacteristics.LENS_FACING)
                val orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION)
                val level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                val focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val sensorSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val maxZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                val fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                val effects = c.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS)
                val exposureRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                val exposureStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)

                appendLine("LENS_FACING raw=$facing (${facingName(facing)})")
                appendLine("SENSOR_MOUNT_ROTATION=$orientation degrees from phone natural orientation")
                appendLine("HARDWARE_LEVEL raw=$level (${hardwareLevelName(level)})")
                appendLine("FOCAL_LENGTHS=${formatValue(focalLengths)}")
                appendLine("SENSOR_PHYSICAL_SIZE=${formatValue(sensorSize)}")
                appendLine("ACTIVE_ARRAY=${formatValue(activeArray)}")
                appendLine("MAX_DIGITAL_ZOOM=${formatValue(maxZoom)}")
                appendLine("AE_FPS_RANGES=${formatValue(fpsRanges)}")
                appendLine("AE_COMP_RANGE=${formatValue(exposureRange)} step=${formatValue(exposureStep)}")
                appendLine("EFFECTS=${effects?.joinToString { effectName(it) } ?: "null"}")
                appendLine("CAPABILITIES=${formatValue(capabilities)}")

                if (Build.VERSION.SDK_INT >= 28) {
                    appendLine("PHYSICAL_CAMERA_IDS=${physicalIds(c)}")
                }

                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                if (map != null) {
                    val surfaceTextureSizes = runCatching { map.getOutputSizes(SurfaceTexture::class.java) }.getOrNull()
                    appendLine("OUTPUT MediaCodec=${formatSizes(runCatching { map.getOutputSizes(MediaCodec::class.java) }.getOrNull())}")
                    appendLine("OUTPUT MediaRecorder=${formatSizes(runCatching { map.getOutputSizes(MediaRecorder::class.java) }.getOrNull())}")
                    appendLine("OUTPUT SurfaceTexture=${formatSizes(surfaceTextureSizes)}")
                    appendLine("H264-ENCODABLE SurfaceTexture=${formatEncodableSizes(surfaceTextureSizes)}")
                } else {
                    appendLine("SCALER_STREAM_CONFIGURATION_MAP=null")
                }

                appendLine("--- ALL CAMERA CHARACTERISTICS ---")
                for (key in c.keys.sortedBy { it.name }) {
                    @Suppress("UNCHECKED_CAST")
                    val value = runCatching { c.get(key as CameraCharacteristics.Key<Any>) }
                        .getOrElse { "<read failed: ${it.message}>" }
                    appendLine("${key.name} = ${formatValue(value)}")
                }
            }
        }
    }

    private fun formatEncodableSizes(values: Array<Size>?): String {
        if (values == null) return "null/unsupported"
        val encoders = h264SurfaceEncoderInfos()
        return values
            .filter { size -> encoders.any { supportsAvcSize(it, size.width, size.height) } }
            .joinToString(", ") { "${it.width}x${it.height}" }
            .ifBlank { "(none reported by codec capabilities)" }
    }

    @TargetApi(28)
    private fun physicalIds(c: CameraCharacteristics): String =
        runCatching { c.physicalCameraIds.joinToString(", ").ifBlank { "(none exposed)" } }
            .getOrElse { "<failed: ${it.message}>" }

    private fun facingName(value: Int?): String = when (value) {
        0 -> "FRONT"
        1 -> "BACK"
        2 -> "EXTERNAL"
        null -> "UNKNOWN/null"
        else -> "UNKNOWN"
    }

    private fun effectName(value: Int): String = when (value) {
        0 -> "OFF"
        1 -> "MONO"
        2 -> "NEGATIVE"
        3 -> "SOLARIZE"
        4 -> "SEPIA"
        5 -> "POSTERIZE"
        6 -> "WHITEBOARD"
        7 -> "BLACKBOARD"
        8 -> "AQUA"
        else -> "UNKNOWN($value)"
    }

    private fun hardwareLevelName(value: Int?): String = when (value) {
        2 -> "LEGACY"
        0 -> "LIMITED"
        1 -> "FULL"
        3 -> "LEVEL_3"
        4 -> "EXTERNAL"
        null -> "UNKNOWN/null"
        else -> "UNKNOWN"
    }

    private fun formatSizes(values: Array<Size>?): String =
        values?.joinToString(", ") { "${it.width}x${it.height}" } ?: "null/unsupported"

    private fun formatDecimal(value: Float): String {
        val hundredths = (value * 100f).roundToInt()
        return if (hundredths % 100 == 0) (hundredths / 100).toString()
        else if (hundredths % 10 == 0) String.format(java.util.Locale.US, "%.1f", value)
        else String.format(java.util.Locale.US, "%.2f", value)
    }

    private fun formatValue(value: Any?): String = when (value) {
        null -> "null"
        is ByteArray -> value.joinToString(prefix = "[", postfix = "]") { (it.toInt() and 0xff).toString() }
        is ShortArray -> value.joinToString(prefix = "[", postfix = "]")
        is IntArray -> value.joinToString(prefix = "[", postfix = "]")
        is LongArray -> value.joinToString(prefix = "[", postfix = "]")
        is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
        is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
        is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
        is CharArray -> value.joinToString(prefix = "[", postfix = "]")
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { formatValue(it) }
        else -> value.toString()
    }
}
