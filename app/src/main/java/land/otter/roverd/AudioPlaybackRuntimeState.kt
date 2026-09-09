package land.otter.roverd

object AudioPlaybackRuntimeState {
    @Volatile var running: Boolean = false
    @Volatile var playing: Boolean = false
    @Volatile var state: String = "Stopped"
    @Volatile var readUrl: String = ""
    @Volatile var configuredVolume: Int = 100
    @Volatile var effectiveVolume: Int = 100
    @Volatile var networkCachingMs: Int = 100
    @Volatile var forwardGain: Float = 1f
    @Volatile var reconnects: Long = 0
    @Volatile var lastEventAtMs: Long = 0
    @Volatile var lastPlayingAtMs: Long = 0
    @Volatile var lastError: String = ""

    fun setConfig(url: String, volume: Int, cacheMs: Int) {
        readUrl = url
        configuredVolume = volume
        networkCachingMs = cacheMs
        applyForwardGain(forwardGain)
    }

    fun applyForwardGain(gain: Float) {
        forwardGain = gain.coerceIn(0f, 4f)
        effectiveVolume = (configuredVolume * forwardGain).toInt().coerceIn(0, 200)
    }

    fun event(newState: String, isPlaying: Boolean? = null, error: String = "") {
        state = newState
        running = true
        isPlaying?.let {
            playing = it
            if (it) lastPlayingAtMs = System.currentTimeMillis()
        }
        lastEventAtMs = System.currentTimeMillis()
        if (error.isNotBlank()) lastError = error
    }

    fun stopped(newState: String = "Stopped", error: String = "") {
        running = false
        playing = false
        state = newState
        lastEventAtMs = System.currentTimeMillis()
        if (error.isNotBlank()) lastError = error
    }

    fun snapshot(): String {
        val now = System.currentTimeMillis()
        fun age(value: Long): String = if (value == 0L) "never" else "${now - value} ms ago"
        return buildString {
            appendLine("pipeline      : $state")
            appendLine("running       : $running")
            appendLine("playing       : $playing")
            appendLine("decoder       : LibVLC RTSP/Opus")
            appendLine("volume cfg    : $configuredVolume")
            appendLine("forward gain  : $forwardGain x")
            appendLine("volume active : $effectiveVolume")
            appendLine("network cache : ${networkCachingMs} ms")
            appendLine("reconnects    : $reconnects")
            appendLine("last event    : ${age(lastEventAtMs)}")
            appendLine("last playing  : ${age(lastPlayingAtMs)}")
            appendLine("read URL      : ${readUrl.ifBlank { "-" }}")
            append("last error    : ${lastError.ifBlank { "-" }}")
        }
    }
}
