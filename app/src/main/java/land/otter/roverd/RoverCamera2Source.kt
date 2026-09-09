package land.otter.roverd

import android.annotation.TargetApi
import android.graphics.SurfaceTexture
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Size
import com.pedro.encoder.input.sources.OrientationConfig
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.sources.video.VideoSource
import com.pedro.encoder.input.video.Camera2ApiManager
import com.pedro.encoder.input.video.CameraCallbacks

@TargetApi(21)
class RoverCamera2Source(
    context: android.content.Context,
    private val cameraId: String,
) : VideoSource() {
    private val camera = Camera2ApiManager(context.applicationContext)

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean {
        check(Build.VERSION.SDK_INT >= 21)
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "Camera size must be positive/even: ${width}x$height"
        }
        val requested = Size(width, height)
        val supported = camera.getCameraResolutions(cameraId).toList()
        require(supported.contains(requested)) {
            "Camera $cameraId does not expose ${width}x$height to SurfaceTexture"
        }
        camera.setRequiredResolution(requested)
        return true
    }

    override fun start(surfaceTexture: SurfaceTexture) {
        this.surfaceTexture = surfaceTexture
        if (isRunning()) return
        val requested = Size(width, height)
        camera.setRequiredResolution(requested)
        surfaceTexture.setDefaultBufferSize(width, height)
        camera.prepareCamera(surfaceTexture, width, height, fps, cameraId)
        camera.openCameraId(cameraId)
    }

    override fun stop() {
        camera.closeCamera()
    }

    override fun release() {
        runCatching { camera.closeCamera() }
    }

    override fun isRunning(): Boolean = camera.isRunning

    // StreamBase's default orientation behavior is designed for normal phone UI preview/rotation.
    // A rover camera is a fixed appliance. Keep the source neutral and let the streamer rotate only
    // the final output pixels inside a fixed landscape H.264 canvas.
    override fun getOrientationConfig(): OrientationConfig = OrientationConfig(
        cameraOrientation = 0,
        isPortrait = false,
        forced = OrientationForced.NONE,
    )

    fun setCallbacks(callbacks: CameraCallbacks?) {
        camera.setCameraCallbacks(callbacks)
    }

    fun applyAutomaticControls(exposureCompensation: Int) {
        if (!isRunning()) return
        camera.enableAutoExposure()
        camera.enableAutoWhiteBalance(CaptureRequest.CONTROL_AWB_MODE_AUTO)
        camera.enableAutoFocus()
        camera.exposure = exposureCompensation
        camera.dynamicFps = true
    }

    fun currentCameraId(): String = camera.getCurrentCameraId()
}
