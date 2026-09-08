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
