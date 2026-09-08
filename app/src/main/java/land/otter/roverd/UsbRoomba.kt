package land.otter.roverd

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.SerialInputOutputManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
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
) : Closeable, SerialInputOutputManager.Listener {

    companion object {
        private const val ACTION_USB_PERMISSION = "land.otter.roverd.USB_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val framer = SensorFramer(onSensorFrame)
    private val writeLock = Any()

    @Volatile private var port: UsbSerialPort? = null
    @Volatile private var ioManager: SerialInputOutputManager? = null
    private var brcTask: ScheduledFuture<*>? = null

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

    fun connect() {
        if (port != null) return
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()
        if (driver == null) {
            onStatus("No supported USB serial adapter found")
            return
        }
        val device = driver.device
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

            // Always put the BRC control output in its inactive state immediately
            // after enumeration/open before the periodic pulse task is started.
            setBrcAsserted(false)

            ioManager = SerialInputOutputManager(openedPort, this).also { it.start() }
            startBrcPulser()
            onStatus("USB serial connected: ${device.deviceName} @ ${config.baud}")
        } catch (t: Throwable) {
            runCatching { connection.close() }
            closePort()
            onStatus("USB serial open failed: ${t.message}")
        }
    }

    fun write(bytes: ByteArray) {
        val p = port ?: throw IllegalStateException("USB serial is not connected")
        synchronized(writeLock) {
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
    fun startSensorStream(packetIds: ByteArray) = write(RoombaOi.startSensorStream(packetIds))

    private fun startBrcPulser() {
        brcTask?.cancel(false)
        brcTask = scheduler.scheduleWithFixedDelay(
            {
                try {
                    pulseBrc()
                } catch (t: Throwable) {
                    onStatus("BRC pulse failed: ${t.message}")
                }
            },
            config.brcPulseEveryMs,
            config.brcPulseEveryMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun pulseBrc() {
        if (port == null) return
        setBrcAsserted(true)
        try {
            Thread.sleep(config.brcPulseWidthMs)
        } finally {
            setBrcAsserted(false)
        }
    }

    private fun setBrcAsserted(asserted: Boolean) {
        val p = port ?: return
        // activeLow describes the physical BRC level desired. For the common
        // cheap adapters used by this project, false corresponds to the low
        // control-line state; the setting is exposed so an adapter can be flipped.
        val controlState = if (config.brcActiveLow) !asserted else asserted
        when (config.brcLine) {
            BrcLine.RTS -> p.setRTS(controlState)
            BrcLine.DTR -> p.setDTR(controlState)
        }
    }

    override fun onNewData(data: ByteArray) {
        framer.feed(data)
    }

    override fun onRunError(e: Exception) {
        onStatus("USB serial read error: ${e.message}")
        closePort()
        scheduler.schedule({ connect() }, 2, TimeUnit.SECONDS)
    }

    private fun closePort() {
        brcTask?.cancel(false)
        brcTask = null
        ioManager?.stop()
        ioManager = null
        val p = port
        port = null
        if (p != null) runCatching { p.close() }
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
