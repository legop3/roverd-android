package land.otter.roverd

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class RoombaSettingsView(context: Context) : ScrollView(context) {
    private val activity = context as Activity
    private val handler = Handler(Looper.getMainLooper())
    private val runtimeView = diagnostic(11f)

    private val usbView = Spinner(context)
    private var usbChoices: List<UsbSerialCandidate> = emptyList()
    private val baudView = EditText(context)
    private val maxWheelView = EditText(context)
    private val batteryFullView = EditText(context)
    private val batteryWarnView = EditText(context)
    private val batteryUrgentView = EditText(context)
    private val autoSideEnabledView = CheckBox(context)
    private val autoSideSpeedView = EditText(context)
    private val brcLineView = Spinner(context)
    private val brcActiveLowView = CheckBox(context)
    private val brcEveryView = EditText(context)
    private val brcWidthView = EditText(context)

    private val privateEnabledView = CheckBox(context)
    private val speedLimitEnabledView = CheckBox(context)
    private val speedLimitMaxView = EditText(context)
    private val overcurrentEnabledView = CheckBox(context)
    private val overcurrentStopView = EditText(context)
    private val bumpEnabledView = CheckBox(context)
    private val bumpSpeedView = EditText(context)
    private val bumpMsView = EditText(context)
    private val cliffEnabledView = CheckBox(context)
    private val cliffSpeedView = EditText(context)
    private val cliffMsView = EditText(context)
    private val virtualWallEnabledView = CheckBox(context)
    private val virtualWallSpeedView = EditText(context)
    private val virtualWallMsView = EditText(context)
    private val cooldownView = EditText(context)

    private val refreshTask = object : Runnable {
        override fun run() {
            refreshRuntime()
            handler.postDelayed(this, 750L)
        }
    }

    init {
        isFillViewport = true
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        addView(root)

        root.addView(TextView(context).apply {
            text = "ROVERD ANDROID / ROOMBA / USB SERIAL / OI"
            textSize = 21f
            typeface = Typeface.MONOSPACE
        })
        root.addView(runtimeView)

        section(root, "SERIAL / OI RECOVERY")
        button(root, "RECONNECT USB SERIAL") { signalRover(RoverService.ACTION_RECONNECT_USB) }
        button(root, "RESTART SENSOR STREAM") { signalRover(RoverService.ACTION_RESTART_SENSOR_STREAM) }
        button(root, "PULSE BRC NOW") { signalRover(RoverService.ACTION_PULSE_BRC) }
        button(root, "REFRESH USB ADAPTER LIST") { refreshUsbChoices() }

        val cfg = RoverSettings.load(context)
        section(root, "USB SERIAL / DRIVE")
        label(root, "USB serial adapter")
        root.addView(usbView)
        refreshUsbChoices(cfg.usbSerialPreference)
        field(root, "Serial baud", baudView, cfg.baud.toString())
        field(root, "Max wheel speed (mm/s, 1..500)", maxWheelView, cfg.maxWheelSpeed.toString())

        section(root, "BATTERY THRESHOLDS")
        root.addView(diagnostic(10f).apply {
            text = "Same Roomba OI charge-unit thresholds sent by Pi roverd. These drive the server/browser battery percentage and warning states."
        })
        field(root, "Full", batteryFullView, cfg.batteryFull.toString())
        field(root, "Warn", batteryWarnView, cfg.batteryWarn.toString())
        field(root, "Urgent", batteryUrgentView, cfg.batteryUrgent.toString())

        section(root, "AUTOMATIC SIDE BRUSH")
        autoSideEnabledView.text = "Automatic side brush while driving"
        autoSideEnabledView.isChecked = cfg.autoSideBrushEnabled
        root.addView(autoSideEnabledView)
        field(root, "Automatic side brush PWM (-127..127)", autoSideSpeedView, cfg.autoSideBrushSpeed.toString())

        section(root, "BRC KEEP-AWAKE")
        label(root, "BRC control line")
        brcLineView.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, BrcLine.entries.map { it.name })
        brcLineView.setSelection(BrcLine.entries.indexOf(cfg.brcLine).coerceAtLeast(0))
        root.addView(brcLineView)
        brcActiveLowView.text = "BRC active state is LOW / control-line false"
        brcActiveLowView.isChecked = cfg.brcActiveLow
        root.addView(brcActiveLowView)
        field(root, "BRC pulse interval (ms)", brcEveryView, cfg.brcPulseEveryMs.toString())
        field(root, "BRC pulse width (ms)", brcWidthView, cfg.brcPulseWidthMs.toString())

        section(root, "PRIVATE ROVER / SERVER SAFETY")
        root.addView(diagnostic(10f).apply {
            text = "This block is advertised in the rover hello exactly like Pi roverd. The MultiRoombaRover server uses it when processing the Roomba sensor stream."
        })
        privateEnabledView.text = "Private rover enabled"
        privateEnabledView.isChecked = cfg.privateEnabled
        root.addView(privateEnabledView)

        val s = cfg.privateSafety
        speedLimitEnabledView.text = "Safety speed limit enabled"
        speedLimitEnabledView.isChecked = s.speedLimitEnabled
        root.addView(speedLimitEnabledView)
        field(root, "Safety max wheel speed (mm/s)", speedLimitMaxView, s.speedLimitMaxWheelSpeed.toString())

        overcurrentEnabledView.text = "Hard overcurrent stop enabled"
        overcurrentEnabledView.isChecked = s.hardOvercurrentEnabled
        root.addView(overcurrentEnabledView)
        field(root, "Overcurrent stop time (ms)", overcurrentStopView, s.overcurrentStopMs.toString())

        bumpEnabledView.text = "Hard bump backoff enabled"
        bumpEnabledView.isChecked = s.hardBumpEnabled
        root.addView(bumpEnabledView)
        field(root, "Bump backoff speed (mm/s)", bumpSpeedView, s.bumpBackoffSpeed.toString())
        field(root, "Bump backoff time (ms)", bumpMsView, s.bumpBackoffMs.toString())

        cliffEnabledView.text = "Cliff backoff enabled"
        cliffEnabledView.isChecked = s.cliffEnabled
        root.addView(cliffEnabledView)
        field(root, "Cliff backoff speed (mm/s)", cliffSpeedView, s.cliffBackoffSpeed.toString())
        field(root, "Cliff backoff time (ms)", cliffMsView, s.cliffBackoffMs.toString())

        virtualWallEnabledView.text = "Virtual-wall backoff enabled"
        virtualWallEnabledView.isChecked = s.virtualWallEnabled
        root.addView(virtualWallEnabledView)
        field(root, "Virtual-wall backoff speed (mm/s)", virtualWallSpeedView, s.virtualWallBackoffSpeed.toString())
        field(root, "Virtual-wall backoff time (ms)", virtualWallMsView, s.virtualWallBackoffMs.toString())
        field(root, "Safety trigger cooldown (ms)", cooldownView, s.triggerCooldownMs.toString())

        button(root, "SAVE ROOMBA SETTINGS + RESTART") { saveAndRestart() }
        refreshRuntime()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.removeCallbacks(refreshTask)
        handler.post(refreshTask)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(refreshTask)
        super.onDetachedFromWindow()
    }

    private fun refreshUsbChoices(preferred: String = RoverSettings.load(context).usbSerialPreference) {
        val found = runCatching { UsbSerialSelector.choices(context) }.getOrElse {
            RoverRuntimeState.log("USB settings enumeration failed: ${it.stackTraceToString()}")
            listOf(UsbSerialCandidate(UsbSerialSelector.AUTO, "AUTO — enumeration failed", null, 0))
        }.toMutableList()
        if (preferred.isNotBlank() && found.none { it.key.equals(preferred, true) }) {
            found += UsbSerialCandidate(preferred, "$preferred — saved adapter (not currently connected)", null, 0)
        }
        usbChoices = found
        usbView.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, usbChoices.map { it.label })
        usbView.setSelection(usbChoices.indexOfFirst { it.key.equals(preferred, true) }.coerceAtLeast(0))
    }

    private fun saveAndRestart() {
        val old = RoverSettings.load(context)
        val full = batteryFullView.text.toString().toIntOrNull() ?: old.batteryFull
        val warn = batteryWarnView.text.toString().toIntOrNull() ?: old.batteryWarn
        val urgent = batteryUrgentView.text.toString().toIntOrNull() ?: old.batteryUrgent
        if (full <= 0 || warn !in 0..full || urgent !in 0..warn) {
            runtimeView.text = "SETTINGS ERROR: battery must satisfy full > 0, 0 <= urgent <= warn <= full\n\n${runtimeSnapshot()}"
            return
        }

        val line = BrcLine.entries.getOrElse(brcLineView.selectedItemPosition) { BrcLine.RTS }
        val safety = PrivateSafetyConfig(
            speedLimitEnabled = speedLimitEnabledView.isChecked,
            speedLimitMaxWheelSpeed = int(speedLimitMaxView, old.privateSafety.speedLimitMaxWheelSpeed, 1, 500),
            hardOvercurrentEnabled = overcurrentEnabledView.isChecked,
            overcurrentStopMs = int(overcurrentStopView, old.privateSafety.overcurrentStopMs, 0, 5_000),
            hardBumpEnabled = bumpEnabledView.isChecked,
            bumpBackoffSpeed = int(bumpSpeedView, old.privateSafety.bumpBackoffSpeed, 1, 500),
            bumpBackoffMs = int(bumpMsView, old.privateSafety.bumpBackoffMs, 0, 5_000),
            cliffEnabled = cliffEnabledView.isChecked,
            cliffBackoffSpeed = int(cliffSpeedView, old.privateSafety.cliffBackoffSpeed, 1, 500),
            cliffBackoffMs = int(cliffMsView, old.privateSafety.cliffBackoffMs, 0, 5_000),
            virtualWallEnabled = virtualWallEnabledView.isChecked,
            virtualWallBackoffSpeed = int(virtualWallSpeedView, old.privateSafety.virtualWallBackoffSpeed, 1, 500),
            virtualWallBackoffMs = int(virtualWallMsView, old.privateSafety.virtualWallBackoffMs, 0, 5_000),
            triggerCooldownMs = int(cooldownView, old.privateSafety.triggerCooldownMs, 0, 10_000),
        )
        val cfg = old.copy(
            usbSerialPreference = usbChoices.getOrNull(usbView.selectedItemPosition)?.key ?: old.usbSerialPreference,
            baud = int(baudView, old.baud, 300, 2_000_000),
            maxWheelSpeed = int(maxWheelView, old.maxWheelSpeed, 1, 500),
            batteryFull = full,
            batteryWarn = warn,
            batteryUrgent = urgent,
            brcLine = line,
            brcActiveLow = brcActiveLowView.isChecked,
            brcPulseEveryMs = (brcEveryView.text.toString().toLongOrNull() ?: old.brcPulseEveryMs).coerceIn(1_000L, 3_600_000L),
            brcPulseWidthMs = (brcWidthView.text.toString().toLongOrNull() ?: old.brcPulseWidthMs).coerceIn(1L, 60_000L),
            autoSideBrushEnabled = autoSideEnabledView.isChecked,
            autoSideBrushSpeed = int(autoSideSpeedView, old.autoSideBrushSpeed, -127, 127),
            privateEnabled = privateEnabledView.isChecked,
            privateSafety = safety,
        )
        RoverSettings.save(context, cfg)
        RoverRuntimeState.log(
            "UI saved ROOMBA config usb=${cfg.usbSerialPreference} baud=${cfg.baud} max=${cfg.maxWheelSpeed} " +
                "battery=${cfg.batteryFull}/${cfg.batteryWarn}/${cfg.batteryUrgent} private=${cfg.privateEnabled}",
        )
        signalRover(RoverService.ACTION_RESTART)
        refreshRuntime()
    }

    private fun int(view: EditText, fallback: Int, min: Int, max: Int): Int =
        (view.text.toString().toIntOrNull() ?: fallback).coerceIn(min, max)

    private fun runtimeSnapshot(): String {
        val cfg = RoverSettings.load(context)
        val s = cfg.privateSafety
        val now = System.currentTimeMillis()
        val sensorAge = if (RoverRuntimeState.lastSensorAtMs == 0L) "never" else "${now - RoverRuntimeState.lastSensorAtMs} ms ago"
        return buildString {
            appendLine("USB connected : ${RoverRuntimeState.usbConnected}")
            appendLine("USB selection : ${cfg.usbSerialPreference}")
            appendLine("USB device    : ${RoverRuntimeState.usbDevice.ifBlank { "-" }}")
            appendLine("serial        : ${cfg.baud} 8N1")
            appendLine("max wheel     : ${cfg.maxWheelSpeed} mm/s")
            appendLine("battery cfg   : full=${cfg.batteryFull} warn=${cfg.batteryWarn} urgent=${cfg.batteryUrgent}")
            appendLine("auto side     : enabled=${cfg.autoSideBrushEnabled} speed=${cfg.autoSideBrushSpeed}")
            appendLine("BRC           : ${cfg.brcLine} activeLow=${cfg.brcActiveLow} every=${cfg.brcPulseEveryMs}ms width=${cfg.brcPulseWidthMs}ms")
            appendLine("private       : ${cfg.privateEnabled}")
            appendLine("safety speed  : ${s.speedLimitEnabled}/${s.speedLimitMaxWheelSpeed}")
            appendLine("safety OC     : ${s.hardOvercurrentEnabled}/${s.overcurrentStopMs}ms")
            appendLine("safety bump   : ${s.hardBumpEnabled}/${s.bumpBackoffSpeed}/${s.bumpBackoffMs}ms")
            appendLine("safety cliff  : ${s.cliffEnabled}/${s.cliffBackoffSpeed}/${s.cliffBackoffMs}ms")
            appendLine("safety wall   : ${s.virtualWallEnabled}/${s.virtualWallBackoffSpeed}/${s.virtualWallBackoffMs}ms cooldown=${s.triggerCooldownMs}")
            appendLine("home base     : ${RoverRuntimeState.homeBaseDetected}")
            appendLine("charging      : ${RoverRuntimeState.roombaCharging}")
            appendLine("AutoCharge    : ${RoverRuntimeState.autoChargeState}")
            appendLine("sensor frames : ${RoverRuntimeState.sensorFrames}")
            appendLine("last sensor   : $sensorAge")
            append("last frame    : ${RoverRuntimeState.lastSensorHex.ifBlank { "-" }}")
        }
    }

    private fun refreshRuntime() {
        runtimeView.text = runtimeSnapshot()
    }

    private fun signalRover(action: String) {
        val intent = Intent(context, RoverService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    private fun field(root: LinearLayout, label: String, view: EditText, value: String) {
        root.addView(TextView(context).apply { text = label })
        view.setText(value)
        root.addView(view)
    }

    private fun label(root: LinearLayout, text: String) {
        root.addView(TextView(context).apply { this.text = text })
    }

    private fun section(root: LinearLayout, text: String) {
        root.addView(TextView(context).apply {
            this.text = "\n--- $text ---"
            textSize = 16f
            typeface = Typeface.MONOSPACE
        })
    }

    private fun button(root: LinearLayout, text: String, action: () -> Unit) {
        root.addView(Button(context).apply {
            this.text = text
            setOnClickListener { action() }
        })
    }

    private fun diagnostic(size: Float): TextView = TextView(context).apply {
        textSize = size
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }
}
