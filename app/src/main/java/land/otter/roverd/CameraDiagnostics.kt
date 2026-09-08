package land.otter.roverd

import android.annotation.TargetApi
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaRecorder
import android.os.Build

object CameraDiagnostics {
    fun cameraIds(context: Context): List<String> =
        if (Build.VERSION.SDK_INT >= 21) camera2Ids(context) else legacyIds()

    fun snapshot(context: Context, selectedId: String): String = buildString {
        appendLine("camera API     : ${if (Build.VERSION.SDK_INT >= 21) "Camera2" else "legacy Camera"}")
        appendLine("selected ID    : ${selectedId.ifBlank { "-" }}")
        appendLine("permission     : ${cameraPermissionState(context)}")
        appendLine()

        if (Build.VERSION.SDK_INT >= 21) {
            append(camera2Snapshot(context))
        } else {
            append(legacySnapshot())
        }
    }

    private fun cameraPermissionState(context: Context): String {
        if (Build.VERSION.SDK_INT < 23) return "install-time permission"
        return if (context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            "GRANTED"
        } else {
            "NOT GRANTED"
        }
    }

    @TargetApi(21)
    private fun camera2Ids(context: Context): List<String> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return runCatching { manager.cameraIdList.toList() }.getOrElse { emptyList() }
    }

    private fun legacyIds(): List<String> =
        runCatching { (0 until Camera.getNumberOfCameras()).map { it.toString() } }.getOrElse { emptyList() }

    @TargetApi(21)
    private fun camera2Snapshot(context: Context): String {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = runCatching { manager.cameraIdList.toList() }.getOrElse { err ->
            return "CAMERA2 inventory failed: ${err.stackTraceToString()}"
        }

        return buildString {
            appendLine("openable IDs   : ${if (ids.isEmpty()) "(none)" else ids.joinToString(", ")}")
            appendLine("camera count   : ${ids.size}")

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

                appendLine("LENS_FACING raw=$facing (${facingName(facing)})")
                appendLine("SENSOR_ORIENTATION=$orientation")
                appendLine("HARDWARE_LEVEL raw=$level (${hardwareLevelName(level)})")
                appendLine("FOCAL_LENGTHS=${formatValue(focalLengths)}")
                appendLine("SENSOR_PHYSICAL_SIZE=${formatValue(sensorSize)}")
                appendLine("ACTIVE_ARRAY=${formatValue(activeArray)}")
                appendLine("MAX_DIGITAL_ZOOM=${formatValue(maxZoom)}")
                appendLine("AE_FPS_RANGES=${formatValue(fpsRanges)}")
                appendLine("CAPABILITIES=${formatValue(capabilities)}")

                if (Build.VERSION.SDK_INT >= 28) {
                    appendLine("PHYSICAL_CAMERA_IDS=${physicalIds(c)}")
                }

                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                if (map != null) {
                    appendLine("OUTPUT MediaCodec=${formatSizes(runCatching { map.getOutputSizes(MediaCodec::class.java) }.getOrNull())}")
                    appendLine("OUTPUT MediaRecorder=${formatSizes(runCatching { map.getOutputSizes(MediaRecorder::class.java) }.getOrNull())}")
                    appendLine("OUTPUT SurfaceTexture=${formatSizes(runCatching { map.getOutputSizes(SurfaceTexture::class.java) }.getOrNull())}")
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

    private fun legacySnapshot(): String = buildString {
        val count = runCatching { Camera.getNumberOfCameras() }.getOrElse { err ->
            appendLine("legacy camera count failed: ${err.stackTraceToString()}")
            return@buildString
        }
        appendLine("openable IDs   : ${(0 until count).joinToString(", ")}")
        appendLine("camera count   : $count")
        for (id in 0 until count) {
            val info = Camera.CameraInfo()
            runCatching { Camera.getCameraInfo(id, info) }
                .onSuccess {
                    appendLine()
                    appendLine("===== CAMERA ID [$id] =====")
                    appendLine("facing raw=${info.facing} (${legacyFacingName(info.facing)})")
                    appendLine("orientation=${info.orientation}")
                    appendLine("NOTE: Android <5.0 exposes only the legacy camera index and CameraInfo here.")
                }
                .onFailure { appendLine("camera $id info failed: ${it.stackTraceToString()}") }
        }
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

    private fun legacyFacingName(value: Int): String = when (value) {
        Camera.CameraInfo.CAMERA_FACING_FRONT -> "FRONT"
        Camera.CameraInfo.CAMERA_FACING_BACK -> "BACK"
        else -> "UNKNOWN"
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

    private fun formatSizes(values: Array<android.util.Size>?): String =
        values?.joinToString(", ") { "${it.width}x${it.height}" } ?: "null/unsupported"

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
