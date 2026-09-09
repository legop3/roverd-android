package land.otter.roverd

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Locale
import java.util.Random
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RtspH264Publisher(
    private val publishUrl: String,
) : Closeable {
    data class EncodedFrame(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val keyFrame: Boolean,
    )

    companion object {
        private const val RTP_PAYLOAD_TYPE = 96
        private const val RTP_CHANNEL = 0
        private const val MAX_RTP_PAYLOAD = 1200
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val RESPONSE_TIMEOUT_MS = 5000
    }

    private val closed = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<EncodedFrame>(45)
    private val random = Random()
    private val ssrc = random.nextInt()
    private var sequence = random.nextInt(65536)
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private var sessionId: String? = null
    @Volatile private var connected = false
    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null
    private var hasEverConnected = false

    private val worker = Thread(::runWorker, "roverd-rtsp-publisher").apply {
        isDaemon = true
        start()
    }

    fun isConnected(): Boolean = connected

    fun setCodecConfig(sps: ByteArray, pps: ByteArray) {
        this.sps = sps.copyOf()
        this.pps = pps.copyOf()
        RoverRuntimeState.log("CAMERA codec config SPS=${sps.size}B PPS=${pps.size}B")
    }

    fun enqueue(data: ByteArray, presentationTimeUs: Long, keyFrame: Boolean) {
        if (closed.get() || data.isEmpty()) return
        val frame = EncodedFrame(data.copyOf(), presentationTimeUs, keyFrame)
        if (!queue.offer(frame)) {
            queue.poll()
            if (!queue.offer(frame)) return
            RoverRuntimeState.recordCameraDrop()
        }
    }

    private fun runWorker() {
        while (!closed.get()) {
            val frame = try {
                queue.poll(500, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                if (closed.get()) break else continue
            } ?: continue

            val localSps = sps
            val localPps = pps
            if (localSps == null || localPps == null) {
                RoverRuntimeState.recordCameraDrop()
                continue
            }

            if (!connected) {
                try {
                    if (hasEverConnected) RoverRuntimeState.recordCameraReconnect()
                    connect(localSps, localPps)
                    hasEverConnected = true
                } catch (t: Throwable) {
                    RoverRuntimeState.setCameraPublisherState(false, "RTSP connect failed", t.message ?: t.javaClass.simpleName)
                    RoverRuntimeState.log("CAMERA RTSP connect failure: ${t.stackTraceToString()}")
                    closeConnection()
                    sleepRetry()
                    continue
                }
            }

            try {
                sendAccessUnit(frame)
            } catch (t: Throwable) {
                RoverRuntimeState.setCameraPublisherState(false, "RTSP send failed", t.message ?: t.javaClass.simpleName)
                RoverRuntimeState.log("CAMERA RTSP send failure: ${t.stackTraceToString()}")
                closeConnection()
                sleepRetry()
            }
        }
        closeConnection()
    }

    private fun connect(sps: ByteArray, pps: ByteArray) {
        val uri = URI(publishUrl)
        require(uri.scheme.equals("rtsp", ignoreCase = true)) { "Publish URL must use rtsp://" }
        val host = uri.host ?: throw IllegalArgumentException("RTSP URL has no host: $publishUrl")
        val port = if (uri.port > 0) uri.port else 554

        RoverRuntimeState.setCameraPublisherState(false, "Connecting RTSP", "")
        RoverRuntimeState.log("CAMERA RTSP connecting url=$publishUrl host=$host port=$port transport=tcp")

        val s = Socket()
        s.tcpNoDelay = true
        s.keepAlive = true
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        s.soTimeout = RESPONSE_TIMEOUT_MS
        socket = s
        input = BufferedInputStream(s.getInputStream())
        output = BufferedOutputStream(s.getOutputStream())

        var cseq = 1
        val sdp = buildSdp(sps, pps)
        request(
            method = "ANNOUNCE",
            url = publishUrl,
            cseq = cseq++,
            headers = mapOf("Content-Type" to "application/sdp"),
            body = sdp,
        ).require2xx("ANNOUNCE")

        val trackUrl = publishUrl.trimEnd('/') + "/trackID=0"
        val setup = request(
            method = "SETUP",
            url = trackUrl,
            cseq = cseq++,
            headers = mapOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"),
        )
        setup.require2xx("SETUP")
        sessionId = setup.headers["session"]?.substringBefore(';')?.trim()
            ?: throw IllegalStateException("RTSP SETUP response did not contain Session")

        request(
            method = "RECORD",
            url = publishUrl,
            cseq = cseq,
            headers = mapOf(
                "Session" to sessionId!!,
                "Range" to "npt=0.000-",
            ),
        ).require2xx("RECORD")

        s.soTimeout = 0
        connected = true
        RoverRuntimeState.setCameraPublisherState(true, "Publishing RTSP/TCP", "")
        RoverRuntimeState.log("CAMERA RTSP RECORD active session=$sessionId")
    }

    private fun request(
        method: String,
        url: String,
        cseq: Int,
        headers: Map<String, String> = emptyMap(),
        body: String = "",
    ): RtspResponse {
        val out = output ?: throw IllegalStateException("RTSP output is closed")
        val bytes = body.toByteArray(Charsets.UTF_8)
        val text = buildString {
            append(method).append(' ').append(url).append(" RTSP/1.0\r\n")
            append("CSeq: ").append(cseq).append("\r\n")
            append("User-Agent: roverd-android\r\n")
            headers.forEach { (key, value) -> append(key).append(": ").append(value).append("\r\n") }
            if (bytes.isNotEmpty()) append("Content-Length: ").append(bytes.size).append("\r\n")
            append("\r\n")
        }
        out.write(text.toByteArray(Charsets.US_ASCII))
        if (bytes.isNotEmpty()) out.write(bytes)
        out.flush()
        return readResponse()
    }

    private fun readResponse(): RtspResponse {
        val source = input ?: throw IllegalStateException("RTSP input is closed")
        val statusLine = readLine(source) ?: throw IllegalStateException("RTSP server closed connection")
        val statusParts = statusLine.split(' ', limit = 3)
        if (statusParts.size < 2) throw IllegalStateException("Bad RTSP status line: $statusLine")
        val status = statusParts[1].toIntOrNull() ?: throw IllegalStateException("Bad RTSP status: $statusLine")
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(source) ?: throw IllegalStateException("RTSP response ended inside headers")
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength > 0) {
            var remaining = contentLength
            val buffer = ByteArray(1024)
            while (remaining > 0) {
                val read = source.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) throw IllegalStateException("RTSP body ended early")
                remaining -= read
            }
        }
        return RtspResponse(status, statusLine, headers)
    }

    private fun readLine(source: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (true) {
            val value = source.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.US_ASCII)
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.add(value.toByte())
            if (bytes.size > 8192) throw IllegalStateException("RTSP line too long")
        }
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun buildSdp(sps: ByteArray, pps: ByteArray): String {
        val profile = if (sps.size >= 4) {
            String.format(Locale.US, "%02x%02x%02x", sps[1].toInt() and 0xff, sps[2].toInt() and 0xff, sps[3].toInt() and 0xff)
        } else {
            "42e01f"
        }
        val sps64 = Base64.encodeToString(sps, Base64.NO_WRAP)
        val pps64 = Base64.encodeToString(pps, Base64.NO_WRAP)
        return buildString {
            append("v=0\r\n")
            append("o=- 0 0 IN IP4 127.0.0.1\r\n")
            append("s=roverd-android\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("t=0 0\r\n")
            append("a=tool:roverd-android\r\n")
            append("m=video 0 RTP/AVP 96\r\n")
            append("a=rtpmap:96 H264/90000\r\n")
            append("a=fmtp:96 packetization-mode=1;profile-level-id=$profile;sprop-parameter-sets=$sps64,$pps64\r\n")
            append("a=control:trackID=0\r\n")
        }
    }

    private fun sendAccessUnit(frame: EncodedFrame) {
        val nals = H264Nals.split(frame.data).filter { it.isNotEmpty() }
        if (nals.isEmpty()) return
        val timestamp = ((frame.presentationTimeUs * 90L) / 1000L).toInt()
        nals.forEachIndexed { index, nal ->
            sendNal(nal, timestamp, index == nals.lastIndex)
        }
        output?.flush()
        RoverRuntimeState.recordCameraPublishedFrame(frame.data.size)
    }

    private fun sendNal(nal: ByteArray, timestamp: Int, lastNal: Boolean) {
        if (nal.size <= MAX_RTP_PAYLOAD) {
            sendRtpPacket(nal, timestamp, lastNal)
            return
        }
        if (nal.size < 2) return

        val nalHeader = nal[0].toInt() and 0xff
        val fuIndicator = (nalHeader and 0xe0) or 28
        val nalType = nalHeader and 0x1f
        val maxChunk = MAX_RTP_PAYLOAD - 2
        var offset = 1
        while (offset < nal.size) {
            val count = minOf(maxChunk, nal.size - offset)
            val start = offset == 1
            val end = offset + count >= nal.size
            val fuHeader = (if (start) 0x80 else 0) or (if (end) 0x40 else 0) or nalType
            val payload = ByteArray(count + 2)
            payload[0] = fuIndicator.toByte()
            payload[1] = fuHeader.toByte()
            System.arraycopy(nal, offset, payload, 2, count)
            sendRtpPacket(payload, timestamp, lastNal && end)
            offset += count
        }
    }

    private fun sendRtpPacket(payload: ByteArray, timestamp: Int, marker: Boolean) {
        val packet = ByteArray(12 + payload.size)
        packet[0] = 0x80.toByte()
        packet[1] = ((if (marker) 0x80 else 0) or RTP_PAYLOAD_TYPE).toByte()
        packet[2] = (sequence ushr 8).toByte()
        packet[3] = sequence.toByte()
        sequence = (sequence + 1) and 0xffff
        writeInt(packet, 4, timestamp)
        writeInt(packet, 8, ssrc)
        System.arraycopy(payload, 0, packet, 12, payload.size)

        val out = output ?: throw IllegalStateException("RTSP output is closed")
        out.write('$'.code)
        out.write(RTP_CHANNEL)
        out.write((packet.size ushr 8) and 0xff)
        out.write(packet.size and 0xff)
        out.write(packet)
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun sleepRetry() {
        try {
            Thread.sleep(2000)
        } catch (_: InterruptedException) {
        }
    }

    private fun closeConnection() {
        connected = false
        sessionId = null
        runCatching { output?.flush() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.interrupt()
        closeConnection()
        queue.clear()
        RoverRuntimeState.setCameraPublisherState(false, "Stopped", "")
    }

    private data class RtspResponse(
        val status: Int,
        val statusLine: String,
        val headers: Map<String, String>,
    ) {
        fun require2xx(method: String) {
            if (status !in 200..299) throw IllegalStateException("RTSP $method failed: $statusLine")
        }
    }
}

object H264Nals {
    fun split(data: ByteArray): List<ByteArray> {
        val annexB = splitAnnexB(data)
        if (annexB.isNotEmpty()) return annexB
        val avcc = splitLengthPrefixed(data)
        if (avcc.isNotEmpty()) return avcc
        return if (data.isEmpty()) emptyList() else listOf(data.copyOf())
    }

    fun codecParameterSets(vararg buffers: ByteArray): Pair<ByteArray?, ByteArray?> {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (buffer in buffers) {
            val nals = split(buffer)
            for (nal in nals) {
                if (nal.isEmpty()) continue
                when (nal[0].toInt() and 0x1f) {
                    7 -> sps = nal.copyOf()
                    8 -> pps = nal.copyOf()
                }
            }
        }
        return sps to pps
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 3 < data.size) {
            val len = when {
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte() -> 3
                i + 4 <= data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (len > 0) {
                starts.add(i to len)
                i += len
            } else {
                i++
            }
        }
        if (starts.isEmpty()) return emptyList()
        val out = ArrayList<ByteArray>(starts.size)
        for (index in starts.indices) {
            val (start, prefix) = starts[index]
            val bodyStart = start + prefix
            val end = if (index + 1 < starts.size) starts[index + 1].first else data.size
            if (end > bodyStart) out.add(data.copyOfRange(bodyStart, end))
        }
        return out
    }

    private fun splitLengthPrefixed(data: ByteArray): List<ByteArray> {
        if (data.size < 5) return emptyList()
        val out = ArrayList<ByteArray>()
        var offset = 0
        while (offset + 4 <= data.size) {
            val size = ((data[offset].toInt() and 0xff) shl 24) or
                ((data[offset + 1].toInt() and 0xff) shl 16) or
                ((data[offset + 2].toInt() and 0xff) shl 8) or
                (data[offset + 3].toInt() and 0xff)
            offset += 4
            if (size <= 0 || offset + size > data.size) return emptyList()
            out.add(data.copyOfRange(offset, offset + size))
            offset += size
        }
        return if (offset == data.size) out else emptyList()
    }
}
