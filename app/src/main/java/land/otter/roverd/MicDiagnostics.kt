package land.otter.roverd

import android.annotation.TargetApi
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build


data class MicSourceOption(val value: Int, val label: String)

object MicDiagnostics {
    fun sourceOptions(): List<MicSourceOption> = buildList {
        add(MicSourceOption(MediaRecorder.AudioSource.DEFAULT, "DEFAULT"))
        add(MicSourceOption(MediaRecorder.AudioSource.MIC, "MIC"))
        add(MicSourceOption(MediaRecorder.AudioSource.CAMCORDER, "CAMCORDER"))
        add(MicSourceOption(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION"))
        add(MicSourceOption(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION"))
        if (Build.VERSION.SDK_INT >= 24) add(MicSourceOption(MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED"))
        if (Build.VERSION.SDK_INT >= 29) add(MicSourceOption(MediaRecorder.AudioSource.VOICE_PERFORMANCE, "VOICE_PERFORMANCE"))
    }

    fun opusEncoders(): List<String> {
        val codecs = if (Build.VERSION.SDK_INT >= 21) {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList()
        } else {
            @Suppress("DEPRECATION")
            (0 until MediaCodecList.getCodecCount()).map { MediaCodecList.getCodecInfoAt(it) }
        }
        return codecs
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals("audio/opus", true) } }
            .map { it.name }
            .distinct()
    }

    fun supportedOpusSampleRates(): List<Int> {
        if (Build.VERSION.SDK_INT < 21) return listOf(48_000, 24_000, 16_000, 12_000, 8_000)
        val rates = linkedSetOf<Int>()
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals("audio/opus", true) } }
            .forEach { codec ->
                runCatching {
                    val caps = codec.getCapabilitiesForType("audio/opus")
                    caps.audioCapabilities?.supportedSampleRates?.forEach { rates += it }
                }
            }
        return rates.sortedDescending().ifEmpty { listOf(48_000, 24_000, 16_000, 12_000, 8_000) }
    }

    fun captureConfigSupported(sampleRate: Int, channels: Int): Boolean {
        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        return AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT) > 0
    }

    fun snapshot(context: Context): String = buildString {
        appendLine("audio API      : AudioRecord / RootEncoder")
        appendLine("Opus encoders  : ${opusEncoders().ifEmpty { listOf("NONE") }.joinToString()}")
        appendLine("Opus rates     : ${supportedOpusSampleRates().joinToString()}")
        appendLine()
        appendLine("Audio sources:")
        sourceOptions().forEach { appendLine("  ${it.value}: ${it.label}") }
        appendLine()
        appendLine("Input devices:")
        append(inputDevices(context))
    }

    private fun inputDevices(context: Context): String {
        if (Build.VERSION.SDK_INT < 23) return "  n/a (< API 23)"
        return inputDevices23(context)
    }

    @TargetApi(23)
    private fun inputDevices23(context: Context): String {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        if (devices.isEmpty()) return "  NONE"
        return buildString {
            devices.forEach { d ->
                appendLine(
                    "  id=${d.id} type=${audioDeviceTypeName(d.type)} product=${d.productName} " +
                        "address=${d.address.ifBlank { "-" }} channels=${d.channelCounts.joinToString().ifBlank { "?" }} " +
                        "rates=${d.sampleRates.joinToString().ifBlank { "?" }} encodings=${d.encodings.joinToString().ifBlank { "?" }}",
                )
            }
        }.trimEnd()
    }

    private fun audioDeviceTypeName(type: Int): String = when (type) {
        1 -> "BUILTIN_EARPIECE"
        2 -> "BUILTIN_SPEAKER"
        3 -> "WIRED_HEADSET"
        4 -> "WIRED_HEADPHONES"
        7 -> "BLUETOOTH_SCO"
        8 -> "BLUETOOTH_A2DP"
        11 -> "USB_DEVICE"
        12 -> "USB_ACCESSORY"
        15 -> "BUILTIN_MIC"
        16 -> "FM_TUNER"
        18 -> "TELEPHONY"
        22 -> "USB_HEADSET"
        23 -> "HEARING_AID"
        24 -> "BUILTIN_SPEAKER_SAFE"
        25 -> "REMOTE_SUBMIX"
        26 -> "BLE_HEADSET"
        27 -> "BLE_SPEAKER"
        28 -> "ECHO_REFERENCE"
        29 -> "HDMI_EARC"
        30 -> "BLE_BROADCAST"
        else -> type.toString()
    }
}
