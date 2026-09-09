package land.otter.roverd

object MicRuntimeState {
    @Volatile var running: Boolean = false
    @Volatile var state: String = "Stopped"
    @Volatile var connected: Boolean = false
    @Volatile var publishUrl: String = ""
    @Volatile var codec: String = "Opus"
    @Volatile var encoder: String = ""
    @Volatile var sampleRate: Int = 0
    @Volatile var channels: Int = 0
    @Volatile var configuredBitrate: Int = 0
    @Volatile var measuredBitrate: Long = 0
    @Volatile var amplitude: Float = 0f
    @Volatile var sentFrames: Long = 0
    @Volatile var sentBytes: Long = 0
    @Volatile var droppedFrames: Long = 0
    @Volatile var cacheItems: Int = 0
    @Volatile var reconnects: Long = 0
    @Volatile var lastFrameAtMs: Long = 0
    @Volatile var lastError: String = ""

    fun resetCounters() {
        measuredBitrate = 0
        amplitude = 0f
        sentFrames = 0
        sentBytes = 0
        droppedFrames = 0
        cacheItems = 0
        reconnects = 0
        lastFrameAtMs = 0
    }

    fun setConfig(url: String, rate: Int, channelCount: Int, bitrate: Int, encoderName: String = "") {
        publishUrl = url
        sampleRate = rate
        channels = channelCount
        configuredBitrate = bitrate
        encoder = encoderName
    }

    fun setConnection(isConnected: Boolean, newState: String, error: String = "") {
        connected = isConnected
        state = newState
        running = isConnected || running
        if (error.isNotBlank()) lastError = error
    }

    fun updateStats(frames: Long, bytes: Long, dropped: Long, cache: Int) {
        if (frames > sentFrames) lastFrameAtMs = System.currentTimeMillis()
        sentFrames = frames.coerceAtLeast(0)
        sentBytes = bytes.coerceAtLeast(0)
        droppedFrames = dropped.coerceAtLeast(0)
        cacheItems = cache.coerceAtLeast(0)
    }

    fun markStopped(newState: String = "Stopped", error: String = "") {
        running = false
        connected = false
        state = newState
        measuredBitrate = 0
        amplitude = 0f
        if (error.isNotBlank()) lastError = error
    }

    fun snapshot(): String {
        val age = if (lastFrameAtMs == 0L) "never" else "${System.currentTimeMillis() - lastFrameAtMs} ms ago"
        return buildString {
            appendLine("pipeline      : $state")
            appendLine("running       : $running")
            appendLine("RTSP connected: $connected")
            appendLine("codec         : $codec")
            appendLine("encoder       : ${encoder.ifBlank { "auto / not reported" }}")
            appendLine("format        : ${sampleRate} Hz / ${channels} ch")
            appendLine("bitrate cfg   : $configuredBitrate bps")
            appendLine("bitrate live  : $measuredBitrate bps")
            appendLine("amplitude     : $amplitude")
            appendLine("sent          : $sentFrames packets / $sentBytes bytes")
            appendLine("dropped       : $droppedFrames")
            appendLine("queue items   : $cacheItems")
            appendLine("reconnects    : $reconnects")
            appendLine("last packet   : $age")
            appendLine("publish URL   : ${publishUrl.ifBlank { "-" }}")
            append("last error    : ${lastError.ifBlank { "-" }}")
        }
    }
}
