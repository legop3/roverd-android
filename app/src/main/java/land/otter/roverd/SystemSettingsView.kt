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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SystemSettingsView(context: Context) : ScrollView(context) {
    private val activity = context as Activity
    private val handler = Handler(Looper.getMainLooper())
    private val runtimeView = diagnostic(11f)
    private val nameView = EditText(context)
    private val descriptionView = EditText(context)
    private val colorView = EditText(context)
    private val serverView = EditText(context)
    private val rtspPortView = EditText(context)

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
            text = "ROVERD ANDROID / SYSTEM / ROVER SERVER"
            textSize = 21f
            typeface = Typeface.MONOSPACE
        })
        root.addView(runtimeView)

        section(root, "RUNTIME")
        button(root, "RESTART FULL ROVER RUNTIME") { signalRover(RoverService.ACTION_RESTART) }
        button(root, "REQUEST BATTERY / DOZE EXEMPTION") { requestBatteryOptimizationExemption() }
        button(root, "COPY SYSTEM DIAGNOSTICS + LOG") { copyDiagnostics() }

        val cfg = RoverSettings.load(context)
        section(root, "ROVER IDENTITY")
        field(root, "Rover name", nameView, cfg.name)
        field(root, "Description", descriptionView, cfg.description)
        field(root, "Color (#RRGGBB or blank)", colorView, cfg.color)
        field(root, "Server WebSocket URL", serverView, cfg.serverUrl)

        section(root, "MEDIAMTX")
        field(root, "Shared RTSP port (camera + mic + reverse audio)", rtspPortView, cfg.mediaRtspPort.toString())
        root.addView(diagnostic(10f).apply {
            text = "All three derived media URLs use this one RTSP listener port. Per-stream URL overrides on CAMERA/MIC/AUDIO still take precedence."
        })

        button(root, "SAVE SYSTEM SETTINGS + RESTART") { saveAndRestart() }
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

    private fun saveAndRestart() {
        val old = RoverSettings.load(context)
        val result = runCatching {
            old.copy(
                name = nameView.text.toString().trim().ifEmpty { "android-rover" },
                description = descriptionView.text.toString().trim(),
                color = RoverSettings.normalizeColor(colorView.text.toString()),
                serverUrl = serverView.text.toString().trim().ifEmpty { old.serverUrl },
                mediaRtspPort = (rtspPortView.text.toString().toIntOrNull() ?: old.mediaRtspPort).coerceIn(1, 65_535),
            )
        }
        val cfg = result.getOrElse {
            RoverRuntimeState.log("SYSTEM settings rejected: ${it.message}")
            runtimeView.text = "SETTINGS ERROR: ${it.message}\n\n${runtimeSnapshot()}"
            return
        }

        RoverSettings.save(context, cfg)
        colorView.setText(cfg.color)
        RoverRuntimeState.log(
            "UI saved SYSTEM identity name=${cfg.name} description=${cfg.description.take(80)} color=${cfg.color} " +
                "server=${cfg.serverUrl} rtspPort=${cfg.mediaRtspPort}",
        )

        signalRover(RoverService.ACTION_RESTART)
        restartConfiguredMedia(cfg)
        refreshRuntime()
    }

    private fun restartConfiguredMedia(cfg: RoverConfig) {
        if (cfg.cameraEnabled && Build.VERSION.SDK_INT >= 21 &&
            (Build.VERSION.SDK_INT < 23 || activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        ) {
            startServiceCompat(Intent(context, CameraPublisherService::class.java).setAction(CameraPublisherService.ACTION_RESTART))
        }
        if (cfg.micEnabled &&
            (Build.VERSION.SDK_INT < 23 || activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        ) {
            startServiceCompat(Intent(context, MicPublisherService::class.java).setAction(MicPublisherService.ACTION_RESTART))
        }
        if (cfg.audioPlaybackEnabled) {
            startServiceCompat(Intent(context, AudioPlaybackService::class.java).setAction(AudioPlaybackService.ACTION_RESTART))
        }
    }

    private fun runtimeSnapshot(): String {
        val cfg = RoverSettings.load(context)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOptimization = if (Build.VERSION.SDK_INT >= 23) {
            if (power.isIgnoringBatteryOptimizations(context.packageName)) "EXEMPT" else "ACTIVE"
        } else "n/a"
        val powerSaver = if (Build.VERSION.SDK_INT >= 21) power.isPowerSaveMode.toString() else "n/a"
        val deviceIdle = if (Build.VERSION.SDK_INT >= 23) power.isDeviceIdleMode.toString() else "n/a"
        return buildString {
            appendLine("device        : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android       : ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("name          : ${cfg.name}")
            appendLine("description   : ${cfg.description.ifBlank { "-" }}")
            appendLine("color         : ${cfg.color.ifBlank { "-" }}")
            appendLine("server URL    : ${cfg.serverUrl}")
            appendLine("MediaMTX RTSP : ${cfg.mediaRtspPort}")
            appendLine("WS connected  : ${RoverRuntimeState.serverConnected}")
            appendLine("CPU wake lock : ${RoverRuntimeState.wakeLockHeld}")
            appendLine("Wi-Fi lock    : ${RoverRuntimeState.wifiLockHeld}")
            appendLine("battery opt   : $batteryOptimization")
            appendLine("power saver   : $powerSaver")
            appendLine("device idle   : $deviceIdle")
            appendLine("commands      : ${RoverRuntimeState.commandCount}")
            append("last command  : ${RoverRuntimeState.lastCommand.ifBlank { "-" }}")
        }
    }

    private fun refreshRuntime() {
        runtimeView.text = runtimeSnapshot()
    }

    private fun copyDiagnostics() {
        val text = buildString {
            appendLine(runtimeSnapshot())
            appendLine()
            appendLine("=== HEADLIGHT ===")
            appendLine(HeadlightController.snapshot())
            appendLine()
            appendLine("=== LOG ===")
            append(RoverRuntimeState.logSnapshot())
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("roverd system diagnostics", text))
        RoverRuntimeState.log("UI system diagnostics copied")
    }

    private fun signalRover(action: String) {
        startServiceCompat(Intent(context, RoverService::class.java).setAction(action))
    }

    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < 23) return
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (power.isIgnoringBatteryOptimizations(context.packageName)) return
        runCatching {
            activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            })
        }.onFailure {
            runCatching { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun field(root: LinearLayout, label: String, view: EditText, value: String) {
        root.addView(TextView(context).apply { text = label })
        view.setText(value)
        root.addView(view)
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
