package land.otter.roverd

object RoombaOi {
    const val START = 128
    const val SAFE = 131
    const val DRIVE_DIRECT = 145
    const val MOTOR_PWM = 144
    const val STREAM = 148
    const val PAUSE_RESUME_STREAM = 150
    const val SEEK_DOCK = 143

    fun driveDirect(left: Int, right: Int): ByteArray = byteArrayOf(
        DRIVE_DIRECT.toByte(),
        (right shr 8).toByte(), right.toByte(),
        (left shr 8).toByte(), left.toByte(),
    )

    fun motorPwm(main: Int, side: Int, vacuum: Int): ByteArray = byteArrayOf(
        MOTOR_PWM.toByte(), main.toByte(), side.toByte(), vacuum.toByte(),
    )

    fun startSensorStream(packetIds: ByteArray): ByteArray =
        byteArrayOf(STREAM.toByte(), packetIds.size.toByte()) + packetIds
}
