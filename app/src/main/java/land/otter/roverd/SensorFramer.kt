package land.otter.roverd

/** Reassembles Roomba OI stream frames from arbitrary USB read chunks. */
class SensorFramer(private val onFrame: (ByteArray) -> Unit) {
    private var buffer = ByteArray(512)
    private var size = 0

    @Synchronized
    fun feed(data: ByteArray) {
        ensureCapacity(size + data.size)
        data.copyInto(buffer, size)
        size += data.size
        drain()
    }

    private fun drain() {
        while (size >= 2) {
            var header = -1
            for (i in 0 until size) {
                if ((buffer[i].toInt() and 0xff) == 19) {
                    header = i
                    break
                }
            }
            if (header < 0) {
                size = 0
                return
            }
            if (header > 0) discard(header)
            if (size < 2) return

            val payloadLength = buffer[1].toInt() and 0xff
            val frameLength = payloadLength + 3
            if (frameLength > 258) {
                discard(1)
                continue
            }
            if (size < frameLength) return

            val frame = buffer.copyOfRange(0, frameLength)
            if (checksumValid(frame)) {
                onFrame(frame)
                discard(frameLength)
            } else {
                // A false 0x13 may have appeared in corrupted/noisy input.
                discard(1)
            }
        }
    }

    private fun checksumValid(frame: ByteArray): Boolean {
        var sum = 0
        for (b in frame) sum = (sum + (b.toInt() and 0xff)) and 0xff
        return sum == 0
    }

    private fun discard(count: Int) {
        if (count >= size) {
            size = 0
            return
        }
        buffer.copyInto(buffer, 0, count, size)
        size -= count
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var next = buffer.size
        while (next < required) next *= 2
        buffer = buffer.copyOf(next)
    }
}
