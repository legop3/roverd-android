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

    // Preserve the orientation state that StreamBase derives from prepareVideo(rotation=...).
    // This custom source exists only so roverd can select an exact Camera2 ID; it should not invent
    // a second geometry model on top of RootEncoder.
    override fun getOrientationConfig(): OrientationConfig = OrientationConfig(
        cameraOrientation = if (rotation == 0) 270 else rotation - 90,
        isPortrait = rotation == 90 || rotation == 270,
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

    fun setTorch(on: Boolean) {
        check(isRunning()) { "camera $cameraId is not running" }
        if (on) camera.enableLantern() else camera.disableLantern()
    }

    fun currentCameraId(): String = camera.getCurrentCameraId()
}
