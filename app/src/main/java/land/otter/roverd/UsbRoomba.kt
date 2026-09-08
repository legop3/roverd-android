package land.otter.roverd

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class UsbRoomba(
    private val context: Context,
    private val config: RoverConfig,
    private val onSensorFrame: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit = {},
) : Closeable, SerialInputOutputManager.Listener {

    companion object {
        private const val ACTION_USB_PERMISSION = "land.otter.roverd.USB_PERMISSION"
        private const val SENSOR_SILENCE_MS = 5_000L
        private const val SENSOR_RECOVERY_COOLDOWN_MS = 3_000L
        private const val SENSOR_COMMAND_PAUSE_MS = 50L
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val writeLock = Any()
    private val framer = SensorFramer { frame ->
        lastSensorFrameNs = System.nanoTime()
        RoverRuntimeState.recordSensor(frame)
        onSensorFrame(frame)
    }

    @Volatile private var port: UsbSerialPort? = null
    @Volatile private var ioManager: SerialInputOutputManager? = null
    @Volatile private var lastSensorFrameNs = 0L
    @Volatile private var lastSensorRecoveryNs = 0L
    private var brcTask: ScheduledFuture<*>? = null
    private var sensorWatchdogTask: ScheduledFuture<*>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice()
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                        openDevice(device)
                    } else {
                        onStatus("USB permission denied")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connect()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detached = intent.usbDevice()
                    if (detached != null && detached.deviceId == port?.device?.deviceId) {
                        closePort()
                        onStatus("USB serial detached")
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    fun isConnected(): Boolean = port != null

    fun connect() {
        if (port != null) return
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        RoverRuntimeState.log("USB probe found ${drivers.size} supported serial driver(s)")
        val driver = drivers.firstOrNull()
        if (driver == null) {
            onStatus("No supported USB serial adapter found")
            return
        }
        val device = driver.device
        RoverRuntimeState.log(
            "USB candidate name=${device.deviceName} vid=0x${device.vendorId.toString(16)} " +
                "pid=0x${device.productId.toString(16)} driver=${driver.javaClass.simpleName} ports=${driver.ports.size}",
        )
        if (!usbManager.hasPermission(device)) {
            val permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            usbManager.requestPermission(device, permissionIntent)
            onStatus("Waiting for USB permission")
            return
        }
        openDevice(device)
    }

    private fun openDevice(device: UsbDevice) {
        if (port != null) return
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null || driver.ports.isEmpty()) {
            onStatus("USB device is not a supported serial adapter")
            return
        }
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            onStatus("Could not open USB device")
            return
        }
        try {
            val openedPort = driver.ports[0]
            openedPort.open(connection)
            openedPort.setParameters(
                config.baud,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE,
            )
            port = openedPort

            setBrcAsserted(false)

            lastSensorFrameNs = System.nanoTime()
            ioManager = SerialInputOutputManager(openedPort, this).also { it.start() }
            startBrcPulser()
            startSensorWatchdog()

            val description = buildString {
                append(device.deviceName)
                append(" vid=0x${device.vendorId.toString(16)}")
                append(" pid=0x${device.productId.toString(16)}")
                append(" driver=${driver.javaClass.simpleName}")
                append(" baud=${config.baud}")
                append(" BRC=${config.brcLine}/${if (config.brcActiveLow) "active-low" else "active-high"}")
            }
            RoverRuntimeState.setUsbState(true, description)
            onConnectionChanged(true)
            onStatus("USB serial connected: ${device.deviceName} @ ${config.baud}")

            scheduler.schedule(
                { recoverSensorStream("startup") },
                config.brcPulseWidthMs + SENSOR_COMMAND_PAUSE_MS,
                TimeUnit.MILLISECONDS,
            )
        } catch (t: Throwable) {
            runCatching { connection.close() }
            closePort()
            onStatus("USB serial open failed: ${t.message}")
            RoverRuntimeState.log("USB open exception: ${t.stackTraceToString()}")
        }
    }

    fun write(bytes: ByteArray) {
        val p = port ?: throw IllegalStateException("USB serial is not connected")
        synchronized(writeLock) {
            if (p !== port) throw IllegalStateException("USB serial disconnected")
            p.write(bytes, 500)
        }
    }

    fun driveDirect(left: Int, right: Int) {
        val max = config.maxWheelSpeed
        write(RoombaOi.driveDirect(left.coerceIn(-max, max), right.coerceIn(-max, max)))
    }

    fun motorPwm(main: Int, side: Int, vacuum: Int) {
        write(RoombaOi.motorPwm(main.coerceIn(-127, 127), side.coerceIn(-127, 127), vacuum.coerceIn(0, 127)))
    }

    fun startOi() = write(byteArrayOf(RoombaOi.START.toByte()))
    fun seekDock() = write(byteArrayOf(RoombaOi.SEEK_DOCK.toByte()))
    fun startSensorStream(packetIds: ByteArray = RoombaOi.DEFAULT_STREAM_PACKETS) = write(RoombaOi.startSensorStream(packetIds))
    fun setSensorStreamEnabled(enable: Boolean) = write(RoombaOi.pauseResumeSensorStream(enable))

    fun playSong(slot: Int, notes: List<RoombaSongNote>) {
        write(RoombaOi.defineSong(slot, notes))
        write(RoombaOi.playSong(slot))
    }

    fun reconnect() {
        RoverRuntimeState.log("MANUAL USB reconnect")
        closePort()
        connect()
    }

    fun restartSensorStreamNow() {
        scheduler.execute { recoverSensorStream("manual") }
    }

    fun pulseBrcNow() {
        scheduler.execute {
            try {
                pulseBrc("manual")
                onStatus("Manual BRC pulse complete")
            } catch (t: Throwable) {
                onStatus("Manual BRC pulse failed: ${t.message}")
            }
        }
    }

    private fun startBrcPulser() {
        brcTask?.cancel(false)
        brcTask = scheduler.scheduleAtFixedRate(
            {
                try {
                    pulseBrc("periodic")
                } catch (t: Throwable) {
                    if (port != null) onStatus("BRC pulse failed: ${t.message}")
                }
            },
            0,
            config.brcPulseEveryMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun pulseBrc(reason: String) {
        if (port == null) return
        RoverRuntimeState.log(
            "BRC pulse reason=$reason line=${config.brcLine} activeLow=${config.brcActiveLow} widthMs=${config.brcPulseWidthMs}",
        )
        setBrcAsserted(true)
        try {
            Thread.sleep(config.brcPulseWidthMs)
        } finally {
            setBrcAsserted(false)
        }
    }

    private fun startSensorWatchdog() {
        sensorWatchdogTask?.cancel(false)
        sensorWatchdogTask = scheduler.scheduleAtFixedRate(
            {
                val p = port ?: return@scheduleAtFixedRate
                if (p !== port) return@scheduleAtFixedRate
                val now = System.nanoTime()
                val idleMs = TimeUnit.NANOSECONDS.toMillis(now - lastSensorFrameNs)
                val sinceRecoveryMs = TimeUnit.NANOSECONDS.toMillis(now - lastSensorRecoveryNs)
                if (idleMs >= SENSOR_SILENCE_MS && sinceRecoveryMs >= SENSOR_RECOVERY_COOLDOWN_MS) {
                    lastSensorRecoveryNs = now
                    recoverSensorStream("${idleMs}ms silence")
                }
            },
            1,
            1,
            TimeUnit.SECONDS,
        )
    }

    private fun recoverSensorStream(reason: String) {
        if (port == null) return
        try {
            RoverRuntimeState.log("SENSOR recovery start reason=$reason")
            startOi()
            Thread.sleep(SENSOR_COMMAND_PAUSE_MS)
            startSensorStream()
            onStatus("Sensor stream restarted ($reason)")
        } catch (t: Throwable) {
            if (port != null) onStatus("Sensor stream recovery failed: ${t.message}")
            RoverRuntimeState.log("SENSOR recovery exception: ${t.stackTraceToString()}")
        }
    }

    private fun setBrcAsserted(asserted: Boolean) {
        val p = port ?: return
        val controlState = if (config.brcActiveLow) !asserted else asserted
        synchronized(writeLock) {
            if (p !== port) return
            when (config.brcLine) {
                BrcLine.RTS -> p.setRTS(controlState)
                BrcLine.DTR -> p.setDTR(controlState)
            }
        }
    }

    override fun onNewData(data: ByteArray) {
        framer.feed(data)
    }

    override fun onRunError(e: Exception) {
        onStatus("USB serial read error: ${e.message}")
        RoverRuntimeState.log("USB read exception: ${e.stackTraceToString()}")
        closePort()
        runCatching {
            scheduler.schedule({ connect() }, 2, TimeUnit.SECONDS)
        }
    }

    private fun closePort() {
        val p = port
        val wasConnected = p != null

        brcTask?.cancel(false)
        brcTask = null
        sensorWatchdogTask?.cancel(false)
        sensorWatchdogTask = null

        if (p != null) runCatching { setBrcAsserted(false) }
        ioManager?.stop()
        ioManager = null
        port = null
        if (p != null) runCatching { p.close() }

        if (wasConnected) {
            RoverRuntimeState.setUsbState(false)
            onConnectionChanged(false)
        }
    }

    override fun close() {
        closePort()
        scheduler.shutdownNow()
        runCatching { context.unregisterReceiver(receiver) }
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
