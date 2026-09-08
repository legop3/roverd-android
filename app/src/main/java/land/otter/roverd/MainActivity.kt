package land.otter.roverd

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var diagnosticsView: TextView
    private lateinit var logView: TextView
    private lateinit var nameView: EditText
    private lateinit var serverView: EditText
    private lateinit var baudView: EditText
    private lateinit var speedView: EditText
    private lateinit var brcLineView: Spinner
    private lateinit var brcActiveLowView: CheckBox

    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshDiagnostics()
            uiHandler.postDelayed(this, 500)
        }
    }

    private val statusListener: (String) -> Unit = { value ->
        runOnUiThread { statusView.text = "STATUS: $value" }
    }

    private val logListener: () -> Unit = {
        runOnUiThread { refreshLog() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RoverRuntimeState.initialize(this)
        setContentView(buildUi())
        loadSettings()
        requestNotificationPermission()
        startRoverService()
    }

    override fun onStart() {
        super.onStart()
        RoverRuntimeState.addListener(statusListener)
        RoverRuntimeState.addLogListener(logListener)
        uiHandler.post(refreshRunnable)
    }

    override fun onStop() {
        uiHandler.removeCallbacks(refreshRunnable)
        RoverRuntimeState.removeLogListener(logListener)
        RoverRuntimeState.removeListener(statusListener)
        super.onStop()
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "ROVERD ANDROID / SERVICE CONSOLE"
            textSize = 22f
            typeface = Typeface.MONOSPACE
        })

        statusView = TextView(this).apply {
            text = "STATUS: Starting"
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(statusView)

        diagnosticsView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, pad / 2, 0, pad)
        }
        root.addView(diagnosticsView)

        addButton(root, "RESTART FULL ROVER RUNTIME") {
            sendServiceAction(RoverService.ACTION_RESTART)
        }
        addButton(root, "RECONNECT USB SERIAL") {
            sendServiceAction(RoverService.ACTION_RECONNECT_USB)
        }
        addButton(root, "RESTART SENSOR STREAM") {
            sendServiceAction(RoverService.ACTION_RESTART_SENSOR_STREAM)
        }
        addButton(root, "PULSE BRC NOW") {
            sendServiceAction(RoverService.ACTION_PULSE_BRC)
        }
        addButton(root, "COPY DIAGNOSTICS + LOG") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText(
                    "roverd diagnostics",
                    diagnosticsView.text.toString() + "\n\n--- LOG ---\n" + RoverRuntimeState.logSnapshot(),
                ),
            )
            RoverRuntimeState.log("UI diagnostics copied to clipboard")
        }
        addButton(root, "CLEAR LOG") {
            RoverRuntimeState.clearLog()
        }

        root.addView(TextView(this).apply {
            text = "\n--- CONFIGURATION ---"
            typeface = Typeface.MONOSPACE
            textSize = 16f
        })

        nameView = edit(root, "Rover name")
        serverView = edit(root, "Server WebSocket URL")
        baudView = edit(root, "Serial baud")
        speedView = edit(root, "Max wheel speed (mm/s)")

        root.addView(TextView(this).apply { text = "BRC control line" })
        brcLineView = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                BrcLine.entries.map { it.name },
            )
        }
        root.addView(brcLineView)

        brcActiveLowView = CheckBox(this).apply {
            text = "BRC active state is LOW / control-line false"
        }
        root.addView(brcActiveLowView)

        addButton(root, "SAVE CONFIG + RESTART") {
            saveSettings()
            sendServiceAction(RoverService.ACTION_RESTART)
        }

        root.addView(TextView(this).apply {
            text = "\n--- ROLLING LOG (selectable) ---"
            typeface = Typeface.MONOSPACE
            textSize = 16f
        })

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextIsSelectable(true)
            setPadding(0, pad / 2, 0, pad * 2)
        }
        root.addView(logView)

        return scroll
    }

    private fun addButton(root: LinearLayout, label: String, action: () -> Unit) {
        root.addView(Button(this).apply {
            text = label
            setOnClickListener { action() }
        })
    }

    private fun edit(root: LinearLayout, label: String): EditText {
        root.addView(TextView(this).apply { text = label })
        return EditText(this).also { root.addView(it) }
    }

    private fun refreshDiagnostics() {
        if (!::diagnosticsView.isInitialized) return
        val now = System.currentTimeMillis()
        val sensorAge = if (RoverRuntimeState.lastSensorAtMs == 0L) {
            "never"
        } else {
            "${now - RoverRuntimeState.lastSensorAtMs} ms ago"
        }
        val commandAge = if (RoverRuntimeState.lastCommandAtMs == 0L) {
            "never"
        } else {
            "${now - RoverRuntimeState.lastCommandAtMs} ms ago"
        }
        val cfg = RoverSettings.load(this)

        diagnosticsView.text = buildString {
            appendLine("device        : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android       : ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("rover name    : ${cfg.name}")
            appendLine("server URL    : ${cfg.serverUrl}")
            appendLine("USB connected : ${RoverRuntimeState.usbConnected}")
            appendLine("USB device    : ${RoverRuntimeState.usbDevice.ifBlank { "-" }}")
            appendLine("WS connected  : ${RoverRuntimeState.serverConnected}")
            appendLine("serial        : ${cfg.baud} 8N1")
            appendLine("BRC           : ${cfg.brcLine} activeLow=${cfg.brcActiveLow} every=${cfg.brcPulseEveryMs}ms width=${cfg.brcPulseWidthMs}ms")
            appendLine("sensor frames : ${RoverRuntimeState.sensorFrames}")
            appendLine("sensor bytes  : ${RoverRuntimeState.sensorBytes}")
            appendLine("last sensor   : $sensorAge")
            appendLine("last frame    : ${RoverRuntimeState.lastSensorHex.ifBlank { "-" }}")
            appendLine("commands      : ${RoverRuntimeState.commandCount}")
            appendLine("last command  : $commandAge")
            append("command JSON  : ${RoverRuntimeState.lastCommand.ifBlank { "-" }}")
        }
    }

    private fun refreshLog() {
        if (::logView.isInitialized) logView.text = RoverRuntimeState.logSnapshot()
    }

    private fun loadSettings() {
        val cfg = RoverSettings.load(this)
        nameView.setText(cfg.name)
        serverView.setText(cfg.serverUrl)
        baudView.setText(cfg.baud.toString())
        speedView.setText(cfg.maxWheelSpeed.toString())
        brcLineView.setSelection(BrcLine.entries.indexOf(cfg.brcLine).coerceAtLeast(0))
        brcActiveLowView.isChecked = cfg.brcActiveLow
    }

    private fun saveSettings() {
        val old = RoverSettings.load(this)
        val cfg = old.copy(
            name = nameView.text.toString().trim().ifEmpty { "android-rover" },
            serverUrl = serverView.text.toString().trim().ifEmpty { old.serverUrl },
            baud = baudView.text.toString().toIntOrNull() ?: old.baud,
            maxWheelSpeed = speedView.text.toString().toIntOrNull() ?: old.maxWheelSpeed,
            brcLine = BrcLine.entries.getOrElse(brcLineView.selectedItemPosition) { BrcLine.RTS },
            brcActiveLow = brcActiveLowView.isChecked,
        )
        RoverSettings.save(this, cfg)
        RoverRuntimeState.log("UI saved configuration")
    }

    private fun startRoverService() {
        startForegroundService(Intent(this, RoverService::class.java))
    }

    private fun sendServiceAction(action: String) {
        startForegroundService(Intent(this, RoverService::class.java).setAction(action))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
