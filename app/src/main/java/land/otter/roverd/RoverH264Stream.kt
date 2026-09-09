package land.otter.roverd

import android.content.Context
import android.media.MediaCodec
import com.pedro.common.AudioCodec
import com.pedro.common.VideoCodec
import com.pedro.common.socket.base.SocketType
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.VideoSource
import com.pedro.library.base.StreamBase
import com.pedro.library.util.streamclient.StreamBaseClient
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RootEncoder owns Camera2/OpenGL/MediaCodec. RtspH264Publisher owns MediaMTX transport.
 * Keeping these responsibilities separate makes camera/encoder failures and network failures
 * independently observable and avoids the RtspCamera2 lifecycle behavior that caused short streams.
 */
class RoverH264Stream(
    context: Context,
    videoSource: VideoSource,
) : StreamBase(context.applicationContext, videoSource, NoAudioSource()) {
    private var publisher: RtspH264Publisher? = null
    private val client = LocalStreamClient()
    private val closed = AtomicBoolean(false)

    override fun getStreamClient(): StreamBaseClient = client

    override fun setVideoCodecImp(codec: VideoCodec) {
        require(codec == VideoCodec.H264) { "Rover MediaMTX publisher currently requires H.264" }
    }

    override fun setAudioCodecImp(codec: AudioCodec) = Unit

    override fun onAudioInfoImp(sampleRate: Int, isStereo: Boolean) = Unit

    override fun startStreamImp(endPoint: String) {
        check(!closed.get()) { "RoverH264Stream is closed" }
        publisher?.close()
        publisher = RtspH264Publisher(endPoint)
        RoverRuntimeState.setCameraPublisherState(false, "Waiting for H.264 codec config", "")
        RoverRuntimeState.log("CAMERA transport created url=$endPoint")
    }

    override fun stopStreamImp() {
        publisher?.close()
        publisher = null
    }

    override fun onVideoInfoImp(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
        val spsBytes = byteBufferBytes(sps)
        val ppsBytes = pps?.let(::byteBufferBytes) ?: ByteArray(0)
        val (parsedSps, parsedPps) = H264Nals.codecParameterSets(spsBytes, ppsBytes)
        val finalSps = parsedSps ?: stripSingleNalPrefix(spsBytes)
        val finalPps = parsedPps ?: stripSingleNalPrefix(ppsBytes)
        if (finalSps.isNotEmpty() && finalPps.isNotEmpty()) {
            publisher?.setCodecConfig(finalSps, finalPps)
        } else {
            RoverRuntimeState.log("CAMERA missing usable SPS/PPS sps=${spsBytes.size} pps=${ppsBytes.size}")
        }
    }

    override fun getVideoDataImp(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.size <= 0) return
        val duplicate = videoBuffer.duplicate()
        duplicate.position(info.offset.coerceAtLeast(0))
        duplicate.limit((info.offset + info.size).coerceAtMost(duplicate.capacity()))
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        val keyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        RoverRuntimeState.recordCameraEncodedFrame(bytes.size, keyFrame)
        publisher?.enqueue(bytes, info.presentationTimeUs, keyFrame)
    }

    override fun getAudioDataImp(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) = Unit

    fun publisherConnected(): Boolean = publisher?.isConnected() == true

    fun closeTransport() {
        if (!closed.compareAndSet(false, true)) return
        publisher?.close()
        publisher = null
    }

    private fun byteBufferBytes(buffer: ByteBuffer): ByteArray {
        val copy = buffer.duplicate()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }

    private fun stripSingleNalPrefix(data: ByteArray): ByteArray {
        if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
            return data.copyOfRange(4, data.size)
        }
        if (data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
            return data.copyOfRange(3, data.size)
        }
        return data
    }

    private inner class LocalStreamClient : StreamBaseClient() {
        override fun setAuthorization(user: String?, password: String?) = Unit
        override fun reTry(delay: Long, reason: String, backupUrl: String?): Boolean = false
        override fun setReTries(reTries: Int) = Unit
        override fun hasCongestion(percentUsed: Float): Boolean = false
        override fun setLogs(enabled: Boolean) = Unit
        override fun setCheckServerAlive(enabled: Boolean) = Unit
        override fun resizeCache(newSize: Int) = Unit
        override fun clearCache() = Unit
        override fun getCacheSize(): Int = 0
        override fun getItemsInCache(): Int = 0
        override fun getQueueBytesOut(): Long = 0
        override fun getSentAudioFrames(): Long = 0
        override fun getSentVideoFrames(): Long = RoverRuntimeState.cameraPublishedFrames
        override fun getBytesSend(): Long = RoverRuntimeState.cameraPublishedBytes
        override fun getDroppedAudioFrames(): Long = 0
        override fun getDroppedVideoFrames(): Long = RoverRuntimeState.cameraDroppedFrames
        override fun resetSentAudioFrames() = Unit
        override fun resetSentVideoFrames() = Unit
        override fun resetDroppedAudioFrames() = Unit
        override fun resetDroppedVideoFrames() = Unit
        override fun resetBytesSend() = Unit
        override fun setOnlyAudio(onlyAudio: Boolean) = Unit
        override fun setOnlyVideo(onlyVideo: Boolean) = Unit
        override fun setBitrateExponentialFactor(factor: Float) = Unit
        override fun getBitrateExponentialFactor(): Float = 1f
        override fun setSocketType(type: SocketType) = Unit
        override fun setSocketTimeout(timeout: Long) = Unit
        override fun setDelay(millis: Long) = Unit
    }
}
