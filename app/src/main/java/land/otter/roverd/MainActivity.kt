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
import android.os.PowerManager
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TabHost
import android.widget.TabWidget
import android.widget.TextView

class MainActivity : Activity() {
    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 2
    }

    private lateinit var statusView: TextView
    private lateinit var systemDiagnosticsView: TextView
    private lateinit var roombaDiagnosticsView: TextView
    private lateinit var cameraDiagnosticsView: TextView
    private lateinit var micDiagnosticsView: TextView
    private lateinit var audioDiagnosticsView: TextView
    private lateinit var logView: TextView

    private lateinit var nameView: EditText
    private lateinit var serverView: EditText
    private lateinit var baudView: EditText
    private lateinit var speedView: EditText
    private lateinit var brcLineView: Spinner
    private lateinit var brcActiveLowView: CheckBox
    private lateinit var cameraIdView: EditText

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
        refreshCameraDiagnostics()
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
        val tabHost = TabHost(this)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val tabs = TabWidget(this).apply {
            id = android.R.id.tabs
        }
        val content = FrameLayout(this).apply {
            id = android.R.id.tabcontent
        }
        outer.addView(
            tabs,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        outer.addView(
            content,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        tabHost.addView(outer)
        tabHost.setup()

        addTab(tabHost, content, "system", "SYSTEM", buildSystemTab())
        addTab(tabHost, content, "roomba", "ROOMBA", buildRoombaTab())
        addTab(tabHost, content, "camera", "CAMERA", buildCameraTab())
        addTab(tabHost, content, "mic", "MIC", buildMicTab())
        addTab(tabHost, content, "audio", "AUDIO", buildAudioTab())
        addTab(tabHost, content, "log", "LOG", buildLogTab())

        tabHost.setOnTabChangedListener { tag ->
            if (tag == "camera") refreshCameraDiagnostics()
        }
        return tabHost
    }

    private fun addTab(tabHost: TabHost, content: FrameLayout, tag: String, label: String, view: View) {
        view.id = View.generateViewId()
        content.addView(view)
        tabHost.addTab(
            tabHost.newTabSpec(tag)
                .setIndicator(label)
                .setContent(view.id),
        )
    }

    private fun buildSystemTab(): View = scrollTab { root, pad ->
        root.addView(TextView(this).apply {
            text = "ROVERD ANDROID / SYSTEM"
            textSize = 22f
            typeface = Typeface.MONOSPACE
        })

        statusView = diagnosticText(15f).apply {
            text = "STATUS: Starting"
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(statusView)

        systemDiagnosticsView = diagnosticText(12f)
        root.addView(systemDiagnosticsView)

        addButton(root, "RESTART FULL ROVER RUNTIME") {
            sendServiceAction(RoverService.ACTION_RESTART)
        }
        addButton(root, "COPY ALL DIAGNOSTICS + LOG") {
            copyAllDiagnostics()
        }

        section(root, "CONFIGURATION")
        nameView = edit(root, "Rover name")
        serverView = edit(root, "Server WebSocket URL")
        addButton(root, "SAVE CONFIG + RESTART") {
            saveSettings()
            sendServiceAction(RoverService.ACTION_RESTART)
        }
    }

    private fun buildRoombaTab(): View = scrollTab { root, _ ->
        section(root, "ROOMBA / USB SERIAL")
        roombaDiagnosticsView = diagnosticText(12f)
        root.addView(roombaDiagnosticsView)

        addButton(root, "RECONNECT USB SERIAL") {
            sendServiceAction(RoverService.ACTION_RECONNECT_USB)
        }
        addButton(root, "RESTART SENSOR STREAM") {
            sendServiceAction(RoverService.ACTION_RESTART_SENSOR_STREAM)
        }
        addButton(root, "PULSE BRC NOW") {
            sendServiceAction(RoverService.ACTION_PULSE_BRC)
        }

        section(root, "SERIAL CONFIGURATION")
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

        addButton(root, "SAVE SERIAL CONFIG + RESTART") {
            saveSettings()
            sendServiceAction(RoverService.ACTION_RESTART)
        }
    }

    private fun buildCameraTab(): View = scrollTab { root, _ ->
        section(root, "CAMERA / RAW ANDROID INVENTORY")
        root.addView(TextView(this).apply {
            text = "Camera selection is the exact Android camera ID, not a front/back abstraction. Camera2 logical/physical IDs are dumped below when available."
            typeface = Typeface.MONOSPACE
            textSize = 11f
        })

        cameraIdView = edit(root, "Selected raw camera ID")

        addButton(root, "SAVE CAMERA ID") {
            saveSettings()
            refreshCameraDiagnostics()
        }
        addButton(root, "REQUEST CAMERA PERMISSION") {
            requestCameraPermission()
        }
        addButton(root, "REFRESH CAMERA INVENTORY") {
            refreshCameraDiagnostics()
        }

        cameraDiagnosticsView = diagnosticText(10f)
        root.addView(cameraDiagnosticsView)
    }

    private fun buildMicTab(): View = scrollTab { root, _ ->
        section(root, "MIC / AUDIO CAPTURE")
        micDiagnosticsView = diagnosticText(12f).apply {
            text = "NOT IMPLEMENTED YET\n\nThis tab will expose microphone devices/sources, sample rates, channel counts, capture state, levels, encoder state, packet counters, and failures."
        }
        root.addView(micDiagnosticsView)
    }

    private fun buildAudioTab(): View = scrollTab { root, _ ->
        section(root, "AUDIO PLAYBACK / TTS / HORN")
        audioDiagnosticsView = diagnosticText(12f).apply {
            text = "NOT IMPLEMENTED YET\n\nThis tab will expose reverse audio forwarding, AudioTrack state, selected output route, TTS engine/voice, horn state, buffer counters, underruns, and failures."
        }
        root.addView(audioDiagnosticsView)
    }

    private fun buildLogTab(): View = scrollTab { root, pad ->
        section(root, "ROLLING LOG")
        addButton(root, "COPY ALL DIAGNOSTICS + LOG") {
            copyAllDiagnostics()
        }
        addButton(root, "CLEAR LOG") {
            RoverRuntimeState.clearLog()
        }

        logView = diagnosticText(10f).apply {
            setPadding(0, pad / 2, 0, pad * 2)
        }
        root.addView(logView)
    }

    private fun scrollTab(builder: (LinearLayout, Int) -> Unit): View {
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        builder(root, pad)
        scroll.addView(root)
        return scroll
    }

    private fun diagnosticText(size: Float): TextView = TextView(this).apply {
        textSize = size
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }

    private fun section(root: LinearLayout, label: String) {
        root.addView(TextView(this).apply {
            text = "\n--- $label ---"
            typeface = Typeface.MONOSPACE
            textSize = 16f
        })
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
        if (!::systemDiagnosticsView.isInitialized || !::roombaDiagnosticsView.isInitialized) return
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
        val batteryOptimization = if (Build.VERSION.SDK_INT >= 23) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (power.isIgnoringBatteryOptimizations(packageName)) "EXEMPT" else "ACTIVE"
        } else {
            "n/a (< API 23)"
        }
        val cfg = RoverSettings.load(this)

        systemDiagnosticsView.text = buildString {
            appendLine("device        : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android       : ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("rover name    : ${cfg.name}")
            appendLine("server URL    : ${cfg.serverUrl}")
            appendLine("WS connected  : ${RoverRuntimeState.serverConnected}")
            appendLine("CPU wake lock : ${RoverRuntimeState.wakeLockHeld}")
            appendLine("Wi-Fi lock    : ${RoverRuntimeState.wifiLockHeld}")
            appendLine("battery opt   : $batteryOptimization")
            appendLine("commands      : ${RoverRuntimeState.commandCount}")
            appendLine("last command  : $commandAge")
            append("command JSON  : ${RoverRuntimeState.lastCommand.ifBlank { "-" }}")
        }

        roombaDiagnosticsView.text = buildString {
            appendLine("USB connected : ${RoverRuntimeState.usbConnected}")
            appendLine("USB device    : ${RoverRuntimeState.usbDevice.ifBlank { "-" }}")
            appendLine("serial        : ${cfg.baud} 8N1")
            appendLine("max wheel     : ${cfg.maxWheelSpeed} mm/s")
            appendLine("BRC           : ${cfg.brcLine} activeLow=${cfg.brcActiveLow} every=${cfg.brcPulseEveryMs}ms width=${cfg.brcPulseWidthMs}ms")
            appendLine("sensor frames : ${RoverRuntimeState.sensorFrames}")
            appendLine("sensor bytes  : ${RoverRuntimeState.sensorBytes}")
            appendLine("last sensor   : $sensorAge")
            append("last frame    : ${RoverRuntimeState.lastSensorHex.ifBlank { "-" }}")
        }
    }

    private fun refreshCameraDiagnostics() {
        if (!::cameraDiagnosticsView.isInitialized || !::cameraIdView.isInitialized) return
        val selected = cameraIdView.text.toString().trim()
        cameraDiagnosticsView.text = "Reading Android camera inventory..."
        Thread {
            val text = runCatching { CameraDiagnostics.snapshot(this, selected) }
                .getOrElse { "CAMERA diagnostics failed: ${it.stackTraceToString()}" }
            runOnUiThread {
                if (::cameraDiagnosticsView.isInitialized) cameraDiagnosticsView.text = text
            }
        }.start()
    }

    private fun refreshLog() {
        if (::logView.isInitialized) logView.text = RoverRuntimeState.logSnapshot()
    }

    private fun copyAllDiagnostics() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = buildString {
            appendLine("=== SYSTEM ===")
            appendLine(if (::systemDiagnosticsView.isInitialized) systemDiagnosticsView.text else "-")
            appendLine()
            appendLine("=== ROOMBA ===")
            appendLine(if (::roombaDiagnosticsView.isInitialized) roombaDiagnosticsView.text else "-")
            appendLine()
            appendLine("=== CAMERA ===")
            appendLine(if (::cameraDiagnosticsView.isInitialized) cameraDiagnosticsView.text else "-")
            appendLine()
            appendLine("=== MIC ===")
            appendLine(if (::micDiagnosticsView.isInitialized) micDiagnosticsView.text else "-")
            appendLine()
            appendLine("=== AUDIO ===")
            appendLine(if (::audioDiagnosticsView.isInitialized) audioDiagnosticsView.text else "-")
            appendLine()
            appendLine("=== LOG ===")
            append(RoverRuntimeState.logSnapshot())
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("roverd diagnostics", text))
        RoverRuntimeState.log("UI all diagnostics copied to clipboard")
    }

    private fun loadSettings() {
        val cfg = RoverSettings.load(this)
        nameView.setText(cfg.name)
        serverView.setText(cfg.serverUrl)
        baudView.setText(cfg.baud.toString())
        speedView.setText(cfg.maxWheelSpeed.toString())
        brcLineView.setSelection(BrcLine.entries.indexOf(cfg.brcLine).coerceAtLeast(0))
        brcActiveLowView.isChecked = cfg.brcActiveLow
        cameraIdView.setText(cfg.cameraId)
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
            cameraId = cameraIdView.text.toString().trim().ifEmpty { old.cameraId },
        )
        RoverSettings.save(this, cfg)
        RoverRuntimeState.log("UI saved configuration cameraId=${cfg.cameraId}")
    }

    private fun startRoverService() {
        startServiceCompat(Intent(this, RoverService::class.java))
    }

    private fun sendServiceAction(action: String) {
        startServiceCompat(Intent(this, RoverService::class.java).setAction(action))
    }

    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent)
        else startService(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun requestCameraPermission() {
        if (Build.VERSION.SDK_INT < 23) {
            RoverRuntimeState.log("CAMERA permission is install-time on API ${Build.VERSION.SDK_INT}")
            refreshCameraDiagnostics()
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            RoverRuntimeState.log("CAMERA permission already granted")
            refreshCameraDiagnostics()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            RoverRuntimeState.log(
                "CAMERA permission result=${grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED}",
            )
            refreshCameraDiagnostics()
        }
    }
}
