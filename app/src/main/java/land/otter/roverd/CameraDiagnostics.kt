package land.otter.roverd

import android.content.Context
import android.hardware.Camera
import android.os.Build

object CameraDiagnostics {
    fun cameraIds(context: Context): List<String> =
        if (Build.VERSION.SDK_INT >= 21) Camera2Diagnostics.cameraIds(context) else legacyIds()

    fun snapshot(context: Context, selectedId: String): String = buildString {
        appendLine("camera API     : ${if (Build.VERSION.SDK_INT >= 21) "Camera2" else "legacy Camera"}")
        appendLine("selected ID    : ${selectedId.ifBlank { "-" }}")
        appendLine("permission     : ${cameraPermissionState(context)}")
        appendLine()

        if (Build.VERSION.SDK_INT >= 21) {
            append(Camera2Diagnostics.snapshot(context))
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

    private fun legacyIds(): List<String> =
        runCatching { (0 until Camera.getNumberOfCameras()).map { it.toString() } }.getOrElse { emptyList() }

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

    private fun legacyFacingName(value: Int): String = when (value) {
        Camera.CameraInfo.CAMERA_FACING_FRONT -> "FRONT"
        Camera.CameraInfo.CAMERA_FACING_BACK -> "BACK"
        else -> "UNKNOWN"
    }
}
