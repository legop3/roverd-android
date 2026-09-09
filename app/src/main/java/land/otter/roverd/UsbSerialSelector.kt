package land.otter.roverd

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.Locale

data class UsbSerialCandidate(
    val key: String,
    val label: String,
    val device: UsbDevice?,
    val portIndex: Int,
)

object UsbSerialSelector {
    const val AUTO = "AUTO"

    fun choices(context: Context): List<UsbSerialCandidate> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val result = ArrayList<UsbSerialCandidate>()
        result += UsbSerialCandidate(AUTO, "AUTO — first supported USB serial adapter", null, 0)
        UsbSerialProber.getDefaultProber().findAllDrivers(manager).forEach { driver ->
            val device = driver.device
            driver.ports.indices.forEach { portIndex ->
                val key = key(device.vendorId, device.productId, portIndex)
                val label = String.format(
                    Locale.US,
                    "%s — %04X:%04X port %d — %s — %s",
                    key,
                    device.vendorId,
                    device.productId,
                    portIndex,
                    driver.javaClass.simpleName,
                    device.deviceName,
                )
                result += UsbSerialCandidate(key, label, device, portIndex)
            }
        }
        return result
    }

    fun key(vendorId: Int, productId: Int, portIndex: Int): String =
        String.format(Locale.US, "%04X:%04X:%d", vendorId, productId, portIndex)

    fun matches(preference: String, device: UsbDevice, portIndex: Int): Boolean =
        preference.equals(AUTO, ignoreCase = true) ||
            preference.equals(key(device.vendorId, device.productId, portIndex), ignoreCase = true)
}
