package land.otter.roverd

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

class AudioSettingsView(context: Context) : ScrollView(context) {
    private val activity = context as Activity
    private val handler = Handler(Looper.getMainLooper())

    private val runtimeView = diagnostic(11f)
    private val inventoryView = diagnostic(10f)
    private val playbackEnabledView = CheckBox(context)
    private val playbackVolumeView = EditText(context)
    private val networkCacheView = EditText(context)
    private val playbackUrlView = EditText(context)
    private val ttsEnabledView = CheckBox(context)
    private val ttsPitchView = EditText(context)
    private val ttsRateView = EditText(context)
    private val ttsVoiceView = EditText(context)
    private val ttsVolumeView = EditText(context)
    private val ttsTestView = EditText(context)
    private val hornEnabledView = CheckBox(context)
    private val hornVolumeView = EditText(context)
    private val hornSineGainView = EditText(context)
    private val hornSawGainView = EditText(context)
    private val hornMaxDurationView = EditText(context)

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
            text = "ROVERD ANDROID / AUDIO PLAYBACK / TTS / HORN"
            textSize = 21f
            typeface = Typeface.MONOSPACE
        })
        root.addView(runtimeView)

        val cfg = RoverSettings.load(context)

        section(root, "REVERSE AUDIO / SPEAKER")
        playbackEnabledView.text = "Enable <rover>-fwd RTSP/Opus playback"
        playbackEnabledView.isChecked = cfg.audioPlaybackEnabled
        root.addView(playbackEnabledView)
        label(root, "LibVLC volume (0..200, 100 = normal)")
        playbackVolumeView.setText(cfg.audioPlaybackVolume.toString())
        root.addView(playbackVolumeView)
        label(root, "RTSP network cache (ms, 0..2000)")
        networkCacheView.setText(cfg.audioPlaybackNetworkCachingMs.toString())
        root.addView(networkCacheView)
        root.addView(diagnostic(10f).apply {
            text = "Shared MediaMTX RTSP port: ${cfg.mediaRtspPort} (SYSTEM tab)"
        })
        label(root, "RTSP read URL override (blank = server host + rover-name-fwd)")
        playbackUrlView.setText(cfg.audioPlaybackUrl)
        root.addView(playbackUrlView)
        root.addView(diagnostic(10f).apply {
            text = "Audio path    : MediaMTX <rover>-fwd RTSP/TCP -> LibVLC Opus decode -> Android AudioTrack\n" +
                "effective URL : ${effectiveUrl(cfg)}\n" +
                "server format : Opus from 16 kHz mono source; browser/server contract unchanged"
        })
        button(root, "SAVE AUDIO SETTINGS + APPLY") { saveAndApply(forceStart = false) }
        button(root, "START / RESTART REVERSE AUDIO") { saveAndApply(forceStart = true) }
        button(root, "STOP REVERSE AUDIO") {
            playbackEnabledView.isChecked = false
            val old = RoverSettings.load(context)
            RoverSettings.save(context, old.copy(audioPlaybackEnabled = false))
            stopPlayback()
            reconnectRoverHello()
            refreshRuntime()
        }

        section(root, "ANDROID TTS")
        ttsEnabledView.text = "Enable TTS commands"
        ttsEnabledView.isChecked = cfg.ttsEnabled
        root.addView(ttsEnabledView)
        label(root, "Default pitch multiplier (0.1..3.0)")
        ttsPitchView.setText(cfg.ttsPitch.toString())
        root.addView(ttsPitchView)
        label(root, "Default speech-rate multiplier (0.25..3.0)")
        ttsRateView.setText(cfg.ttsRate.toString())
        root.addView(ttsRateView)
        label(root, "Default Android TTS voice name (blank = system default)")
        ttsVoiceView.setText(cfg.ttsVoice)
        root.addView(ttsVoiceView)
        label(root, "TTS volume (0.0..1.0)")
        ttsVolumeView.setText(cfg.ttsVolume.toString())
        root.addView(ttsVolumeView)
        label(root, "TTS test text")
        ttsTestView.setText("Hello from Roomba Rover")
        root.addView(ttsTestView)
        button(root, "SPEAK TEST") {
            saveSettingsOnly()
            runCatching {
                RoverAudioController.handleTts(
                    JSONObject()
                        .put("text", ttsTestView.text.toString())
                        .put("speak", true),
                )
            }.onFailure { RoverRuntimeState.log("AUDIO TTS test failed: ${it.stackTraceToString()}") }
            refreshRuntime()
        }

        section(root, "HORN")
        hornEnabledView.text = "Enable horn commands"
        hornEnabledView.isChecked = cfg.hornEnabled
        root.addView(hornEnabledView)
        label(root, "Horn base volume (0.0..1.0)")
        hornVolumeView.setText(cfg.hornVolume.toString())
        root.addView(hornVolumeView)
        label(root, "Sine waveform gain (0.0..4.0)")
        hornSineGainView.setText(cfg.hornSineGain.toString())
        root.addView(hornSineGainView)
        label(root, "Saw waveform gain (0.0..4.0)")
        hornSawGainView.setText(cfg.hornSawGain.toString())
        root.addView(hornSawGainView)
        label(root, "Horn maximum duration (ms)")
        hornMaxDurationView.setText(cfg.hornMaxDurationMs.toString())
        root.addView(hornMaxDurationView)
        button(root, "START TEST HORN") {
            saveSettingsOnly()
            runCatching {
                RoverAudioController.handleHorn(
                    JSONObject()
                        .put("action", "start")
                        .put("waveform", "saw")
                        .put("freqs", JSONArray().put(330.0).put(440.0)),
                )
            }.onFailure { RoverRuntimeState.log("AUDIO horn test failed: ${it.stackTraceToString()}") }
            refreshRuntime()
        }
        button(root, "STOP HORN") {
            RoverAudioController.stopHorn()
            refreshRuntime()
        }

        section(root, "RAW OUTPUT INVENTORY")
        button(root, "REFRESH AUDIO OUTPUT INVENTORY") { refreshInventory() }
        inventoryView.text = "Not read yet. Press REFRESH AUDIO OUTPUT INVENTORY."
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

    private fun saveSettingsOnly(): RoverConfig {
        val old = RoverSettings.load(context)
        val cfg = old.copy(
            audioPlaybackEnabled = playbackEnabledView.isChecked,
            audioPlaybackVolume = (playbackVolumeView.text.toString().toIntOrNull() ?: old.audioPlaybackVolume).coerceIn(0, 200),
            audioPlaybackNetworkCachingMs = (networkCacheView.text.toString().toIntOrNull() ?: old.audioPlaybackNetworkCachingMs).coerceIn(0, 2_000),
            audioPlaybackUrl = playbackUrlView.text.toString().trim(),
            ttsEnabled = ttsEnabledView.isChecked,
            ttsPitch = (ttsPitchView.text.toString().toFloatOrNull() ?: old.ttsPitch).coerceIn(0.1f, 3.0f),
            ttsRate = (ttsRateView.text.toString().toFloatOrNull() ?: old.ttsRate).coerceIn(0.25f, 3.0f),
            ttsVoice = ttsVoiceView.text.toString().trim(),
            ttsVolume = (ttsVolumeView.text.toString().toFloatOrNull() ?: old.ttsVolume).coerceIn(0f, 1f),
            hornEnabled = hornEnabledView.isChecked,
            hornVolume = (hornVolumeView.text.toString().toFloatOrNull() ?: old.hornVolume).coerceIn(0f, 1f),
            hornSineGain = (hornSineGainView.text.toString().toFloatOrNull() ?: old.hornSineGain).coerceIn(0f, 4f),
            hornSawGain = (hornSawGainView.text.toString().toFloatOrNull() ?: old.hornSawGain).coerceIn(0f, 4f),
            hornMaxDurationMs = (hornMaxDurationView.text.toString().toLongOrNull() ?: old.hornMaxDurationMs).coerceIn(100L, 60_000L),
        )
        RoverSettings.save(context, cfg)
        RoverRuntimeState.log(
            "UI saved AUDIO settings playback=${cfg.audioPlaybackEnabled} volume=${cfg.audioPlaybackVolume} " +
                "cacheMs=${cfg.audioPlaybackNetworkCachingMs} tts=${cfg.ttsEnabled} horn=${cfg.hornEnabled} " +
                "sineGain=${cfg.hornSineGain} sawGain=${cfg.hornSawGain} rtspPort=${cfg.mediaRtspPort} url=${effectiveUrl(cfg)}",
        )
        return cfg
    }

    private fun saveAndApply(forceStart: Boolean) {
        if (forceStart) playbackEnabledView.isChecked = true
        val cfg = saveSettingsOnly()
        reconnectRoverHello()
        if (cfg.audioPlaybackEnabled) restartPlayback() else stopPlayback()
        refreshRuntime()
    }

    private fun restartPlayback() {
        RoverRuntimeState.log("UI restarting reverse audio playback service")
        startServiceCompat(Intent(context, AudioPlaybackService::class.java).setAction(AudioPlaybackService.ACTION_RESTART))
    }

    private fun stopPlayback() {
        RoverRuntimeState.log("UI stopping reverse audio playback service")
        context.stopService(Intent(context, AudioPlaybackService::class.java))
    }

    private fun reconnectRoverHello() {
        startServiceCompat(Intent(context, RoverService::class.java).setAction(RoverService.ACTION_RECONNECT_SERVER))
    }

    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    private fun refreshRuntime() {
        val cfg = RoverSettings.load(context)
        runtimeView.text = buildString {
            appendLine("enabled       : ${cfg.audioPlaybackEnabled}")
            appendLine("RTSP port     : ${cfg.mediaRtspPort} (shared)")
            appendLine("effective URL : ${effectiveUrl(cfg)}")
            appendLine("horn cfg      : vol=${cfg.hornVolume} sine=${cfg.hornSineGain} saw=${cfg.hornSawGain} max=${cfg.hornMaxDurationMs}ms")
            appendLine()
            appendLine(AudioPlaybackRuntimeState.snapshot())
            appendLine()
            append(RoverAudioController.snapshot())
        }
    }

    private fun refreshInventory() {
        inventoryView.text = buildString {
            appendLine("Android API ${Build.VERSION.SDK_INT}")
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= 23) {
                val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                appendLine("output devices: ${devices.size}")
                devices.forEach { device ->
                    appendLine(
                        "id=${device.id} type=${device.type} product=${device.productName} " +
                            "rates=${device.sampleRates.joinToString()} channels=${device.channelCounts.joinToString()} " +
                            "encodings=${device.encodings.joinToString()}",
                    )
                }
            } else {
                appendLine("physical output enumeration requires API 23+")
                appendLine("speaker=${manager.isSpeakerphoneOn} bluetoothA2dp=${manager.isBluetoothA2dpOn} bluetoothSco=${manager.isBluetoothScoOn}")
            }
            appendLine()
            appendLine("LibVLC output: android_audiotrack / stereo compatibility mode")
            appendLine("Physical media routing follows Android system output routing.")
        }
    }

    private fun effectiveUrl(cfg: RoverConfig): String =
        runCatching { MediaUrl.audioPlaybackUrl(cfg) }.getOrElse { "ERROR: ${it.message}" }

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
