package land.otter.roverd

data class RoombaChargeSample(
    val chargingState: Int,
    val chargeSources: Int,
)

object RoombaChargeSampleParser {
    private val packetSizes = mapOf(
        100 to 80,
        21 to 1,
        34 to 1,
    )

    fun parse(frame: ByteArray): RoombaChargeSample? {
        if (frame.size < 4 || (frame[0].toInt() and 0xff) != 19) return null
        val payloadLength = frame[1].toInt() and 0xff
        val payloadEnd = 2 + payloadLength
        if (payloadEnd >= frame.size) return null

        var chargingState: Int? = null
        var chargeSources: Int? = null
        var offset = 2
        while (offset < payloadEnd) {
            val packetId = frame[offset++].toInt() and 0xff
            val packetSize = packetSizes[packetId] ?: return null
            if (offset + packetSize > payloadEnd) return null
            when (packetId) {
                21 -> chargingState = frame[offset].toInt() and 0xff
                34 -> chargeSources = frame[offset].toInt() and 0xff
            }
            offset += packetSize
        }

        val state = chargingState ?: return null
        val sources = chargeSources ?: return null
        return RoombaChargeSample(state, sources)
    }
}

class AutoChargeController(
    private val seekDock: () -> Unit,
    private val emitEvent: (String, Map<String, Any>) -> Unit,
) {
    companion object {
        private const val AUTO_CHARGE_TIMEOUT_MS = 1_000L
        private const val SOURCE_HOME_BASE = 1 shl 1
    }

    private var timerStartMs = 0L

    @Synchronized
    fun onSensorFrame(frame: ByteArray) {
        val sample = RoombaChargeSampleParser.parse(frame) ?: return
        val now = System.currentTimeMillis()
        val docked = sample.chargeSources and SOURCE_HOME_BASE != 0
        val charging = sample.chargingState in 1..4

        RoverRuntimeState.setChargeState(
            chargingState = sample.chargingState,
            chargeSources = sample.chargeSources,
            homeBaseDetected = docked,
            charging = charging,
        )

        if (!docked || charging) {
            if (timerStartMs != 0L) {
                emitEvent(
                    "autoCharge.timerCleared",
                    mapOf("durationMs" to (now - timerStartMs)),
                )
            }
            timerStartMs = 0L
            RoverRuntimeState.setAutoChargeState("idle", false)
            return
        }

        if (timerStartMs == 0L) {
            timerStartMs = now
            RoverRuntimeState.setAutoChargeState("waiting: docked but not charging", true)
            emitEvent(
                "autoCharge.timerStarted",
                mapOf("chargingState" to sample.chargingState),
            )
            return
        }

        if (now - timerStartMs >= AUTO_CHARGE_TIMEOUT_MS) {
            try {
                seekDock()
                RoverRuntimeState.recordAutoChargeSeek("SeekDock issued after ${AUTO_CHARGE_TIMEOUT_MS}ms")
                emitEvent(
                    "autoCharge.seekDockIssued",
                    mapOf("waitingMs" to AUTO_CHARGE_TIMEOUT_MS),
                )
            } catch (t: Throwable) {
                val error = t.message ?: t.javaClass.simpleName
                RoverRuntimeState.setAutoChargeState("SeekDock error: $error", false)
                emitEvent(
                    "autoCharge.seekDockError",
                    mapOf("error" to error),
                )
                RoverRuntimeState.log("AUTOCHARGE SeekDock failed: ${t.stackTraceToString()}")
            }
            // Pi roverd currently has a zero-duration cooldown, so the condition
            // immediately rearms and can issue another SeekDock after another 1s.
            timerStartMs = 0L
        }
    }
}
