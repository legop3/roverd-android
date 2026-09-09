package land.otter.roverd

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {
    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 2
    }

    private enum class Page(val label: String) {
        SYSTEM("SYSTEM"),
        ROOMBA("ROOMBA"),
        CAMERA("CAMERA"),
        MIC("MIC"),
        AUDIO("AUDIO"),
        LOG("LOG"),
    }

    private lateinit var statusView: TextView
    private lateinit var pageHost: FrameLayout
    private var currentPage = Page.SYSTEM
    private val tabButtons = mutableMapOf<Page, Button>()

    private var systemDiagnosticsView: TextView? = null
    private var roombaDiagnosticsView: TextView? = null
    private var cameraSummaryView: TextView? = null
    private var cameraInventoryView: TextView? = null
    private var logView: TextView? = null

    private var nameView: EditText? = null
    private var serverView: EditText? = null

    private var baudView: EditText? = null
    private var speedView: EditText? = null
    private var brcLineView: Spinner? = null
    private var brcActiveLowView: CheckBox? = null
    private var brcEveryView: EditText? = null
    private var brcWidthView: EditText? = null

    private var cameraIdView: EditText? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshVisiblePage()
            uiHandler.postDelayed(this, 500)
        }
    }

    private val statusListener: (String) -> Unit = { value ->
        runOnUiThread {
            if (::statusView.isInitialized) statusView.text = "STATUS: $value"
        }
    }

    private val logListener: () -> Unit = {
        runOnUiThread {
            if (currentPage == Page.LOG) refreshLog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RoverRuntimeState.initialize(this)
        setContentView(buildShell())
        showPage(Page.SYSTEM)
        requestNotificationPermission()
        startRoverService()
        requestBatteryOptimizationExemption(userInitiated = false)
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

    private fun buildShell(): View {
        val density = resources.displayMetrics.density
        val pad = (8 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        statusView = TextView(this).apply {
            text = "STATUS: Starting"
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(
            statusView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val tabScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
        }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (page in Page.entries) {
            val button = Button(this).apply {
                text = page.label
                setOnClickListener { showPage(page) }
            }
            tabButtons[page] = button
            tabRow.addView(button)
        }
        tabScroller.addView(tabRow)
        root.addView(
            tabScroller,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        pageHost = FrameLayout(this)
        root.addView(
            pageHost,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        return root
    }

    private fun showPage(page: Page) {
        currentPage = page
        tabButtons.forEach { (p, button) -> button.isEnabled = p != page }

        systemDiagnosticsView = null
        roombaDiagnosticsView = null
        cameraSummaryView = null
        cameraInventoryView = null
        logView = null
        nameView = null
        serverView = null
        baudView = null
        speedView = null
        brcLineView = null
        brcActiveLowView = null
        brcEveryView = null
        brcWidthView = null
        cameraIdView = null

        pageHost.removeAllViews()
        val view = when (page) {
            Page.SYSTEM -> buildSystemPage()
            Page.ROOMBA -> buildRoombaPage()
            Page.CAMERA -> buildCameraPage()
            Page.MIC -> buildMicPage()
            Page.AUDIO -> buildAudioPage()
            Page.LOG -> buildLogPage()
        }
        pageHost.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        refreshVisiblePage()
    }

    private fun buildSystemPage(): View = scrollPage { root, _ ->
        pageTitle(root, "SYSTEM / ROVER SERVER")

        systemDiagnosticsView = diagnosticText(12f).also { root.addView(it) }

        section(root, "RUNTIME")
        addButton(root, "RESTART FULL ROVER RUNTIME") {
            sendServiceAction(RoverService.ACTION_RESTART)
        }
        addButton(root, "REQUEST BATTERY / DOZE EXEMPTION") {
            requestBatteryOptimizationExemption(userInitiated = true)
        }
        addButton(root, "COPY ALL DIAGNOSTICS + LOG") {
            copyAllDiagnostics()
        }

        section(root, "SETTINGS")
        val cfg = RoverSettings.load(this)
        nameView = edit(root, "Rover name").apply { setText(cfg.name) }
        serverView = edit(root, "Server WebSocket URL").apply { setText(cfg.serverUrl) }
        addButton(root, "SAVE SYSTEM SETTINGS + RESTART") {
            saveSystemSettings()
        }
    }

    private fun buildRoombaPage(): View = scrollPage { root, _ ->
        pageTitle(root, "ROOMBA / USB SERIAL / OI")

        roombaDiagnosticsView = diagnosticText(12f).also { root.addView(it) }

        section(root, "SERIAL / OI RECOVERY")
        addButton(root, "RECONNECT USB SERIAL") {
            sendServiceAction(RoverService.ACTION_RECONNECT_USB)
        }
        addButton(root, "RESTART SENSOR STREAM") {
            sendServiceAction(RoverService.ACTION_RESTART_SENSOR_STREAM)
        }
        addButton(root, "PULSE BRC NOW") {
            sendServiceAction(RoverService.ACTION_PULSE_BRC)
        }

        section(root, "ROOMBA SETTINGS")
        val cfg = RoverSettings.load(this)
        baudView = edit(root, "Serial baud").apply { setText(cfg.baud.toString()) }
        speedView = edit(root, "Max wheel speed (mm/s)").apply { setText(cfg.maxWheelSpeed.toString()) }

        root.addView(TextView(this).apply { text = "BRC control line" })
        brcLineView = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                BrcLine.entries.map { it.name },
            )
            setSelection(BrcLine.entries.indexOf(cfg.brcLine).coerceAtLeast(0))
        }
        root.addView(brcLineView)

        brcActiveLowView = CheckBox(this).apply {
            text = "BRC active state is LOW / control-line false"
            isChecked = cfg.brcActiveLow
        }
        root.addView(brcActiveLowView)

        brcEveryView = edit(root, "BRC pulse interval (ms)").apply { setText(cfg.brcPulseEveryMs.toString()) }
        brcWidthView = edit(root, "BRC pulse width (ms)").apply { setText(cfg.brcPulseWidthMs.toString()) }

        addButton(root, "SAVE ROOMBA SETTINGS + RESTART") {
            saveRoombaSettings()
        }
    }

    private fun buildCameraPage(): View = scrollPage { root, _ ->
        pageTitle(root, "CAMERA")

        cameraSummaryView = diagnosticText(12f).also { root.addView(it) }

        section(root, "CAMERA SETTINGS")
        val cfg = RoverSettings.load(this)
        cameraIdView = edit(root, "Selected raw Android camera ID").apply { setText(cfg.cameraId) }
        root.addView(TextView(this).apply {
            text = "Camera IDs are kept raw. No front/back abstraction is used."
            typeface = Typeface.MONOSPACE
            textSize = 11f
        })
        addButton(root, "SAVE CAMERA SETTINGS") {
            saveCameraSettings()
        }
        addButton(root, "REQUEST CAMERA PERMISSION") {
            requestCameraPermission()
        }
        addButton(root, "REFRESH RAW CAMERA INVENTORY") {
            refreshCameraInventory()
        }

        section(root, "RAW CAMERA INVENTORY / CAPABILITIES")
        cameraInventoryView = diagnosticText(10f).apply {
            text = "Not read yet. Press REFRESH RAW CAMERA INVENTORY.\nCamera hardware is not probed during app startup."
        }.also { root.addView(it) }

        section(root, "VIDEO ENCODER / STREAM")
        root.addView(diagnosticText(11f).apply {
            text = "NOT IMPLEMENTED YET\nThis section will own H.264 encoder selection, resolution, FPS, bitrate, MediaMTX publishing state, frame counters, reconnects, and encoder errors."
        })
    }

    private fun buildMicPage(): View = scrollPage { root, _ ->
        pageTitle(root, "MIC / AUDIO CAPTURE")
        root.addView(diagnosticText(12f).apply {
            text = "NOT IMPLEMENTED YET\n\nMicrophone selection/source, capture format, levels, encoder state, packet counters, and capture errors will live on this page."
        })
    }

    private fun buildAudioPage(): View = scrollPage { root, _ ->
        pageTitle(root, "AUDIO PLAYBACK / TTS / HORN")
        root.addView(diagnosticText(12f).apply {
            text = "NOT IMPLEMENTED YET\n\nReverse audio forwarding, output route, AudioTrack buffers/underruns, TTS engine/voice, horn controls, and playback errors will live on this page."
        })
    }

    private fun buildLogPage(): View = scrollPage { root, pad ->
        pageTitle(root, "LOG / CRASH OUTPUT")
        addButton(root, "COPY ALL DIAGNOSTICS + LOG") {
            copyAllDiagnostics()
        }
        addButton(root, "CLEAR LOG") {
            RoverRuntimeState.clearLog()
        }
        logView = diagnosticText(10f).apply {
            setPadding(0, pad / 2, 0, pad * 2)
        }.also { root.addView(it) }
        refreshLog()
    }

    private fun scrollPage(builder: (LinearLayout, Int) -> Unit): View {
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

    private fun pageTitle(root: LinearLayout, label: String) {
        root.addView(TextView(this).apply {
            text = "ROVERD ANDROID / $label"
            textSize = 21f
            typeface = Typeface.MONOSPACE
        })
    }

    private fun section(root: LinearLayout, label: String) {
        root.addView(TextView(this).apply {
            text = "\n--- $label ---"
            typeface = Typeface.MONOSPACE
            textSize = 16f
        })
    }

    private fun diagnosticText(size: Float): TextView = TextView(this).apply {
        textSize = size
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
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

    private fun refreshVisiblePage() {
        when (currentPage) {
            Page.SYSTEM -> systemDiagnosticsView?.text = systemSnapshot()
            Page.ROOMBA -> roombaDiagnosticsView?.text = roombaSnapshot()
            Page.CAMERA -> cameraSummaryView?.text = cameraSummarySnapshot()
            Page.LOG -> refreshLog()
            else -> Unit
        }
    }

    private fun systemSnapshot(): String {
        val now = System.currentTimeMillis()
        val commandAge = if (RoverRuntimeState.lastCommandAtMs == 0L) {
            "never"
        } else {
            "${now - RoverRuntimeState.lastCommandAtMs} ms ago"
        }
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOptimization = if (Build.VERSION.SDK_INT >= 23) {
            if (power.isIgnoringBatteryOptimizations(packageName)) "EXEMPT" else "ACTIVE"
        } else {
            "n/a (< API 23)"
        }
        val powerSaver = if (Build.VERSION.SDK_INT >= 21) power.isPowerSaveMode.toString() else "n/a"
        val deviceIdle = if (Build.VERSION.SDK_INT >= 23) power.isDeviceIdleMode.toString() else "n/a"
        val cfg = RoverSettings.load(this)

        return buildString {
            appendLine("device        : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android       : ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("rover name    : ${cfg.name}")
            appendLine("server URL    : ${cfg.serverUrl}")
            appendLine("WS connected  : ${RoverRuntimeState.serverConnected}")
            appendLine("CPU wake lock : ${RoverRuntimeState.wakeLockHeld}")
            appendLine("Wi-Fi lock    : ${RoverRuntimeState.wifiLockHeld}")
            appendLine("battery opt   : $batteryOptimization")
            appendLine("power saver   : $powerSaver")
            appendLine("device idle   : $deviceIdle")
            appendLine("commands      : ${RoverRuntimeState.commandCount}")
            appendLine("last command  : $commandAge")
            append("command JSON  : ${RoverRuntimeState.lastCommand.ifBlank { "-" }}")
        }
    }

    private fun roombaSnapshot(): String {
        val now = System.currentTimeMillis()
        val sensorAge = if (RoverRuntimeState.lastSensorAtMs == 0L) {
            "never"
        } else {
            "${now - RoverRuntimeState.lastSensorAtMs} ms ago"
        }
        val cfg = RoverSettings.load(this)

        return buildString {
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

    private fun cameraSummarySnapshot(): String {
        val cfg = RoverSettings.load(this)
        val permission = if (Build.VERSION.SDK_INT < 23) {
            "install-time permission"
        } else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "GRANTED"
        } else {
            "NOT GRANTED"
        }
        return buildString {
            appendLine("selected ID   : ${cfg.cameraId}")
            appendLine("permission    : $permission")
            append("camera API    : ${if (Build.VERSION.SDK_INT >= 21) "Camera2" else "legacy Camera"}")
        }
    }

    private fun refreshCameraInventory() {
        val output = cameraInventoryView ?: return
        val selected = cameraIdView?.text?.toString()?.trim().orEmpty().ifEmpty {
            RoverSettings.load(this).cameraId
        }
        output.text = "Reading Android camera inventory..."
        Thread {
            val text = runCatching { CameraDiagnostics.snapshot(this, selected) }
                .getOrElse { "CAMERA diagnostics failed: ${it.stackTraceToString()}" }
            runOnUiThread {
                if (currentPage == Page.CAMERA) cameraInventoryView?.text = text
            }
        }.start()
    }

    private fun refreshLog() {
        logView?.text = RoverRuntimeState.logSnapshot()
    }

    private fun saveSystemSettings() {
        val old = RoverSettings.load(this)
        val cfg = old.copy(
            name = nameView?.text?.toString()?.trim().orEmpty().ifEmpty { "android-rover" },
            serverUrl = serverView?.text?.toString()?.trim().orEmpty().ifEmpty { old.serverUrl },
        )
        RoverSettings.save(this, cfg)
        RoverRuntimeState.log("UI saved SYSTEM settings")
        sendServiceAction(RoverService.ACTION_RESTART)
        refreshVisiblePage()
    }

    private fun saveRoombaSettings() {
        val old = RoverSettings.load(this)
        val line = BrcLine.entries.getOrElse(brcLineView?.selectedItemPosition ?: 0) { BrcLine.RTS }
        val cfg = old.copy(
            baud = baudView?.text?.toString()?.toIntOrNull() ?: old.baud,
            maxWheelSpeed = speedView?.text?.toString()?.toIntOrNull() ?: old.maxWheelSpeed,
            brcLine = line,
            brcActiveLow = brcActiveLowView?.isChecked ?: old.brcActiveLow,
            brcPulseEveryMs = brcEveryView?.text?.toString()?.toLongOrNull() ?: old.brcPulseEveryMs,
            brcPulseWidthMs = brcWidthView?.text?.toString()?.toLongOrNull() ?: old.brcPulseWidthMs,
        )
        RoverSettings.save(this, cfg)
        RoverRuntimeState.log("UI saved ROOMBA settings")
        sendServiceAction(RoverService.ACTION_RESTART)
        refreshVisiblePage()
    }

    private fun saveCameraSettings() {
        val old = RoverSettings.load(this)
        val cfg = old.copy(
            cameraId = cameraIdView?.text?.toString()?.trim().orEmpty().ifEmpty { old.cameraId },
        )
        RoverSettings.save(this, cfg)
        RoverRuntimeState.log("UI saved CAMERA settings cameraId=${cfg.cameraId}")
        refreshVisiblePage()
    }

    private fun copyAllDiagnostics() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val cameraInventory = cameraInventoryView?.text?.toString() ?: "not read in this UI session"
        val text = buildString {
            appendLine("=== SYSTEM ===")
            appendLine(systemSnapshot())
            appendLine()
            appendLine("=== ROOMBA ===")
            appendLine(roombaSnapshot())
            appendLine()
            appendLine("=== CAMERA ===")
            appendLine(cameraSummarySnapshot())
            appendLine(cameraInventory)
            appendLine()
            appendLine("=== LOG ===")
            append(RoverRuntimeState.logSnapshot())
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("roverd diagnostics", text))
        RoverRuntimeState.log("UI all diagnostics copied to clipboard")
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
            refreshVisiblePage()
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            RoverRuntimeState.log("CAMERA permission already granted")
            refreshVisiblePage()
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
            refreshVisiblePage()
        }
    }

    private fun requestBatteryOptimizationExemption(userInitiated: Boolean) {
        if (Build.VERSION.SDK_INT < 23) {
            if (userInitiated) RoverRuntimeState.log("POWER Doze exemption not applicable on API ${Build.VERSION.SDK_INT}")
            return
        }

        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            if (userInitiated) RoverRuntimeState.log("POWER battery optimization exemption already granted")
            return
        }

        RoverRuntimeState.log("POWER requesting battery optimization / Doze exemption")
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching {
            startActivity(direct)
        }.onFailure { directFailure ->
            RoverRuntimeState.log("POWER direct exemption request failed: ${directFailure.message}; opening battery optimization settings")
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure { settingsFailure ->
                RoverRuntimeState.log("POWER battery optimization settings failed: ${settingsFailure.stackTraceToString()}")
            }
        }
    }
}
