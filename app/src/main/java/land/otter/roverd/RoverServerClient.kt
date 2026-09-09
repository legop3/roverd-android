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
import java.util.concurrent.ScheduledFuture
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
        private const val DISCONNECT_SEEK_SECONDS = 60L
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
    private var disconnectSeekTask: ScheduledFuture<*>? = null

    private var lastAuxMain = 0
    private var lastAuxSide = 0
    private var lastAuxVacuum = 0
    private var autoSideBrushOn = false

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

    fun sendEvent(event: String, data: Map<String, Any>) {
        val payload = JSONObject()
        data.forEach { (key, value) -> payload.put(key, value) }
        val msg = JSONObject()
            .put("type", "event")
            .put("event", event)
            .put("ts", System.currentTimeMillis())
            .put("data", payload)
        RoverRuntimeState.log("EVENT event=$event data=${payload.toString()}")
        if (connected) socket?.send(msg.toString())
    }

    private fun sendHello(ws: WebSocket) {
        val disabledToggle = JSONObject()
            .put("enabled", false)
            .put("gpioPin", -1)
            .put("gpioChip", "")
            .put("initialOn", false)
            .put("activeLow", false)

        val headlight = JSONObject()
            .put("enabled", config.headlightEnabled && HeadlightController.isAvailable())
            .put("gpioPin", -1)
            .put("gpioChip", "android-camera-torch")
            .put("initialOn", config.headlightInitialOn)
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

        val audioCapture = JSONObject().put("enabled", config.micEnabled)
        if (config.micEnabled) {
            runCatching {
                audioCapture
                    .put("service", "android-microphone")
                    .put("publisher", "android-mediacodec-opus")
                    .put("publishUrl", MediaUrl.micPublishUrl(config))
                    .put("device", "audioSource:${config.micAudioSource}")
                    .put("sampleRate", config.micSampleRate)
                    .put("channels", config.micChannels)
                    .put("bitrate", config.micBitrate)
            }.onFailure {
                RoverRuntimeState.log("MIC hello publish URL failure: ${it.stackTraceToString()}")
                audioCapture.put("enabled", false)
            }
        }

        val audioPlayback = JSONObject().put("enabled", config.audioPlaybackEnabled)
        if (config.audioPlaybackEnabled) {
            runCatching {
                audioPlayback
                    .put("service", "android-libvlc")
                    .put("forwardUrl", MediaUrl.audioPlaybackUrl(config))
                    .put("device", "android-system-media")
                    .put("normalize", false)
            }.onFailure {
                RoverRuntimeState.log("AUDIO playback hello URL failure: ${it.stackTraceToString()}")
                audioPlayback.put("enabled", false)
            }
        }

        val safety = config.privateSafety
        val privateConfig = JSONObject()
            .put("enabled", config.privateEnabled)
            .put("safety", JSONObject()
                .put("speedLimitEnabled", safety.speedLimitEnabled)
                .put("speedLimitMaxWheelSpeed", safety.speedLimitMaxWheelSpeed)
                .put("hardOvercurrentEnabled", safety.hardOvercurrentEnabled)
                .put("overcurrentStopMs", safety.overcurrentStopMs)
                .put("hardBumpEnabled", safety.hardBumpEnabled)
                .put("bumpBackoffSpeed", safety.bumpBackoffSpeed)
                .put("bumpBackoffMs", safety.bumpBackoffMs)
                .put("cliffEnabled", safety.cliffEnabled)
                .put("cliffBackoffSpeed", safety.cliffBackoffSpeed)
                .put("cliffBackoffMs", safety.cliffBackoffMs)
                .put("virtualWallEnabled", safety.virtualWallEnabled)
                .put("virtualWallBackoffSpeed", safety.virtualWallBackoffSpeed)
                .put("virtualWallBackoffMs", safety.virtualWallBackoffMs)
                .put("triggerCooldownMs", safety.triggerCooldownMs))

        val hello = JSONObject()
            .put("type", "hello")
            .put("name", config.name)
            .put("description", config.description)
            .put("color", config.color)
            .put("battery", JSONObject()
                .put("full", config.batteryFull)
                .put("warn", config.batteryWarn)
                .put("urgent", config.batteryUrgent))
            .put("maxWheelSpeed", config.maxWheelSpeed)
            .put("media", JSONObject()
                .put("manage", false)
                .put("video", video)
                .put("audioCapture", audioCapture)
                .put("audioPlayback", audioPlayback))
            .put("cameraServo", JSONObject().put("enabled", false))
            .put("audio", JSONObject()
                .put("ttsEnabled", config.ttsEnabled)
                .put("defaultEngine", "android")
                .put("defaultVoice", config.ttsVoice)
                .put("defaultPitch", (config.ttsPitch * 50f).toInt().coerceIn(1, 99)))
            .put("horn", JSONObject()
                .put("enabled", config.hornEnabled)
                .put("volume", config.hornVolume)
                .put("sampleRate", 48_000)
                .put("channels", 1))
            .put("headlight", headlight)
            .put("laser", JSONObject(disabledToggle.toString()))
            .put("private", privateConfig)
            .put("platform", JSONObject()
                .put("type", "android")
                .put("appVersion", "0.1.0")
                .put("usbSerialPreference", config.usbSerialPreference)
                .put("usbSerialConnected", roombaProvider()?.isConnected() == true))

        RoverRuntimeState.log("WS hello=${hello.toString().take(4000)}")
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
                val left = p.optInt("left")
                val right = p.optInt("right")
                val roomba = connectedRoomba()
                roomba.driveDirect(left, right)
                applyAutoSideBrush(roomba, left, right)
            }
            msg.has("motorPwm") -> {
                val p = msg.getJSONObject("motorPwm")
                val main = p.optInt("main").coerceIn(-127, 127)
                val side = p.optInt("side").coerceIn(-127, 127)
                val vacuum = p.optInt("vacuum").coerceIn(0, 127)
                lastAuxMain = main
                lastAuxSide = side
                lastAuxVacuum = vacuum
                autoSideBrushOn = false
                connectedRoomba().motorPwm(main, side, vacuum)
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
            msg.has("tts") -> RoverAudioController.handleTts(msg.getJSONObject("tts"))
            msg.has("horn") -> RoverAudioController.handleHorn(msg.getJSONObject("horn"))
            msg.has("audioLevels") -> RoverAudioController.handleAudioLevels(msg.getJSONObject("audioLevels"))
            msg.has("headlight") -> {
                if (!config.headlightEnabled) throw IllegalStateException("headlight disabled")
                val action = msg.getJSONObject("headlight").optString("action", "toggle")
                HeadlightController.handleAction(action, config.cameraId)
            }
            else -> throw UnsupportedOperationException("Unsupported command type: ${msg.optString("type", "unknown")}")
        }
    }

    private fun applyAutoSideBrush(roomba: UsbRoomba, left: Int, right: Int) {
        if (!config.autoSideBrushEnabled) {
            if (autoSideBrushOn) {
                autoSideBrushOn = false
                roomba.motorPwm(lastAuxMain, lastAuxSide, lastAuxVacuum)
                RoverRuntimeState.log("AUTO SIDE BRUSH stopped: feature disabled")
            }
            return
        }

        val moving = left != 0 || right != 0
        if (!moving) {
            if (autoSideBrushOn) {
                autoSideBrushOn = false
                roomba.motorPwm(lastAuxMain, lastAuxSide, lastAuxVacuum)
                RoverRuntimeState.log("AUTO SIDE BRUSH stopped: drive stopped")
            }
            return
        }

        if (lastAuxSide != 0) {
            autoSideBrushOn = false
            return
        }

        val speed = config.autoSideBrushSpeed.coerceIn(-127, 127)
        if (speed == 0 || autoSideBrushOn) return

        roomba.motorPwm(lastAuxMain, speed, lastAuxVacuum)
        autoSideBrushOn = true
        RoverRuntimeState.log("AUTO SIDE BRUSH started speed=$speed")
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

    @Synchronized
    private fun scheduleDisconnectSeek() {
        if (closed.get() || connected || disconnectSeekTask != null) return
        RoverRuntimeState.log("WS disconnect failsafe armed: SeekDock in ${DISCONNECT_SEEK_SECONDS}s")
        disconnectSeekTask = scheduler.schedule(
            {
                synchronized(this) { disconnectSeekTask = null }
                if (closed.get() || connected) return@schedule
                val roomba = roombaProvider()
                if (roomba == null || !roomba.isConnected()) {
                    RoverRuntimeState.log("WS disconnect failsafe could not SeekDock: USB serial not connected")
                    return@schedule
                }
                runCatching { roomba.seekDock() }
                    .onSuccess {
                        RoverRuntimeState.log("WS disconnect failsafe SeekDock issued after ${DISCONNECT_SEEK_SECONDS}s")
                    }
                    .onFailure {
                        RoverRuntimeState.log("WS disconnect failsafe SeekDock failed: ${it.stackTraceToString()}")
                    }
            },
            DISCONNECT_SEEK_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    @Synchronized
    private fun cancelDisconnectSeek() {
        disconnectSeekTask?.cancel(false)
        disconnectSeekTask = null
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            connected = true
            reconnectSeconds = 1
            cancelDisconnectSeek()
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
            scheduleDisconnectSeek()
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket === webSocket) socket = null
            connected = false
            RoverRuntimeState.setServerState(false)
            RoverRuntimeState.log("WS failure response=${response?.code()} exception=${t.stackTraceToString()}")
            onStatus("Server connection failed: ${t.message}")
            scheduleDisconnectSeek()
            scheduleReconnect()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connected = false
        cancelDisconnectSeek()
        RoverRuntimeState.setServerState(false)
        socket?.close(1000, "service stopping")
        socket = null
        scheduler.shutdownNow()
        http.dispatcher().executorService().shutdown()
        http.connectionPool().evictAll()
    }
}
