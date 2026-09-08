package land.otter.roverd

data class RoombaSongNote(
    val note: Int,
    val duration: Int,
)

object RoombaOi {
    const val START = 128
    const val SAFE = 131
    const val FULL = 132
    const val SONG = 140
    const val PLAY_SONG = 141
    const val SEEK_DOCK = 143
    const val MOTOR_PWM = 144
    const val DRIVE_DIRECT = 145
    const val STREAM = 148
    const val PAUSE_RESUME_STREAM = 150

    val DEFAULT_STREAM_PACKETS = byteArrayOf(100, 21, 34)

    fun driveDirect(left: Int, right: Int): ByteArray = byteArrayOf(
        DRIVE_DIRECT.toByte(),
        (right shr 8).toByte(), right.toByte(),
        (left shr 8).toByte(), left.toByte(),
    )

    fun motorPwm(main: Int, side: Int, vacuum: Int): ByteArray = byteArrayOf(
        MOTOR_PWM.toByte(), main.toByte(), side.toByte(), vacuum.toByte(),
    )

    fun startSensorStream(packetIds: ByteArray = DEFAULT_STREAM_PACKETS): ByteArray =
        byteArrayOf(STREAM.toByte(), packetIds.size.toByte()) + packetIds

    fun pauseResumeSensorStream(enable: Boolean): ByteArray =
        byteArrayOf(PAUSE_RESUME_STREAM.toByte(), if (enable) 1 else 0)

    fun defineSong(slot: Int, notes: List<RoombaSongNote>): ByteArray {
        require(slot in 0..4) { "song slot must be 0-4" }
        require(notes.isNotEmpty()) { "song requires at least one note" }
        require(notes.size <= 16) { "song supports up to 16 notes, got ${notes.size}" }

        val out = ByteArray(3 + notes.size * 2)
        out[0] = SONG.toByte()
        out[1] = slot.toByte()
        out[2] = notes.size.toByte()
        var offset = 3
        for (entry in notes) {
            out[offset++] = entry.note.coerceIn(31, 127).toByte()
            out[offset++] = entry.duration.coerceIn(1, 255).toByte()
        }
        return out
    }

    fun playSong(slot: Int): ByteArray {
        require(slot in 0..4) { "song slot must be 0-4" }
        return byteArrayOf(PLAY_SONG.toByte(), slot.toByte())
    }

    fun isModeOpcode(opcode: Int): Boolean = opcode == START || opcode == SAFE || opcode == FULL
}
