package land.otter.roverd

import java.net.URI
import java.net.URLEncoder

object MediaUrl {
    fun videoPublishUrl(config: RoverConfig): String {
        if (config.cameraPublishUrl.isNotBlank()) return config.cameraPublishUrl.trim()
        return deriveRtspUrl(config.serverUrl, config.name, config.cameraRtspPort)
    }

    fun deriveRtspUrl(serverUrl: String, streamName: String, port: Int = 8554): String {
        val uri = runCatching { URI(serverUrl) }
            .getOrElse { throw IllegalArgumentException("Invalid rover server URL: $serverUrl", it) }
        val host = uri.host ?: throw IllegalArgumentException("Rover server URL has no host: $serverUrl")
        val renderedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        val path = URLEncoder.encode(streamName, "UTF-8").replace("+", "%20")
        return "rtsp://$renderedHost:$port/$path"
    }
}
