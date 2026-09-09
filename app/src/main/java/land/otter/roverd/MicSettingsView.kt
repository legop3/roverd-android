package land.otter.roverd

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

class MicSettingsView(context: Context) : ScrollView(context) {
    companion object {
        const val MIC_PERMISSION_REQUEST = 3
    }

    private val activity = context as Activity
    private val handler = Handler(Looper.getMainLooper())
    private val sources = MicDiagnostics.sourceOptions()
    private var rates = MicDiagnostics.supportedOpusSampleRates()

    private val runtimeView = diagnostic(11f)
    private val inventoryView = diagnostic(10f)
    private val enabledView = CheckBox(context)
    private val sourceView = Spinner(context)
    private val rateView = Spinner(context)
    private val channelView = Spinner(context)
    private val bitrateView = EditText(context)
    private val gainView = EditText(context)
    private val echoView = CheckBox(context)
    private val noiseView = CheckBox(context)
    private val publishUrlView = EditText(context)

    private val refreshTask = object : Runnable {
        override fun run() {
            refreshRuntime()
            handler.postDelayed(this, 750L)
        }
    }

    init {
        isFillViewport = true
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        addView(root)

        root.addView(TextView(context).apply {
            text = "ROVERD ANDROID / MIC / AUDIO CAPTURE"
            textSize = 21f
            typeface = Typeface.MONOSPACE
        })

        root.addView(runtimeView)

        section(root, "MICROPHONE PUBLISHER")
        val cfg = RoverSettings.load(context)

        enabledView.text = "Enable microphone Opus -> MediaMTX publishing"
        enabledView.isChecked = cfg.micEnabled
        root.addView(enabledView)

        label(root, "Android audio source")
        sourceView.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, sources.map { "${it.value} — ${it.label}" })
        sourceView.setSelection(sources.indexOfFirst { it.value == cfg.micAudioSource }.coerceAtLeast(0))
        root.addView(sourceView)

        if (cfg.micSampleRate !in rates) rates = (rates + cfg.micSampleRate).distinct().sortedDescending()
        label(root, "Opus sample rate")
        rateView.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, rates.map { "$it Hz" })
        rateView.setSelection(rates.indexOf(cfg.micSampleRate).coerceAtLeast(0))
        root.addView(rateView)

        label(root, "Channels")
        val channelLabels = listOf("1 — MONO", "2 — STEREO")
        channelView.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, channelLabels)
        channelView.setSelection(if (cfg.micChannels >= 2) 1 else 0)
        root.addView(channelView)

        label(root, "Opus bitrate (bits/sec, 16000..510000)")
        bitrateView.setText(cfg.micBitrate.toString())
        root.addView(bitrateView)

        label(root, "Microphone gain before Opus (-20..30 dB)")
        gainView.setText(cfg.micGainDb.toString())
        root.addView(gainView)
        root.addView(diagnostic(10f).apply {
            text = "Gain is applied to raw PCM before encoding. 0 dB = unchanged; +12 dB is the Android default here; the Pi publisher uses a +20 dB microphone boost."
        })

        echoView.text = "Enable Android acoustic echo canceler"
        echoView.isChecked = cfg.micEchoCanceler
        root.addView(echoView)

        noiseView.text = "Enable Android noise suppressor"
        noiseView.isChecked = cfg.micNoiseSuppressor
        root.addView(noiseView)

        root.addView(diagnostic(10f).apply {
            text = "Shared MediaMTX RTSP port: ${cfg.mediaRtspPort} (SYSTEM tab)"
        })

        label(root, "RTSP publish URL override (blank = server host + rover-name-audio)")
        publishUrlView.setText(cfg.micPublishUrl)
        root.addView(publishUrlView)

        root.addView(diagnostic(10f).apply {
            text = "Audio path    : Android AudioRecord -> PCM gain -> MediaCodec Opus -> RTSP/RTP over TCP -> MediaMTX\n" +
                "effective URL : ${effectiveUrl(cfg)}\n" +
                "Pi contract    : separate <rover>-audio stream; camera and mic are independent"
        })

        button(root, "REQUEST MICROPHONE PERMISSION") { requestMicPermission() }
        button(root, "SAVE MIC SETTINGS + APPLY") { saveAndApply(false) }
        button(root, "START / RESTART MIC STREAM") { saveAndApply(true) }
        button(root, "STOP MIC STREAM") {
            enabledView.isChecked = false
            val old = RoverSettings.load(context)
            RoverSettings.save(context, old.copy(micEnabled = false))
            stopPublisher()
            reconnectRoverHello()
            MicRuntimeState.markStopped("Stopped from UI")
            refreshRuntime()
        }

        section(root, "RAW INPUT / CODEC INVENTORY")
        button(root, "REFRESH RAW MIC INVENTORY") { refreshInventory() }
        inventoryView.text = "Not read yet. Press REFRESH RAW MIC INVENTORY."
        root.addView(inventoryView)

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

    private fun saveAndApply(forceStart: Boolean) {
        val old = RoverSettings.load(context)
        val source = sources.getOrNull(sourceView.selectedItemPosition)?.value ?: old.micAudioSource
        val rate = rates.getOrNull(rateView.selectedItemPosition) ?: old.micSampleRate
        val channels = if (channelView.selectedItemPosition == 1) 2 else 1
        val enabled = forceStart || enabledView.isChecked
        if (forceStart) enabledView.isChecked = true
        val cfg = old.copy(
            micEnabled = enabled,
            micAudioSource = source,
            micSampleRate = rate,
            micChannels = channels,
            micBitrate = (bitrateView.text.toString().toIntOrNull() ?: old.micBitrate).coerceIn(16_000, 510_000),
            micGainDb = (gainView.text.toString().toIntOrNull() ?: old.micGainDb).coerceIn(-20, 30),
            micEchoCanceler = echoView.isChecked,
            micNoiseSuppressor = noiseView.isChecked,
            micPublishUrl = publishUrlView.text.toString().trim(),
        )
        RoverSettings.save(context, cfg)
        RoverRuntimeState.log(
            "UI saved MIC settings enabled=${cfg.micEnabled} source=${cfg.micAudioSource} " +
                "rate=${cfg.micSampleRate} channels=${cfg.micChannels} bitrate=${cfg.micBitrate} gain=${cfg.micGainDb}dB " +
                "echo=${cfg.micEchoCanceler} noise=${cfg.micNoiseSuppressor} rtspPort=${cfg.mediaRtspPort} url=${effectiveUrl(cfg)}",
        )
        reconnectRoverHello()

        if (!cfg.micEnabled) {
            stopPublisher()
            refreshRuntime()
            return
        }
        if (!hasMicPermission()) {
            MicRuntimeState.markStopped("Microphone permission missing", "RECORD_AUDIO permission not granted")
            requestMicPermission()
            refreshRuntime()
            return
        }

        restartPublisher()
        refreshRuntime()
    }

    private fun hasMicPermission(): Boolean =
        Build.VERSION.SDK_INT < 23 || activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMicPermission() {
        if (Build.VERSION.SDK_INT < 23) {
            RoverRuntimeState.log("MIC permission is install-time on API ${Build.VERSION.SDK_INT}")
            return
        }
        if (hasMicPermission()) {
            RoverRuntimeState.log("MIC RECORD_AUDIO permission already granted")
            return
        }
        activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_REQUEST)
    }

    private fun restartPublisher() {
        RoverRuntimeState.log("UI requesting in-service microphone pipeline restart")
        startServiceCompat(Intent(context, MicPublisherService::class.java).setAction(MicPublisherService.ACTION_RESTART))
    }

    private fun stopPublisher() {
        RoverRuntimeState.log("UI stopping microphone publisher service")
        context.stopService(Intent(context, MicPublisherService::class.java))
    }

    private fun reconnectRoverHello() {
        startServiceCompat(Intent(context, RoverService::class.java).setAction(RoverService.ACTION_RECONNECT_SERVER))
    }

    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    private fun refreshRuntime() {
        val cfg = RoverSettings.load(context)
        val permission = if (hasMicPermission()) "GRANTED" else "NOT GRANTED"
        runtimeView.text = buildString {
            appendLine("enabled       : ${cfg.micEnabled}")
            appendLine("permission    : $permission")
            appendLine("source        : ${sources.firstOrNull { it.value == cfg.micAudioSource }?.label ?: cfg.micAudioSource}")
            appendLine("configured    : ${cfg.micSampleRate} Hz / ${cfg.micChannels} ch / ${cfg.micBitrate} bps / ${cfg.micGainDb} dB gain")
            appendLine("RTSP port     : ${cfg.mediaRtspPort} (shared)")
            appendLine("effective URL : ${effectiveUrl(cfg)}")
            appendLine()
            append(MicRuntimeState.snapshot())
        }
    }

    private fun refreshInventory() {
        inventoryView.text = "Reading microphone / Opus inventory..."
        Thread {
            val text = runCatching { MicDiagnostics.snapshot(context) }
                .getOrElse { "MIC diagnostics failed: ${it.stackTraceToString()}" }
            activity.runOnUiThread { inventoryView.text = text }
        }.start()
    }

    private fun effectiveUrl(cfg: RoverConfig): String =
        runCatching { MediaUrl.micPublishUrl(cfg) }.getOrElse { "ERROR: ${it.message}" }

    private fun section(root: LinearLayout, text: String) {
        root.addView(TextView(context).apply {
            this.text = "\n--- $text ---"
            textSize = 16f
            typeface = Typeface.MONOSPACE
        })
    }

    private fun label(root: LinearLayout, text: String) {
        root.addView(TextView(context).apply { this.text = text })
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
