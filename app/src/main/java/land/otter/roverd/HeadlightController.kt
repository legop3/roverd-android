package land.otter.roverd

import android.annotation.TargetApi
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

/**
 * Maps the rover's logical headlight to the phone's camera LED torch.
 *
 * If the flash belongs to the currently-open Camera2 source, use that source's
 * capture request so the video stream is not interrupted. Otherwise use
 * CameraManager.setTorchMode() on API 23+.
 */
object HeadlightController {
    private val lock = Any()

    @Volatile private var appContext: Context? = null
    @Volatile private var requestedOn: Boolean = false
    @Volatile private var activeSource: RoverCamera2Source? = null
    @Volatile private var activeSourceId: String = ""
    @Volatile private var lastDriver: String = "none"
    @Volatile private var lastError: String = ""

    fun initialize(context: Context) {
        appContext = context.applicationContext
        RoverRuntimeState.log("HEADLIGHT initialized available=${isAvailable()}")
    }

    fun isAvailable(): Boolean {
        val context = appContext ?: return false
        if (Build.VERSION.SDK_INT < 21) return false
        return runCatching { flashCameraIds(context).isNotEmpty() }
            .getOrElse {
                RoverRuntimeState.log("HEADLIGHT capability check failed: ${it.message}")
                false
            }
    }

    fun isOn(): Boolean = requestedOn

    fun handleAction(action: String, preferredCameraId: String = "") {
        val next = synchronized(lock) {
            when (action.trim().lowercase()) {
                "", "toggle" -> !requestedOn
                "on" -> true
                "off" -> false
                else -> throw IllegalArgumentException("unknown headlight action '$action'")
            }
        }
        set(next, preferredCameraId)
    }

    fun set(on: Boolean, preferredCameraId: String = "") {
        synchronized(lock) {
            val context = appContext ?: throw IllegalStateException("headlight controller not initialized")
            if (Build.VERSION.SDK_INT < 21) throw UnsupportedOperationException("phone flashlight requires Android 5.0+")

            val flashIds = flashCameraIds(context)
            if (flashIds.isEmpty()) throw UnsupportedOperationException("phone has no Camera2 flash/torch")

            requestedOn = on
            val preferred = preferredCameraId.takeIf { it in flashIds }
            val source = activeSource
            val sourceId = activeSourceId

            try {
                if (source != null && source.isRunning() && sourceId in flashIds && (preferred == null || sourceId == preferred)) {
                    source.setTorch(on)
                    lastDriver = "active-camera:$sourceId"
                    lastError = ""
                    RoverRuntimeState.log("HEADLIGHT ${if (on) "ON" else "OFF"} via active Camera2 source id=$sourceId")
                    return
                }

                if (Build.VERSION.SDK_INT >= 23) {
                    val id = preferred ?: flashIds.first()
                    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    manager.setTorchMode(id, on)
                    lastDriver = "system-torch:$id"
                    lastError = ""
                    RoverRuntimeState.log("HEADLIGHT ${if (on) "ON" else "OFF"} via CameraManager id=$id")
                    return
                }

                // On API 21-22 there is no CameraManager.setTorchMode(). We can still
                // control the LED without reopening the camera when the active stream owns it.
                throw UnsupportedOperationException("on Android 5.0/5.1 the flashlight requires the flash camera to be actively streaming")
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                RoverRuntimeState.log("HEADLIGHT set failed on=$on preferred=$preferredCameraId: ${t.stackTraceToString()}")
                throw t
            }
        }
    }

    fun registerCameraSource(source: RoverCamera2Source, cameraId: String) {
        synchronized(lock) {
            activeSource = source
            activeSourceId = cameraId
            RoverRuntimeState.log("HEADLIGHT registered active camera source id=$cameraId requestedOn=$requestedOn")
        }
    }

    fun onCameraOpened(source: RoverCamera2Source, cameraId: String) {
        synchronized(lock) {
            if (activeSource !== source) return
            activeSourceId = cameraId
            if (!requestedOn) return

            val context = appContext ?: return
            val flashIds = runCatching { flashCameraIds(context) }.getOrDefault(emptyList())
            if (cameraId !in flashIds) return

            runCatching { source.setTorch(true) }
                .onSuccess {
                    lastDriver = "active-camera:$cameraId"
                    lastError = ""
                    RoverRuntimeState.log("HEADLIGHT reapplied ON after camera opened id=$cameraId")
                }
                .onFailure {
                    lastError = it.message ?: it.javaClass.simpleName
                    RoverRuntimeState.log("HEADLIGHT reapply after camera open failed: ${it.stackTraceToString()}")
                }
        }
    }

    fun unregisterCameraSource(source: RoverCamera2Source) {
        synchronized(lock) {
            if (activeSource !== source) return
            val oldId = activeSourceId
            activeSource = null
            activeSourceId = ""
            RoverRuntimeState.log("HEADLIGHT unregistered camera source id=$oldId requestedOn=$requestedOn")
        }
    }

    fun snapshot(): String {
        val context = appContext
        val flashIds = if (context != null && Build.VERSION.SDK_INT >= 21) {
            runCatching { flashCameraIds(context) }.getOrDefault(emptyList())
        } else emptyList()
        return buildString {
            appendLine("available     : ${flashIds.isNotEmpty()}")
            appendLine("flash IDs     : ${if (flashIds.isEmpty()) "none" else flashIds.joinToString()}")
            appendLine("requested on  : $requestedOn")
            appendLine("active camera : ${activeSourceId.ifBlank { "none" }}")
            appendLine("driver        : $lastDriver")
            append("last error    : ${lastError.ifBlank { "-" }}")
        }
    }

    @TargetApi(21)
    private fun flashCameraIds(context: Context): List<String> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return manager.cameraIdList.filter { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }
}
