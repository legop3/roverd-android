package land.otter.roverd

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class RoverServerClient(
    private val config: RoverConfig,
    private val roombaProvider: () -> UsbRoomba?,
    private val onStatus: (String) -> Unit,
) : Closeable {

    companion object {
        val DEFAULT_STREAM_PACKETS = byteArrayOf(100, 21, 34)
    }

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val http = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val closed = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var connected = false
    private var reconnectSeconds = 1L
    private var lastSensorSentNs = 0L

    fun start() {
        connect()
    }

    private fun connect() {
        if (closed.get() || connected || socket != null) return
        onStatus("Connecting to ${config.serverUrl}")
        RoverRuntimeState.log("WS connect url=${config.serverUrl}")
        val request = Request.Builder().url(config.serverUrl).build()
        socket = http.newWebSocket(request, Listener())
    }

    fun sendSensorFrame(frame: ByteArray) {
        if (!connected) return
        val now = System.nanoTime()
        if (now - lastSensorSentNs < 50_000_000L) return
        lastSensorSentNs = now
        val msg = JSONObject()
            .put("type", "sensor")
            .put("ts", System.currentTimeMillis())
            .put("data", Base64.encodeToString(frame, Base64.NO_WRAP))
        socket?.send(msg.toString())
    }

    private fun sendHello(ws: WebSocket) {
        val disabledToggle = JSONObject()
            .put("enabled", false)
            .put("gpioPin", -1)
            .put("gpioChip", "")
            .put("initialOn", false)
            .put("activeLow", false)

        val video = JSONObject().put("enabled", config.cameraEnabled)
        if (config.cameraEnabled) {
            runCatching {
                video
                    .put("service", "android-camera")
                    .put("publisher", "android-mediacodec")
                    .put("publishUrl", MediaUrl.videoPublishUrl(config))
                    .put("device", config.cameraId)
            }.onFailure {
                RoverRuntimeState.log("CAMERA hello publish URL failure: ${it.stackTraceToString()}")
                video.put("enabled", false)
            }
        }

        val hello = JSONObject()
            .put("type", "hello")
            .put("name", config.name)
            .put("description", "Android phone rover")
            .put("color", "#4DB6AC")
            .put("battery", JSONObject()
                .put("full", 2068)
                .put("warn", 1700)
                .put("urgent", 1650))
            .put("maxWheelSpeed", config.maxWheelSpeed)
            .put("media", JSONObject()
                .put("manage", false)
                .put("video", video)
                .put("audioCapture", JSONObject().put("enabled", false))
                .put("audioPlayback", JSONObject().put("enabled", false)))
            .put("cameraServo", JSONObject().put("enabled", false))
            .put("audio", JSONObject().put("ttsEnabled", false))
            .put("horn", JSONObject().put("enabled", false))
            .put("headlight", disabledToggle)
            .put("laser", JSONObject(disabledToggle.toString()))
            .put("private", JSONObject().put("enabled", false))
            .put("platform", JSONObject()
                .put("type", "android")
                .put("appVersion", "0.1.0")
                .put("usbSerialConnected", roombaProvider()?.isConnected() == true))

        RoverRuntimeState.log("WS hello=${hello.toString().take(2000)}")
        ws.send(hello.toString())
    }

    private fun handleCommand(ws: WebSocket, text: String) {
        RoverRuntimeState.recordCommand(text)
        val msg = runCatching { JSONObject(text) }.getOrElse {
            onStatus("Invalid server JSON: ${it.message}")
            RoverRuntimeState.log("WS invalid JSON=${text.take(1500)}")
            return
        }
        val id = msg.optString("id")
        if (id.isEmpty()) return

        try {
            dispatch(msg)
            sendAck(ws, id, null)
        } catch (t: Throwable) {
            RoverRuntimeState.log("CMD failure id=$id error=${t.message} raw=${text.take(1500)}")
            sendAck(ws, id, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun connectedRoomba(): UsbRoomba {
        val roomba = roombaProvider()
        if (roomba == null || !roomba.isConnected()) {
            throw IllegalStateException("USB serial not connected")
        }
        return roomba
    }

    private fun dispatch(msg: JSONObject) {
        when {
            msg.has("driveDirect") -> {
                val p = msg.getJSONObject("driveDirect")
                connectedRoomba().driveDirect(p.optInt("left"), p.optInt("right"))
            }
            msg.has("motorPwm") -> {
                val p = msg.getJSONObject("motorPwm")
                connectedRoomba().motorPwm(p.optInt("main"), p.optInt("side"), p.optInt("vacuum"))
            }
            msg.has("sensorStream") -> {
                val enable = msg.getJSONObject("sensorStream").optBoolean("enable")
                if (enable) connectedRoomba().startSensorStream(DEFAULT_STREAM_PACKETS)
                else connectedRoomba().setSensorStreamEnabled(false)
            }
            msg.has("raw") && msg.optString("raw").isNotEmpty() -> {
                val raw = Base64.decode(msg.getString("raw"), Base64.DEFAULT)
                val roomba = connectedRoomba()
                roomba.write(raw)
                if (raw.isNotEmpty() && isModeOpcode(raw[0].toInt() and 0xff)) {
                    roomba.startSensorStream(DEFAULT_STREAM_PACKETS)
                }
            }
            msg.has("song") -> {
                val p = msg.getJSONObject("song")
                val slot = p.optInt("slot", 0).coerceIn(0, 4)
                val jsonNotes = p.getJSONArray("notes")
                val notes = ArrayList<RoombaSongNote>(jsonNotes.length())
                for (i in 0 until jsonNotes.length()) {
                    val n = jsonNotes.getJSONObject(i)
                    notes.add(
                        RoombaSongNote(
                            note = n.optInt("note"),
                            duration = n.optInt("duration"),
                        ),
                    )
                }
                RoverRuntimeState.log("CMD song slot=$slot notes=${notes.size} loop=${p.optBoolean("loop", false)}")
                connectedRoomba().playSong(slot, notes)
            }
            else -> throw UnsupportedOperationException("Unsupported command type: ${msg.optString("type", "unknown")}")
        }
    }

    private fun isModeOpcode(opcode: Int): Boolean = opcode == 128 || opcode == 131 || opcode == 132

    private fun sendAck(ws: WebSocket, id: String, error: String?) {
        val ack = JSONObject()
            .put("type", "ack")
            .put("id", id)
            .put("status", if (error == null) "ok" else "error")
        if (error != null) ack.put("error", error)
        ws.send(ack.toString())
    }

    private fun scheduleReconnect() {
        if (closed.get()) return
        val delay = reconnectSeconds
        reconnectSeconds = min(30L, reconnectSeconds * 2L)
        RoverRuntimeState.log("WS reconnect scheduled in ${delay}s")
        scheduler.schedule({ connect() }, delay, TimeUnit.SECONDS)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            connected = true
            reconnectSeconds = 1
            RoverRuntimeState.setServerState(true)
            onStatus("Server connected")
            sendHello(webSocket)
            roombaProvider()?.takeIf { it.isConnected() }?.let { roomba ->
                runCatching { roomba.startSensorStream(DEFAULT_STREAM_PACKETS) }
                    .onFailure { onStatus("Sensor stream start failed: ${it.message}") }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleCommand(webSocket, text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            RoverRuntimeState.log("WS closing code=$code reason=$reason")
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket === webSocket) socket = null
            connected = false
            RoverRuntimeState.setServerState(false)
            onStatus("Server disconnected: $code $reason")
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket === webSocket) socket = null
            connected = false
            RoverRuntimeState.setServerState(false)
            RoverRuntimeState.log("WS failure response=${response?.code()} exception=${t.stackTraceToString()}")
            onStatus("Server connection failed: ${t.message}")
            scheduleReconnect()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connected = false
        RoverRuntimeState.setServerState(false)
        socket?.close(1000, "service stopping")
        socket = null
        scheduler.shutdownNow()
        http.dispatcher().executorService().shutdown()
        http.connectionPool().evictAll()
    }
}
