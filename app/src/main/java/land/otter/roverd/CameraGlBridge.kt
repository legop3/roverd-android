package land.otter.roverd

import android.annotation.TargetApi
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@TargetApi(21)
class CameraGlBridge(
    inputWidth: Int,
    inputHeight: Int,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val rotationDegrees: Int,
    private val outputSurface: Surface,
) : Closeable, SurfaceTexture.OnFrameAvailableListener {

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val INIT_TIMEOUT_MS = 5000L

        private val VERTICES = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f,
        )
    }

    private val closed = AtomicBoolean(false)
    private val framePending = AtomicBoolean(false)
    private val thread = HandlerThread("roverd-camera-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val initLatch = CountDownLatch(1)
    private var initError: Throwable? = null

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var oesTexture = 0
    private var textureMatrixHandle = -1
    private var positionHandle = -1
    private var textureCoordHandle = -1
    private var vertexBuffer: FloatBuffer? = null
    private var textureBuffer: FloatBuffer? = null
    private var surfaceTexture: SurfaceTexture? = null

    lateinit var cameraSurface: Surface
        private set

    init {
        handler.post {
            try {
                initializeGl(inputWidth, inputHeight)
            } catch (t: Throwable) {
                initError = t
            } finally {
                initLatch.countDown()
            }
        }
        if (!initLatch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            close()
            throw IllegalStateException("Timed out initializing camera GL bridge")
        }
        initError?.let {
            close()
            throw IllegalStateException("Camera GL bridge initialization failed", it)
        }
    }

    private fun initializeGl(inputWidth: Int, inputHeight: Int) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) { "eglInitialize failed" }

        val configAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, configAttrs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No recordable EGL config"
        }
        val config = configs[0] ?: error("EGL config missing")

        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            outputSurface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }

        program = createProgram()
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        textureCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        textureMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
        check(positionHandle >= 0 && textureCoordHandle >= 0 && textureMatrixHandle >= 0) { "GL shader handles missing" }

        vertexBuffer = floatBuffer(VERTICES)
        textureBuffer = floatBuffer(textureCoordinates(rotationDegrees))

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTexture = textures[0]
        check(oesTexture != 0) { "glGenTextures failed" }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(oesTexture).apply {
            setDefaultBufferSize(inputWidth, inputHeight)
            setOnFrameAvailableListener(this@CameraGlBridge, handler)
        }
        cameraSurface = Surface(surfaceTexture)
        RoverRuntimeState.log(
            "CAMERA GL bridge ready input=${inputWidth}x$inputHeight output=${outputWidth}x$outputHeight rotation=$rotationDegrees",
        )
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        if (closed.get()) return
        if (!framePending.compareAndSet(false, true)) return
        handler.post {
            try {
                drawLatestFrame()
            } catch (t: Throwable) {
                if (!closed.get()) RoverRuntimeState.log("CAMERA GL render failure: ${t.stackTraceToString()}")
            } finally {
                framePending.set(false)
            }
        }
    }

    private fun drawLatestFrame() {
        val st = surfaceTexture ?: return
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw IllegalStateException("eglMakeCurrent failed during draw")
        }
        st.updateTexImage()
        val matrix = FloatArray(16)
        st.getTransformMatrix(matrix)

        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)

        val vb = vertexBuffer ?: return
        vb.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vb)

        val tb = textureBuffer ?: return
        tb.position(0)
        GLES20.glEnableVertexAttribArray(textureCoordHandle)
        GLES20.glVertexAttribPointer(textureCoordHandle, 2, GLES20.GL_FLOAT, false, 0, tb)
        GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, matrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(textureCoordHandle)

        val timestamp = st.timestamp
        if (timestamp > 0L) EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestamp)
        check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) { "eglSwapBuffers failed" }
    }

    private fun textureCoordinates(rotation: Int): FloatArray = when (((rotation % 360) + 360) % 360) {
        90 -> floatArrayOf(
            0f, 1f,
            0f, 0f,
            1f, 1f,
            1f, 0f,
        )
        180 -> floatArrayOf(
            1f, 1f,
            0f, 1f,
            1f, 0f,
            0f, 0f,
        )
        270 -> floatArrayOf(
            1f, 0f,
            1f, 1f,
            0f, 0f,
            0f, 1f,
        )
        else -> floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )
    }

    private fun createProgram(): Int {
        val vertex = compileShader(
            GLES20.GL_VERTEX_SHADER,
            """
                attribute vec4 aPosition;
                attribute vec4 aTexCoord;
                uniform mat4 uTexMatrix;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = (uTexMatrix * aTexCoord).xy;
                }
            """.trimIndent(),
        )
        val fragment = compileShader(
            GLES20.GL_FRAGMENT_SHADER,
            """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES sTexture;
                void main() {
                    gl_FragColor = texture2D(sTexture, vTexCoord);
                }
            """.trimIndent(),
        )
        val p = GLES20.glCreateProgram()
        check(p != 0) { "glCreateProgram failed" }
        GLES20.glAttachShader(p, vertex)
        GLES20.glAttachShader(p, fragment)
        GLES20.glLinkProgram(p)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw IllegalStateException("GL program link failed: $log")
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return p
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "glCreateShader failed" }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("GL shader compile failed: $log")
        }
        return shader
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val done = CountDownLatch(1)
        handler.post {
            try {
                runCatching { cameraSurface.release() }
                runCatching { surfaceTexture?.release() }
                surfaceTexture = null
                if (program != 0) GLES20.glDeleteProgram(program)
                if (oesTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTexture), 0)
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        eglDisplay,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(eglDisplay)
                }
            } finally {
                done.countDown()
            }
        }
        done.await(1500, TimeUnit.MILLISECONDS)
        thread.quitSafely()
    }
}
