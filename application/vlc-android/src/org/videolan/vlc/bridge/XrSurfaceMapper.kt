/*
 * Copyright © 2026 XRVLC contributors
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package org.videolan.vlc.bridge

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

internal class XrSurfaceMapper(
    private val onRenderFailure: (XrSurfaceMapper, Throwable) -> Unit
) {
    private val renderThread = HandlerThread("XrSurfaceMapper").apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val vertexBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(FULLSCREEN_VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(FULLSCREEN_VERTICES)
                position(0)
            }

    @Volatile
    private var released = false

    @Volatile
    private var inputSurfaceRef: Surface? = null

    val inputSurface: Surface?
        get() = inputSurfaceRef

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglConfig: EGLConfig? = null
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var outputSurface: Surface? = null

    private var oesTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var programId = 0
    private var aPositionLocation = -1
    private var aTexCoordLocation = -1
    private var uTextureLocation = -1
    private var uTexMatrixLocation = -1
    private var uRotationRadiansLocation = -1
    private var uStereoLocation = -1
    private var uInputSizeLocation = -1
    private var uFisheyeMappingEnabledLocation = -1
    private var uFisheyeProjectionFormulaLocation = -1
    private var uChromaKeyEnabledLocation = -1
    private var uChromaKeyColorLocation = -1
    private var uChromaKeyRangeLocation = -1
    private var uChromaKeyEdgeSmoothLocation = -1
    private var uChromaKeyDespillStrengthLocation = -1

    private var stereoMode = STEREO_MONO
    @Volatile
    private var rotationDegrees = 0
    private var fisheyeMappingEnabled = false
    private var chromaKeyEnabled = false
    @Volatile
    private var fisheyeProjectionFormula = FISHEYE_EQUIDISTANT
    @Volatile
    private var chromaKeyRed = 0x2B / 255f
    @Volatile
    private var chromaKeyGreen = 0xE6 / 255f
    @Volatile
    private var chromaKeyBlue = 0x40 / 255f
    @Volatile
    private var chromaKeyRange = 0.125f
    @Volatile
    private var chromaKeyEdgeSmooth = 0.125f
    @Volatile
    private var chromaKeyDespillStrength = 0.05f
    private var inputWidth = 1
    private var inputHeight = 1
    private var outputWidth = 1
    private var outputHeight = 1
    private var renderedFrameCount = 0L
    private var frameAvailablePending = false
    private var renderFailureReported = false
    private var pendingDominantColorCallback: ((Int?) -> Unit)? = null
    private val textureMatrix = FloatArray(16)

    fun configure(output: Surface, fisheyeMappingEnabled: Boolean, chromaKeyEnabled: Boolean, stereo: Int, width: Int, height: Int) {
        if (released) return
        runOnRenderThreadBlocking("configure") {
            configureOnRenderThread(output, fisheyeMappingEnabled, chromaKeyEnabled, stereo, width, height)
        }
    }

    fun detachOutput() {
        if (released) return
        runOnRenderThreadBlocking("detach-output") {
            detachOutputOnRenderThread()
        }
    }

    fun releaseInputLayer() {
        if (released) return
        runOnRenderThreadBlocking("release-input") {
            releaseInputLayerOnRenderThread()
        }
    }

    fun updateProcessingParameters(
        fisheyeProjectionFormula: Int,
        keyRed: Float,
        keyGreen: Float,
        keyBlue: Float,
        colorRange: Float,
        edgeSmooth: Float,
        despillStrength: Float
    ) {
        if (released) return
        this.fisheyeProjectionFormula = fisheyeProjectionFormula.coerceIn(FISHEYE_EQUIDISTANT, FISHEYE_ORTHOGRAPHIC)
        chromaKeyRed = keyRed.coerceIn(0f, 1f)
        chromaKeyGreen = keyGreen.coerceIn(0f, 1f)
        chromaKeyBlue = keyBlue.coerceIn(0f, 1f)
        chromaKeyRange = colorRange.coerceIn(0f, 0.25f)
        chromaKeyEdgeSmooth = edgeSmooth.coerceIn(0f, 0.25f)
        chromaKeyDespillStrength = despillStrength.coerceIn(0f, 0.1f)
    }

    fun updateRotation(degrees: Int) {
        if (released) return
        rotationDegrees = normalizeQuarterTurnDegrees(degrees)
        renderHandler.post {
            if (!released && renderedFrameCount > 0L)
                drawFrame()
        }
    }

    fun requestDominantColor(callback: (Int?) -> Unit) {
        if (released) {
            callback(null)
            return
        }
        renderHandler.post {
            if (released || eglSurface == EGL14.EGL_NO_SURFACE) {
                callback(null)
                return@post
            }
            pendingDominantColorCallback?.invoke(null)
            pendingDominantColorCallback = callback
            if (renderedFrameCount > 0L)
                drawFrame()
        }
    }

    fun release() {
        if (released) return
        released = true
        if (Looper.myLooper() == renderThread.looper) {
            releaseOnRenderThread()
            renderThread.quitSafely()
            return
        }

        val latch = CountDownLatch(1)
        renderHandler.post {
            try {
                releaseOnRenderThread()
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(1000L, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "release timed out")
        }
        renderThread.quitSafely()
    }

    private fun configureOnRenderThread(
        output: Surface,
        fisheyeMappingEnabled: Boolean,
        chromaKeyEnabled: Boolean,
        stereo: Int,
        width: Int,
        height: Int
    ) {
        ensureEgl(output)
        ensureProgram()
        this.fisheyeMappingEnabled = fisheyeMappingEnabled
        this.chromaKeyEnabled = chromaKeyEnabled
        stereoMode = stereo.coerceIn(STEREO_MONO, STEREO_TOP_BOTTOM)
        inputWidth = max(1, width)
        inputHeight = max(1, height)
        ensureInputSurface(inputWidth, inputHeight)
        renderFailureReported = false
        Log.e(
            TAG,
            "mapper configured output=$output input=${inputSurfaceRef} fisheye=${this.fisheyeMappingEnabled} " +
                "chromaKey=${this.chromaKeyEnabled} stereo=$stereoMode content=${inputWidth}x$inputHeight " +
                "formula=$fisheyeProjectionFormula key=($chromaKeyRed,$chromaKeyGreen,$chromaKeyBlue) " +
                "range=$chromaKeyRange edgeSmooth=$chromaKeyEdgeSmooth despill=$chromaKeyDespillStrength"
        )
        if (frameAvailablePending || renderedFrameCount > 0L)
            drawFrame()
    }

    private fun ensureEgl(output: Surface) {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "Unable to initialize EGL" }

            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            val configAttributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            check(
                EGL14.eglChooseConfig(
                    eglDisplay,
                    configAttributes,
                    0,
                    configs,
                    0,
                    configs.size,
                    configCount,
                    0
                ) && configCount[0] > 0
            ) { "Unable to choose EGL config" }

            eglConfig = configs[0]
            val config = eglConfig ?: error("Missing EGL config")
            val contextAttributes = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(
                eglDisplay,
                config,
                EGL14.EGL_NO_CONTEXT,
                contextAttributes,
                0
            )
            check(eglContext != EGL14.EGL_NO_CONTEXT) { "Unable to create EGL context" }
        }

        if (outputSurface !== output || eglSurface == EGL14.EGL_NO_SURFACE) {
            detachOutputOnRenderThread()
            outputSurface = output
            val config = eglConfig ?: error("Missing EGL config")
            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                config,
                output,
                intArrayOf(EGL14.EGL_NONE),
                0
            )
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "Unable to create EGL window surface" }
        }

        makeCurrent()
        queryOutputSize()
    }

    private fun ensureInputSurface(width: Int, height: Int) {
        if (oesTextureId == 0) {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            oesTextureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            checkGl("create oes texture")

            surfaceTexture = SurfaceTexture(oesTextureId).apply {
                setDefaultBufferSize(width, height)
                setOnFrameAvailableListener(
                    SurfaceTexture.OnFrameAvailableListener {
                        frameAvailablePending = true
                        renderHandler.post { drawFrame() }
                    },
                    renderHandler
                )
            }
            inputSurfaceRef = Surface(surfaceTexture)
            Log.e(TAG, "mapper input Surface created texture=$oesTextureId surface=$inputSurfaceRef")
        } else {
            surfaceTexture?.setDefaultBufferSize(width, height)
        }
    }

    private fun ensureProgram() {
        if (programId != 0) return

        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FISHEYE_FRAGMENT_SHADER)
        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(programId)
            GLES20.glDeleteProgram(programId)
            programId = 0
            error("Unable to link mapper program: $log")
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        aPositionLocation = GLES20.glGetAttribLocation(programId, "aPosition")
        aTexCoordLocation = GLES20.glGetAttribLocation(programId, "aTexCoord")
        uTextureLocation = GLES20.glGetUniformLocation(programId, "uTexture")
        uTexMatrixLocation = GLES20.glGetUniformLocation(programId, "uTexMatrix")
        uRotationRadiansLocation = GLES20.glGetUniformLocation(programId, "uRotationRadians")
        uStereoLocation = GLES20.glGetUniformLocation(programId, "uStereo")
        uInputSizeLocation = GLES20.glGetUniformLocation(programId, "uInputSize")
        uFisheyeMappingEnabledLocation = GLES20.glGetUniformLocation(programId, "uFisheyeMappingEnabled")
        uFisheyeProjectionFormulaLocation = GLES20.glGetUniformLocation(programId, "uFisheyeProjectionFormula")
        uChromaKeyEnabledLocation = GLES20.glGetUniformLocation(programId, "uChromaKeyEnabled")
        uChromaKeyColorLocation = GLES20.glGetUniformLocation(programId, "uChromaKeyColor")
        uChromaKeyRangeLocation = GLES20.glGetUniformLocation(programId, "uChromaKeyRange")
        uChromaKeyEdgeSmoothLocation = GLES20.glGetUniformLocation(programId, "uChromaKeyEdgeSmooth")
        uChromaKeyDespillStrengthLocation = GLES20.glGetUniformLocation(programId, "uChromaKeyDespillStrength")
    }

    private fun drawFrame() {
        if (released) return
        val texture = surfaceTexture ?: return
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return

        runCatching {
            makeCurrent()
            texture.updateTexImage()
            frameAvailablePending = false
            texture.getTransformMatrix(textureMatrix)

            GLES20.glViewport(0, 0, outputWidth, outputHeight)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(programId)

            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(aPositionLocation)
            GLES20.glVertexAttribPointer(aPositionLocation, 2, GLES20.GL_FLOAT, false, VERTEX_STRIDE_BYTES, vertexBuffer)

            vertexBuffer.position(2)
            GLES20.glEnableVertexAttribArray(aTexCoordLocation)
            GLES20.glVertexAttribPointer(aTexCoordLocation, 2, GLES20.GL_FLOAT, false, VERTEX_STRIDE_BYTES, vertexBuffer)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glUniform1i(uTextureLocation, 0)
            GLES20.glUniformMatrix4fv(uTexMatrixLocation, 1, false, textureMatrix, 0)
            GLES20.glUniform1f(uRotationRadiansLocation, Math.toRadians(rotationDegrees.toDouble()).toFloat())
            GLES20.glUniform1i(uStereoLocation, stereoMode)
            GLES20.glUniform2f(uInputSizeLocation, inputWidth.toFloat(), inputHeight.toFloat())
            GLES20.glUniform1i(uFisheyeMappingEnabledLocation, if (fisheyeMappingEnabled) 1 else 0)
            GLES20.glUniform1i(uFisheyeProjectionFormulaLocation, fisheyeProjectionFormula)
            GLES20.glUniform1i(uChromaKeyEnabledLocation, if (chromaKeyEnabled) 1 else 0)
            GLES20.glUniform3f(uChromaKeyColorLocation, chromaKeyRed, chromaKeyGreen, chromaKeyBlue)
            GLES20.glUniform1f(uChromaKeyRangeLocation, chromaKeyRange)
            GLES20.glUniform1f(uChromaKeyEdgeSmoothLocation, chromaKeyEdgeSmooth)
            GLES20.glUniform1f(uChromaKeyDespillStrengthLocation, chromaKeyDespillStrength)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPositionLocation)
            GLES20.glDisableVertexAttribArray(aTexCoordLocation)
            checkGl("draw frame")
            pendingDominantColorCallback?.let { callback ->
                pendingDominantColorCallback = null
                val color = runCatching { extractDominantEdgeColor() }
                    .onFailure { Log.e(TAG, "dominant color extraction failed", it) }
                    .getOrNull()
                callback(color)
            }
            check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                "eglSwapBuffers failed error=0x${Integer.toHexString(EGL14.eglGetError())}"
            }
            renderedFrameCount++
        }.onFailure {
            Log.e(TAG, "drawFrame failed", it)
            if (!renderFailureReported) {
                renderFailureReported = true
                onRenderFailure(this, it)
            }
        }
    }

    private fun extractDominantEdgeColor(): Int? {
        val thickness = minOf(EDGE_READBACK_THICKNESS, max(1, minOf(outputWidth, outputHeight) / 8))
        val strips = arrayOf(
            intArrayOf(0, 0, outputWidth, thickness),
            intArrayOf(0, outputHeight - thickness, outputWidth, thickness),
            intArrayOf(0, thickness, thickness, max(1, outputHeight - thickness * 2)),
            intArrayOf(outputWidth - thickness, thickness, thickness, max(1, outputHeight - thickness * 2))
        )
        val counts = IntArray(COLOR_HISTOGRAM_BINS * COLOR_HISTOGRAM_BINS)
        val redTotals = LongArray(counts.size)
        val greenTotals = LongArray(counts.size)
        val blueTotals = LongArray(counts.size)
        var validSamples = 0

        for (rect in strips) {
            val width = rect[2]
            val height = rect[3]
            val rgba = ByteBuffer.allocateDirect(width * height * RGBA_BYTES_PER_PIXEL)
                .order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(
                rect[0],
                rect[1],
                width,
                height,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                rgba
            )
            checkGl("read dominant color edge")

            for (pixel in 0 until width * height) {
                val offset = pixel * RGBA_BYTES_PER_PIXEL
                val red = rgba.get(offset).toInt() and 0xff
                val green = rgba.get(offset + 1).toInt() and 0xff
                val blue = rgba.get(offset + 2).toInt() and 0xff
                val redUnit = red / 255f
                val greenUnit = green / 255f
                val blueUnit = blue / 255f
                val cg = -0.25f * redUnit + 0.5f * greenUnit - 0.25f * blueUnit
                val co = 0.5f * redUnit - 0.5f * blueUnit
                if (cg * cg + co * co < MIN_CHROMA_SQUARED) continue

                val cgBin = (((cg + 0.5f) * COLOR_HISTOGRAM_BINS).toInt())
                    .coerceIn(0, COLOR_HISTOGRAM_BINS - 1)
                val coBin = (((co + 0.5f) * COLOR_HISTOGRAM_BINS).toInt())
                    .coerceIn(0, COLOR_HISTOGRAM_BINS - 1)
                val bin = cgBin * COLOR_HISTOGRAM_BINS + coBin
                counts[bin]++
                redTotals[bin] += red.toLong()
                greenTotals[bin] += green.toLong()
                blueTotals[bin] += blue.toLong()
                validSamples++
            }
        }

        if (validSamples < MIN_VALID_COLOR_SAMPLES) return null
        var dominantBin = 0
        for (bin in 1 until counts.size) {
            if (counts[bin] > counts[dominantBin]) dominantBin = bin
        }
        val dominantCount = counts[dominantBin]
        if (dominantCount < max(MIN_VALID_COLOR_SAMPLES, validSamples / 10)) return null

        val red = ((redTotals[dominantBin] + dominantCount / 2) / dominantCount).toInt()
        val green = ((greenTotals[dominantBin] + dominantCount / 2) / dominantCount).toInt()
        val blue = ((blueTotals[dominantBin] + dominantCount / 2) / dominantCount).toInt()
        return (red shl 16) or (green shl 8) or blue
    }

    private fun queryOutputSize() {
        val width = IntArray(1)
        val height = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, width, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, height, 0)
        outputWidth = max(1, width[0])
        outputHeight = max(1, height[0])
    }

    private fun makeCurrent() {
        check(
            EGL14.eglMakeCurrent(
                eglDisplay,
                eglSurface,
                eglSurface,
                eglContext
            )
        ) { "Unable to make EGL context current" }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Unable to compile shader: $log")
        }
        return shader
    }

    private fun releaseOnRenderThread() {
        pendingDominantColorCallback?.invoke(null)
        pendingDominantColorCallback = null
        releaseInputLayerOnRenderThread()
        if (programId != 0) {
            makeCurrentIfPossible()
            GLES20.glDeleteProgram(programId)
            programId = 0
        }

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
        outputSurface = null
        Log.e(TAG, "mapper released")
    }

    private fun releaseInputLayerOnRenderThread() {
        pendingDominantColorCallback?.invoke(null)
        pendingDominantColorCallback = null
        inputSurfaceRef?.release()
        inputSurfaceRef = null
        surfaceTexture?.setOnFrameAvailableListener(null as SurfaceTexture.OnFrameAvailableListener?)
        surfaceTexture?.release()
        surfaceTexture = null

        if (oesTextureId != 0) {
            makeCurrentIfPossible()
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = 0
        }
        frameAvailablePending = false
        renderedFrameCount = 0L
    }

    private fun detachOutputOnRenderThread() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
        outputSurface = null
        outputWidth = 1
        outputHeight = 1
    }

    private fun makeCurrentIfPossible() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY ||
            eglSurface == EGL14.EGL_NO_SURFACE ||
            eglContext == EGL14.EGL_NO_CONTEXT)
            return

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun runOnRenderThreadBlocking(label: String, block: () -> Unit) {
        if (Looper.myLooper() == renderThread.looper) {
            block()
            return
        }

        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        renderHandler.post {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(1500L, TimeUnit.MILLISECONDS)) {
            error("Timed out waiting for mapper $label")
        }
        failure?.let { throw it }
    }

    private fun checkGl(label: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "GL error after $label: 0x${Integer.toHexString(error)}" }
    }

    companion object {
        private const val TAG = "XrSurfaceMapper"
        private const val STEREO_MONO = 0
        private const val STEREO_TOP_BOTTOM = 2
        private const val FISHEYE_EQUIDISTANT = 0
        private const val FISHEYE_ORTHOGRAPHIC = 3
        private const val VERTEX_STRIDE_BYTES = 4 * 4
        private const val RGBA_BYTES_PER_PIXEL = 4
        private const val EDGE_READBACK_THICKNESS = 8
        private const val COLOR_HISTOGRAM_BINS = 16
        private const val MIN_VALID_COLOR_SAMPLES = 32
        private const val MIN_CHROMA_SQUARED = 0.0036f

        private fun normalizeQuarterTurnDegrees(degrees: Int): Int {
            var normalized = degrees % 360
            if (normalized > 180) normalized -= 360
            if (normalized < -180) normalized += 360
            val steps = if (normalized >= 0) {
                (normalized + 45) / 90
            } else {
                (normalized - 45) / 90
            }
            return steps * 90
        }

        private val FULLSCREEN_VERTICES = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f
        )

        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying highp vec2 vUv;

            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vUv = aTexCoord;
            }
        """

        private const val FISHEYE_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #define XR_FISHEYE_PRECISION highp
            #else
            precision mediump float;
            #define XR_FISHEYE_PRECISION mediump
            #endif

            uniform samplerExternalOES uTexture;
            uniform XR_FISHEYE_PRECISION mat4 uTexMatrix;
            uniform XR_FISHEYE_PRECISION float uRotationRadians;
            uniform int uStereo;
            uniform XR_FISHEYE_PRECISION vec2 uInputSize;
            uniform int uFisheyeMappingEnabled;
            uniform int uFisheyeProjectionFormula;
            uniform int uChromaKeyEnabled;
            uniform XR_FISHEYE_PRECISION vec3 uChromaKeyColor;
            uniform XR_FISHEYE_PRECISION float uChromaKeyRange;
            uniform XR_FISHEYE_PRECISION float uChromaKeyEdgeSmooth;
            uniform XR_FISHEYE_PRECISION float uChromaKeyDespillStrength;
            varying XR_FISHEYE_PRECISION vec2 vUv;

            const XR_FISHEYE_PRECISION float PI = 3.14159265358979323846264;

            vec3 rgbToYcgco(vec3 color) {
                float y = 0.25 * color.r + 0.5 * color.g + 0.25 * color.b;
                float cg = -0.25 * color.r + 0.5 * color.g - 0.25 * color.b;
                float co = 0.5 * color.r - 0.5 * color.b;
                return vec3(y, cg, co);
            }

            vec3 ycgcoToRgb(vec3 color) {
                float base = color.x - color.y;
                return vec3(base + color.z, color.x + color.y, base - color.z);
            }

            float chromaDistance(vec3 color, vec3 keyYcgco) {
                return clamp(length(rgbToYcgco(color).yz - keyYcgco.yz), 0.0, 1.0);
            }

            float alphaFromDistance(float distance) {
                if (distance <= uChromaKeyRange) return 0.0;
                if (uChromaKeyEdgeSmooth <= 0.00001) return 1.0;
                return smoothstep(
                    uChromaKeyRange,
                    min(1.0, uChromaKeyRange + uChromaKeyEdgeSmooth),
                    distance
                );
            }

            float fisheyeRadius(float theta) {
                float thetaMax = 0.5 * PI;
                if (uFisheyeProjectionFormula == 1) {
                    return sin(theta * 0.5) / sin(thetaMax * 0.5);
                }
                if (uFisheyeProjectionFormula == 2) {
                    return tan(theta * 0.5) / tan(thetaMax * 0.5);
                }
                if (uFisheyeProjectionFormula == 3) {
                    return sin(theta) / sin(thetaMax);
                }
                return theta / thetaMax;
            }

            vec2 rotateUnitUv(vec2 uv) {
                vec2 centered = uv - vec2(0.5);
                float cosine = cos(uRotationRadians);
                float sine = sin(uRotationRadians);
                return vec2(
                    cosine * centered.x + sine * centered.y,
                    -sine * centered.x + cosine * centered.y
                ) + vec2(0.5);
            }

            bool outsideUnitUv(vec2 uv) {
                return uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0;
            }

            void main() {
                vec2 uv = clamp(vUv, 0.0, 1.0);
                vec2 sampledUv = uv;

                if (uFisheyeMappingEnabled != 0) {
                    vec2 localUv = uv;
                    vec2 eyeCenter = vec2(0.5, 0.5);
                    float eyeWidth = max(uInputSize.x, 1.0);
                    float eyeHeight = max(uInputSize.y, 1.0);

                    if (uStereo == 1) {
                        bool rightEye = uv.x >= 0.5;
                        localUv.x = rightEye ? (uv.x - 0.5) * 2.0 : uv.x * 2.0;
                        eyeCenter = vec2(rightEye ? 0.75 : 0.25, 0.5);
                        eyeWidth = max(uInputSize.x * 0.5, 1.0);
                    } else if (uStereo == 2) {
                        bool bottomEye = uv.y >= 0.5;
                        localUv.y = bottomEye ? (uv.y - 0.5) * 2.0 : uv.y * 2.0;
                        eyeCenter = vec2(0.5, bottomEye ? 0.75 : 0.25);
                        eyeHeight = max(uInputSize.y * 0.5, 1.0);
                    }

                    localUv = rotateUnitUv(localUv);
                    if (outsideUnitUv(localUv)) {
                        gl_FragColor = vec4(0.0);
                        return;
                    }

                    float yaw = (localUv.x - 0.5) * PI;
                    float pitch = (localUv.y - 0.5) * PI;
                    vec3 dir = normalize(vec3(sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch)));
                    float theta = acos(clamp(dir.z, 0.0, 1.0));
                    float rho = fisheyeRadius(theta);
                    if (rho > 1.0) {
                        gl_FragColor = vec4(0.0);
                        return;
                    }

                    vec2 radial = length(dir.xy) > 0.00001 ? normalize(dir.xy) : vec2(0.0);
                    float radiusPx = min(eyeWidth, eyeHeight) * 0.5;
                    vec2 eyeRadiusUv = vec2(
                        radiusPx / max(uInputSize.x, 1.0),
                        radiusPx / max(uInputSize.y, 1.0)
                    );
                    sampledUv = eyeCenter + radial * rho * eyeRadiusUv;
                } else if (uStereo == 1) {
                    float eye = uv.x >= 0.5 ? 1.0 : 0.0;
                    vec2 localUv = rotateUnitUv(vec2(uv.x * 2.0 - eye, uv.y));
                    if (outsideUnitUv(localUv)) {
                        gl_FragColor = vec4(0.0);
                        return;
                    }
                    sampledUv = vec2((localUv.x + eye) * 0.5, localUv.y);
                } else if (uStereo == 2) {
                    float eye = uv.y >= 0.5 ? 1.0 : 0.0;
                    vec2 localUv = rotateUnitUv(vec2(uv.x, uv.y * 2.0 - eye));
                    if (outsideUnitUv(localUv)) {
                        gl_FragColor = vec4(0.0);
                        return;
                    }
                    sampledUv = vec2(localUv.x, (localUv.y + eye) * 0.5);
                } else {
                    sampledUv = rotateUnitUv(uv);
                    if (outsideUnitUv(sampledUv)) {
                        gl_FragColor = vec4(0.0);
                        return;
                    }
                }

                vec2 inputUv = vec2(sampledUv.x, 1.0 - sampledUv.y);
                vec2 transformedUv = (uTexMatrix * vec4(inputUv, 0.0, 1.0)).xy;
                vec3 rgb = texture2D(uTexture, transformedUv).rgb;
                float alpha = 1.0;
                if (uChromaKeyEnabled != 0) {
                    vec3 keyYcgco = rgbToYcgco(uChromaKeyColor);
                    alpha = alphaFromDistance(chromaDistance(rgb, keyYcgco));

                    if (uChromaKeyDespillStrength > 0.00001) {
                        vec3 ycgco = rgbToYcgco(rgb);
                        vec2 keyChroma = keyYcgco.yz;
                        float keyMagnitude = length(keyChroma);
                        if (keyMagnitude > 0.00001) {
                            vec2 keyDirection = keyChroma / keyMagnitude;
                            float spill = max(dot(ycgco.yz, keyDirection), 0.0);
                            float edgeWeight = clamp(4.0 * alpha * (1.0 - alpha), 0.0, 1.0);
                            ycgco.yz -= keyDirection * spill * uChromaKeyDespillStrength * edgeWeight;
                            rgb = clamp(ycgcoToRgb(ycgco), 0.0, 1.0);
                        }
                    }
                }
                gl_FragColor = vec4(rgb, alpha);
            }
        """
    }
}
