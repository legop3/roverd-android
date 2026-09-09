package land.otter.roverd

import com.pedro.encoder.input.audio.CustomAudioEffect
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Applies simple PCM gain before Opus encoding.
 * RootEncoder microphone PCM is signed 16-bit little-endian.
 * Saturation is intentional so overload clips instead of integer-wrapping.
 */
class MicGainEffect(
    gainDb: Int,
    private val onAmplitude: (Float) -> Unit,
) : CustomAudioEffect() {
    private val gain = 10.0.pow(gainDb / 20.0)

    override fun process(pcmBuffer: ByteArray): ByteArray {
        var peak = 0
        var i = 0
        while (i + 1 < pcmBuffer.size) {
            val raw = ((pcmBuffer[i].toInt() and 0xff) or (pcmBuffer[i + 1].toInt() shl 8)).toShort().toInt()
            val amplified = (raw * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            peak = maxOf(peak, abs(amplified).coerceAtMost(32767))
            pcmBuffer[i] = (amplified and 0xff).toByte()
            pcmBuffer[i + 1] = ((amplified shr 8) and 0xff).toByte()
            i += 2
        }
        onAmplitude(peak / 32767f)
        return pcmBuffer
    }
}
