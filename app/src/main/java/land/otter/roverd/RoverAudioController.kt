package land.otter.roverd

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.speech.tts.TextToSpeech
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

object RoverAudioController {
    @Volatile var hornGain: Float = 1f
        private set
    @Volatile var ttsGain: Float = 1f
        private set
    @Volatile var forwardGain: Float = 1f
        private set
    @Volatile var hornActive: Boolean = false
        private set
    @Volatile var ttsState: String = "Not initialized"
        private set
    @Volatile var lastError: String = ""
        private set

    private var appContext: Context? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val hornStop = AtomicBoolean(false)
    private var hornThread: Thread? = null

    @Synchronized
    fun initialize(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        if (tts != null) return
        ttsState = "Initializing Android TTS"
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                ttsState = "Ready"
                runCatching { tts?.language = Locale.getDefault() }
                RoverRuntimeState.log("AUDIO Android TTS ready")
            } else {
                ttsReady = false
                ttsState = "Initialization failed ($status)"
                lastError = ttsState
                RoverRuntimeState.log("AUDIO Android TTS initialization failed status=$status")
            }
        }
    }

    fun handleTts(payload: JSONObject) {
        val context = appContext ?: throw IllegalStateException("Android audio controller not initialized")
        val cfg = RoverSettings.load(context)
        if (!cfg.ttsEnabled) throw IllegalStateException("tts disabled on rover")
        if (!payload.optBoolean("speak", false)) return
        var text = payload.optString("text").trim()
        if (text.isEmpty()) throw IllegalArgumentException("tts text required")
        if (text.length > 512) text = text.take(512)
        if (!ttsReady) throw IllegalStateException("Android TTS not ready: $ttsState")

        val engine = payload.optString("engine").trim()
        if (engine.isNotEmpty() && engine.lowercase() !in setOf("android", "default", "system")) {
            RoverRuntimeState.log("AUDIO TTS requested engine=$engine; Android uses current system TTS engine")
        }

        val requestedPitch = payload.optDouble("pitch", 0.0).toFloat()
        val requestedSpeed = payload.optDouble("speed", 0.0).toFloat()
        val pitch = when {
            requestedPitch in 0.1f..3.0f -> requestedPitch
            requestedPitch > 3f -> (requestedPitch / 50f).coerceIn(0.1f, 3f)
            else -> cfg.ttsPitch
        }
        val speed = if (requestedSpeed > 0f) requestedSpeed.coerceIn(0.25f, 3f) else cfg.ttsRate

        val active = tts ?: throw IllegalStateException("Android TTS unavailable")
        active.setPitch(pitch)
        active.setSpeechRate(speed)

        val requestedVoice = payload.optString("voice").trim().ifEmpty { cfg.ttsVoice }
        if (Build.VERSION.SDK_INT >= 21 && requestedVoice.isNotEmpty()) {
            active.voices?.firstOrNull { it.name == requestedVoice }?.let { active.voice = it }
        }

        val volume = (cfg.ttsVolume * ttsGain).coerceIn(0f, 1f)
        val params = HashMap<String, String>()
        params[TextToSpeech.Engine.KEY_PARAM_VOLUME] = volume.toString()
        @Suppress("DEPRECATION")
        val result = active.speak(text, TextToSpeech.QUEUE_ADD, params)
        if (result == TextToSpeech.ERROR) throw IllegalStateException("Android TTS speak() failed")
        ttsState = "Speaking / queued"
        RoverRuntimeState.log("AUDIO TTS queued chars=${text.length} pitch=$pitch rate=$speed volume=$volume voice=${requestedVoice.ifBlank { "default" }}")
    }

    fun handleHorn(payload: JSONObject) {
        val context = appContext ?: throw IllegalStateException("Android audio controller not initialized")
        val cfg = RoverSettings.load(context)
        if (!cfg.hornEnabled) throw IllegalStateException("horn disabled")
        when (payload.optString("action").trim().lowercase()) {
            "start", "on", "honk" -> {
                val waveform = payload.optString("waveform", "saw").trim().lowercase().let {
                    if (it == "sine") "sine" else "saw"
                }
                val array = payload.optJSONArray("freqs")
                val freqs = ArrayList<Double>()
                if (array != null) {
                    for (i in 0 until minOf(array.length(), 4)) {
                        val f = array.optDouble(i, 0.0)
                        if (f > 0.0) freqs += f
                    }
                }
                if (freqs.isEmpty()) {
                    stopHorn()
                    return
                }
                startHorn(waveform, freqs, cfg)
            }
            "stop", "off" -> stopHorn()
            else -> throw IllegalArgumentException("unsupported horn action: ${payload.optString("action")}")
        }
    }

    @Synchronized
    private fun startHorn(waveform: String, freqs: List<Double>, cfg: RoverConfig) {
        if (hornThread?.isAlive == true) return
        hornStop.set(false)
        hornActive = true
        val thread = Thread({ runHorn(waveform, freqs, cfg) }, "roverd-horn")
        hornThread = thread
        thread.start()
        RoverRuntimeState.log("AUDIO horn start waveform=$waveform freqs=$freqs")
    }

    @Synchronized
    fun stopHorn() {
        hornStop.set(true)
        hornActive = false
    }

    @Suppress("DEPRECATION")
    private fun runHorn(waveform: String, freqs: List<Double>, cfg: RoverConfig) {
        val rate = 48_000
        val minBuffer = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        var track: AudioTrack? = null
        try {
            track = AudioTrack(
                AudioManager.STREAM_MUSIC,
                rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
                AudioTrack.MODE_STREAM,
            )
            track.play()
            val chunk = ShortArray(512)
            val phases = DoubleArray(freqs.size)
            val increments = freqs.map { 2.0 * PI * it / rate }
            val maxFrames = (rate.toLong() * cfg.hornMaxDurationMs.coerceAtLeast(100L) / 1000L).coerceAtLeast(rate.toLong())
            var frame = 0L
            val baseVolume = (cfg.hornVolume * hornGain).coerceIn(0f, 4f)
            while (!hornStop.get() && frame < maxFrames) {
                for (i in chunk.indices) {
                    var sample = 0.0
                    for (j in freqs.indices) {
                        sample += if (waveform == "sine") {
                            sin(phases[j])
                        } else {
                            2.0 * (phases[j] / (2.0 * PI)) - 1.0
                        }
                        phases[j] += increments[j]
                        if (phases[j] >= 2.0 * PI) phases[j] -= 2.0 * PI
                    }
                    sample /= max(1, freqs.size)
                    val attack = (frame / (rate * 0.020)).coerceIn(0.0, 1.0)
                    sample *= baseVolume * attack
                    chunk[i] = (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
                    frame++
                    if (frame >= maxFrames) break
                }
                track.write(chunk, 0, chunk.size)
            }
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            RoverRuntimeState.log("AUDIO horn failure: ${t.stackTraceToString()}")
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            hornActive = false
            hornThread = null
            hornStop.set(false)
            RoverRuntimeState.log("AUDIO horn stopped")
        }
    }

    fun handleAudioLevels(payload: JSONObject) {
        payload.opt("hornGain")?.let { hornGain = payload.optDouble("hornGain", hornGain.toDouble()).toFloat().coerceIn(0f, 4f) }
        payload.opt("ttsGain")?.let { ttsGain = payload.optDouble("ttsGain", ttsGain.toDouble()).toFloat().coerceIn(0f, 4f) }
        payload.opt("forwardGain")?.let { forwardGain = payload.optDouble("forwardGain", forwardGain.toDouble()).toFloat().coerceIn(0f, 4f) }
        AudioPlaybackRuntimeState.applyForwardGain(forwardGain)
        appContext?.let { context ->
            val intent = Intent(context, AudioPlaybackService::class.java).setAction(AudioPlaybackService.ACTION_APPLY_LEVEL)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure {
                RoverRuntimeState.log("AUDIO forward gain apply service signal failed: ${it.message}")
            }
        }
        RoverRuntimeState.log("AUDIO levels horn=$hornGain tts=$ttsGain forward=$forwardGain")
    }

    fun snapshot(): String = buildString {
        appendLine("TTS state     : $ttsState")
        appendLine("TTS gain      : $ttsGain x")
        appendLine("horn active   : $hornActive")
        appendLine("horn gain     : $hornGain x")
        appendLine("forward gain  : $forwardGain x")
        append("audio error   : ${lastError.ifBlank { "-" }}")
    }
}
