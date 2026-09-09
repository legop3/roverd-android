package land.otter.roverd

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object RoverRuntimeState {
    private const val MAX_LOG_LINES = 600
    private const val MAX_LOG_BYTES = 2L * 1024L * 1024L

    @Volatile var status: String = "Stopped"
        private set
    @Volatile var usbConnected: Boolean = false
        private set
    @Volatile var usbDevice: String = ""
        private set
    @Volatile var serverConnected: Boolean = false
        private set
    @Volatile var wakeLockHeld: Boolean = false
        private set
    @Volatile var wifiLockHeld: Boolean = false
        private set
    @Volatile var sensorFrames: Long = 0
        private set
    @Volatile var sensorBytes: Long = 0
        private set
    @Volatile var lastSensorAtMs: Long = 0
        private set
    @Volatile var lastSensorHex: String = ""
        private set
    @Volatile var commandCount: Long = 0
        private set
    @Volatile var lastCommandAtMs: Long = 0
        private set
    @Volatile var lastCommand: String = ""
        private set

    @Volatile var chargingState: Int = -1
        private set
    @Volatile var chargeSources: Int = -1
        private set
    @Volatile var homeBaseDetected: Boolean = false
        private set
    @Volatile var roombaCharging: Boolean = false
        private set
    @Volatile var autoChargeState: String = "no sensor sample"
        private set
    @Volatile var autoChargeTimerActive: Boolean = false
        private set
    @Volatile var autoChargeSeekCount: Long = 0
        private set
    @Volatile var lastAutoChargeAtMs: Long = 0
        private set

    @Volatile var cameraRunning: Boolean = false
        private set
    @Volatile var cameraState: String = "Stopped"
        private set
    @Volatile var cameraId: String = ""
        private set
    @Volatile var cameraEncoderName: String = ""
        private set
    @Volatile var cameraWidth: Int = 0
        private set
    @Volatile var cameraHeight: Int = 0
        private set
    @Volatile var cameraFps: Int = 0
        private set
    @Volatile var cameraBitrate: Int = 0
        private set
    @Volatile var cameraPublishUrl: String = ""
        private set
    @Volatile var cameraPublisherConnected: Boolean = false
        private set
    @Volatile var cameraPublisherState: String = "Stopped"
        private set
    @Volatile var cameraLastError: String = ""
        private set
    @Volatile var cameraEncodedFrames: Long = 0
        private set
    @Volatile var cameraEncodedBytes: Long = 0
        private set
    @Volatile var cameraPublishedFrames: Long = 0
        private set
    @Volatile var cameraPublishedBytes: Long = 0
        private set
    @Volatile var cameraDroppedFrames: Long = 0
        private set
    @Volatile var cameraReconnects: Long = 0
        private set
    @Volatile var lastCameraEncodedAtMs: Long = 0
        private set
    @Volatile var lastCameraPublishedAtMs: Long = 0
        private set
    @Volatile var lastCameraKeyFrameAtMs: Long = 0
        private set

    private val statusListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val logListeners = CopyOnWriteArrayList<() -> Unit>()
    private val logLock = Any()
    private val logLines = ArrayDeque<String>()
    @Volatile private var logFile: File? = null
    @Volatile private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(logLock) {
            if (initialized) return
            val file = File(context.applicationContext.filesDir, "roverd.log")
            logFile = file
            if (file.exists()) {
                runCatching {
                    file.readLines().takeLast(MAX_LOG_LINES).forEach { logLines.addLast(it) }
                }
            }
            initialized = true
        }
        log("PROCESS started; persistent log=${logFile?.absolutePath}")
    }

    fun update(value: String) {
        status = value
        log("STATUS $value")
        statusListeners.forEach { it(value) }
    }

    fun log(value: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$timestamp  $value"
        synchronized(logLock) {
            logLines.addLast(line)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
            appendPersistent(line)
        }
        logListeners.forEach { it() }
    }

    fun setUsbState(connected: Boolean, description: String = "") {
        usbConnected = connected
        usbDevice = if (connected) description else ""
        log("USB connected=$connected${if (description.isNotBlank()) " device=[$description]" else ""}")
    }

    fun setServerState(connected: Boolean) {
        serverConnected = connected
        log("SERVER connected=$connected")
    }

    fun setPowerLocks(wakeHeld: Boolean, wifiHeld: Boolean) {
        wakeLockHeld = wakeHeld
        wifiLockHeld = wifiHeld
        log("POWER locks wake=$wakeHeld wifi=$wifiHeld")
    }

    fun setChargeState(
        chargingState: Int,
        chargeSources: Int,
        homeBaseDetected: Boolean,
        charging: Boolean,
    ) {
        this.chargingState = chargingState
        this.chargeSources = chargeSources
        this.homeBaseDetected = homeBaseDetected
        roombaCharging = charging
    }

    fun setAutoChargeState(state: String, timerActive: Boolean) {
        autoChargeState = state
        autoChargeTimerActive = timerActive
    }

    fun recordAutoChargeSeek(state: String) {
        autoChargeSeekCount += 1
        lastAutoChargeAtMs = System.currentTimeMillis()
        autoChargeState = state
        autoChargeTimerActive = false
        log("AUTOCHARGE $state count=$autoChargeSeekCount")
    }

    fun setCameraPipelineState(
        running: Boolean? = null,
        state: String? = null,
        cameraId: String? = null,
        encoderName: String? = null,
        width: Int? = null,
        height: Int? = null,
        fps: Int? = null,
        bitrate: Int? = null,
        publishUrl: String? = null,
        error: String? = null,
    ) {
        running?.let { cameraRunning = it }
        state?.let { cameraState = it }
        cameraId?.let { this.cameraId = it }
        encoderName?.let { cameraEncoderName = it }
        width?.let { cameraWidth = it }
        height?.let { cameraHeight = it }
        fps?.let { cameraFps = it }
        bitrate?.let { cameraBitrate = it }
        publishUrl?.let { cameraPublishUrl = it }
        error?.let { cameraLastError = it }
    }

    fun setCameraPublisherState(connected: Boolean, state: String, error: String = "") {
        cameraPublisherConnected = connected
        cameraPublisherState = state
        if (error.isNotEmpty()) cameraLastError = error
    }

    fun recordCameraEncodedFrame(bytes: Int, keyFrame: Boolean) {
        cameraEncodedFrames += 1
        cameraEncodedBytes += bytes
        lastCameraEncodedAtMs = System.currentTimeMillis()
        if (keyFrame) lastCameraKeyFrameAtMs = lastCameraEncodedAtMs
    }

    fun recordCameraPublishedFrame(bytes: Int) {
        cameraPublishedFrames += 1
        cameraPublishedBytes += bytes
        lastCameraPublishedAtMs = System.currentTimeMillis()
    }

    fun recordCameraDrop() {
        cameraDroppedFrames += 1
    }

    fun recordCameraReconnect() {
        cameraReconnects += 1
    }

    fun resetCameraCounters() {
        cameraEncodedFrames = 0
        cameraEncodedBytes = 0
        cameraPublishedFrames = 0
        cameraPublishedBytes = 0
        cameraDroppedFrames = 0
        cameraReconnects = 0
        lastCameraEncodedAtMs = 0
        lastCameraPublishedAtMs = 0
        lastCameraKeyFrameAtMs = 0
    }

    fun recordSensor(frame: ByteArray) {
        sensorFrames += 1
        sensorBytes += frame.size
        lastSensorAtMs = System.currentTimeMillis()
        lastSensorHex = frame.take(96).joinToString(" ") { "%02X".format(it.toInt() and 0xff) } +
            if (frame.size > 96) " ... (${frame.size} bytes)" else " (${frame.size} bytes)"
    }

    fun recordCommand(raw: String) {
        commandCount += 1
        lastCommandAtMs = System.currentTimeMillis()
        lastCommand = raw.take(1500)
    }

    fun logSnapshot(): String = synchronized(logLock) { logLines.joinToString("\n") }

    fun clearLog() {
        synchronized(logLock) {
            logLines.clear()
            runCatching { logFile?.writeText("") }
        }
        log("LOG cleared from UI")
    }

    fun addListener(listener: (String) -> Unit) {
        statusListeners += listener
        listener(status)
    }

    fun removeListener(listener: (String) -> Unit) {
        statusListeners -= listener
    }

    fun addLogListener(listener: () -> Unit) {
        logListeners += listener
        listener()
    }

    fun removeLogListener(listener: () -> Unit) {
        logListeners -= listener
    }

    private fun appendPersistent(line: String) {
        val file = logFile ?: return
        runCatching {
            if (file.exists() && file.length() >= MAX_LOG_BYTES) {
                val old = File(file.parentFile, "roverd.log.1")
                if (old.exists()) old.delete()
                file.renameTo(old)
            }
            file.appendText(line + "\n")
        }
    }
}
