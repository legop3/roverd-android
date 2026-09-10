package land.otter.roverd

import android.app.admin.DeviceAdminReceiver

/**
 * Device-owner entry point for dedicated rover phones.
 *
 * Roverd does not impose user-facing device policies here; device-owner status is used so the
 * long-running rover service can recover a wedged Wi-Fi radio on Android 10+.
 */
class RoverDeviceAdminReceiver : DeviceAdminReceiver()
