package com.phonemonitor.monitor.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.phonemonitor.monitor.lut.Lut3D
import com.phonemonitor.monitor.settings.MonitorSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 监看渲染器（GLES 2.0）。
 *
 * 数据流：UVC 采集卡 → SurfaceTexture（外部 OES 纹理）→ 本渲染器
 *        → LUT 三线性套用 → 斑马/峰值/网格 → 屏幕。
 *
 * 没有采集卡时绘制内置测试图案（uTestPattern = 1），用于验证 LUT 管线。
 */
class MonitorRenderer(
    private val settings: MonitorSettings,
    private val lutProvider: () -> Lut3D?,
    private val onHistogram: (IntArray) -> Unit,
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit,
    private val onFps: (Float) -> Unit = {},
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    /** 由 GLSurfaceView 注入：请求下一帧渲染（RENDERMODE_WHEN_DIRTY 模式） */
    var requestRender: () -> Unit = {}

    /** Activity 在 UVC 设备就绪后调用（任意线程），渲染线程下一帧生效 */
    @Volatile
    var requestedPreviewSize: Pair<Int, Int>? = null

    private var program: GlProgram? = null
    private var oesTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val frameAvailable = AtomicBoolean(false)
    private val texMatrix = FloatArray(16)

    private var previewWidth = 1920
    private var previewHeight = 1080

    private var viewportW = 1
    private var viewportH = 1

    private var quadPositions: FloatBuffer? = null
    private var quadTexCoords: FloatBuffer? = null

    // LUT 纹理状态
    private var lutTextureId = 0
    private var uploadedLut: Lut3D? = null
    private var uLutSize = 33f
    private var uLutStride = 35f
    private var uLutDim = 210f
    private var uLutTiles = 6f

    // 直方图离屏缓冲
    private val histW = 160
    private val histH = 90
    private var histFbo = 0
    private var histTex = 0
    private val histPixels =
        ByteBuffer.allocateDirect(histW * histH * 4).order(ByteOrder.nativeOrder())
    private var frameCounter = 0

    // 帧率统计：每 30 帧汇报一次
    private var fpsFrameCount = 0
    private var fpsWindowStartNanos = 0L

    // 缓存的 uniform 位置
    private var uSource = -1
    private var uTexMatrix = -1
    private var uLut = -1
    private var uLutEnabled = -1
    private var uLutSizeLoc = -1
    private var uLutStrideLoc = -1
    private var uLutDimLoc = -1
    private var uLutTilesLoc = -1
    private var uPeaking = -1
    private var uPeakingThreshold = -1
    private var uZebra = -1
    private var uZebraThreshold = -1
    private var uGrid = -1
    private var uSafeArea = -1
    private var uDesqueeze = -1
    private var uTexel = -1
    private var uVideoPixels = -1
    private var uVideoOrigin = -1
    private var uTestPattern = -1
    private var uQuarterTurns = -1
    private var uMirror = -1
    private var aPosition = -1
    private var aTexCoord = -1

    // ---------------------------------------------------------------- lifecycle

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // 低延迟模式：关闭垂直同步。默认（间隔 1）会等到下一个 vsync 才交换缓冲，
        // 在 60Hz 屏上最多多等 16.7ms；间隔 0 立即交换，代价是可能撕裂。
        applySwapInterval(force = true)

        // GL 上下文重建时所有 GL 资源失效，重置引用
        lutTextureId = 0
        uploadedLut = null
        histFbo = 0
        histTex = 0

        val prog = GlProgram(Shaders.VERTEX, Shaders.FRAGMENT)
        program = prog
        cacheUniforms(prog)

        quadPositions = floatBuffer(
            floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
        )
        quadTexCoords = floatBuffer(
            floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f),
        )

        oesTextureId = createOesTexture()
        val st = SurfaceTexture(oesTextureId)
        st.setDefaultBufferSize(previewWidth, previewHeight)
        st.setOnFrameAvailableListener(this)
        surfaceTexture = st
        onSurfaceTextureReady(st)

        Matrix.setIdentityM(texMatrix, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportW = width.coerceAtLeast(1)
        viewportH = height.coerceAtLeast(1)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        frameAvailable.set(true)
        requestRender()
    }

    override fun onDrawFrame(gl: GL10?) {
        applySwapInterval(force = false)
        val st = surfaceTexture ?: return
        if (frameAvailable.compareAndSet(true, false)) {
            st.updateTexImage()
        }
        st.getTransformMatrix(texMatrix)

        requestedPreviewSize?.let { (w, h) ->
            requestedPreviewSize = null
            if (w > 0 && h > 0) {
                previewWidth = w
                previewHeight = h
                st.setDefaultBufferSize(w, h)
            }
        }

        uploadLutIfNeeded()

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        drawScene(viewportW, viewportH, overlays = true, testPattern = !hasRealSource())

        frameCounter++
        if (settings.histogram && frameCounter % 10 == 0) {
            drawHistogramPass(st)
        }

        // 帧率统计（供 HUD 显示，帮助判断采集卡实际出帧能力）
        fpsFrameCount++
        val nowNanos = System.nanoTime()
        if (fpsWindowStartNanos == 0L) fpsWindowStartNanos = nowNanos
        if (fpsFrameCount >= 30) {
            val elapsed = nowNanos - fpsWindowStartNanos
            if (elapsed > 0L) {
                onFps(fpsFrameCount * 1_000_000_000f / elapsed)
            }
            fpsFrameCount = 0
            fpsWindowStartNanos = nowNanos
        }
    }

    /** 已应用的 swap interval，-1 表示尚未应用 */
    private var appliedSwapInterval = -1

    /** 按「低延迟模式」开关同步 EGL swap interval（0 = 不等 vsync） */
    private fun applySwapInterval(force: Boolean) {
        val want = if (settings.lowLatency) 0 else 1
        if (!force && want == appliedSwapInterval) return
        runCatching {
            val display = EGL14.eglGetCurrentDisplay()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglSwapInterval(display, want)
                appliedSwapInterval = want
            }
        }
    }

    /** 采集卡是否已连接（由 Activity 维护） */
    @Volatile
    var sourceConnected: Boolean = false

    /** 预览旋转（90° 的倍数）：系统相机需要，UVC 恒为 0 */
    @Volatile
    var quarterTurns: Int = 0

    /** 是否左右镜像（系统相机需要，UVC 恒为 false） */
    @Volatile
    var mirror: Boolean = false

    private fun hasRealSource(): Boolean = sourceConnected

    // ---------------------------------------------------------------- drawing

    private fun drawScene(targetW: Int, targetH: Int, overlays: Boolean, testPattern: Boolean) {
        val prog = program ?: return
        val vp = computeViewport(targetW, targetH)
        GLES20.glViewport(vp[0], vp[1], vp[2], vp[3])

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        prog.use()

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(uSource, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (lutTextureId != 0) lutTextureId else 0)
        GLES20.glUniform1i(uLut, 1)

        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)

        GLES20.glUniform1f(
            uLutEnabled,
            if (settings.lutEnabled && lutTextureId != 0) 1f else 0f,
        )
        GLES20.glUniform1f(uLutSizeLoc, uLutSize)
        GLES20.glUniform1f(uLutStrideLoc, uLutStride)
        GLES20.glUniform1f(uLutDimLoc, uLutDim)
        GLES20.glUniform1f(uLutTilesLoc, uLutTiles)

        GLES20.glUniform1f(uPeaking, if (overlays && settings.peaking) 1f else 0f)
        GLES20.glUniform1f(uPeakingThreshold, settings.peakingThreshold)
        GLES20.glUniform1f(uZebra, if (overlays && settings.zebra) 1f else 0f)
        GLES20.glUniform1f(uZebraThreshold, settings.zebraThreshold)
        GLES20.glUniform1f(uGrid, if (overlays && settings.grid) 1f else 0f)
        GLES20.glUniform1f(uSafeArea, if (overlays && settings.safeArea) 1f else 0f)
        GLES20.glUniform1f(uDesqueeze, settings.desqueeze.coerceAtLeast(1.0f))
        GLES20.glUniform2f(uTexel, 1f / previewWidth, 1f / previewHeight)
        // 网格用屏幕像素坐标绘制，必须传入视口实际矩形（letterbox 时会偏移）
        GLES20.glUniform2f(uVideoPixels, vp[2].toFloat(), vp[3].toFloat())
        GLES20.glUniform2f(uVideoOrigin, vp[0].toFloat(), vp[1].toFloat())
        GLES20.glUniform1f(uTestPattern, if (testPattern) 1f else 0f)
        GLES20.glUniform1f(uQuarterTurns, quarterTurns.toFloat())
        GLES20.glUniform1f(uMirror, if (mirror) 1f else 0f)

        val pos = quadPositions ?: return
        val tex = quadTexCoords ?: return

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, pos)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, tex)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    /** 按视频宽高比做 letterbox，返回视口矩形 [x, y, w, h] */
    private fun computeViewport(targetW: Int, targetH: Int): IntArray {
        val videoAspect = if (quarterTurns % 2 == 1) {
            previewHeight.toFloat() / previewWidth.toFloat()
        } else {
            previewWidth.toFloat() / previewHeight.toFloat()
        }
        val viewAspect = targetW.toFloat() / targetH.toFloat()
        val vpW: Int
        val vpH: Int
        if (videoAspect > viewAspect) {
            vpW = targetW
            vpH = (targetW / videoAspect).toInt().coerceAtLeast(1)
        } else {
            vpH = targetH
            vpW = (targetH * videoAspect).toInt().coerceAtLeast(1)
        }
        return intArrayOf((targetW - vpW) / 2, (targetH - vpH) / 2, vpW, vpH)
    }

    // ---------------------------------------------------------------- histogram

    private fun drawHistogramPass(st: SurfaceTexture) {
        ensureHistTarget()

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, histFbo)
        drawScene(histW, histH, overlays = false, testPattern = !hasRealSource())
        GLES20.glReadPixels(
            0, 0, histW, histH,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, histPixels,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        histPixels.rewind()
        val hist = IntArray(256)
        val count = histW * histH
        for (i in 0 until count) {
            val r = histPixels.get(i * 4).toInt() and 0xFF
            val g = histPixels.get(i * 4 + 1).toInt() and 0xFF
            val b = histPixels.get(i * 4 + 2).toInt() and 0xFF
            val l = (r * 77 + g * 150 + b * 29) shr 8
            hist[l]++
        }
        onHistogram(hist)
    }

    private fun ensureHistTarget() {
        if (histFbo != 0 && histTex != 0) return
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        histTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, histTex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, histW, histH, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        histFbo = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, histFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, histTex, 0,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    // ---------------------------------------------------------------- LUT

    private fun uploadLutIfNeeded() {
        val lut = lutProvider() ?: return
        if (lut === uploadedLut && lutTextureId != 0) return

        val atlas = lut.packAtlas()
        if (lutTextureId == 0) {
            lutTextureId = create2dTexture()
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
        // glTexImage2D 要求 direct Buffer：堆上的 ByteArray/ByteBuffer.wrap 会抛
        // "Buffer is not direct"，必须拷进 allocateDirect 的缓冲区
        val pixels = ByteBuffer.allocateDirect(atlas.pixels.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(atlas.pixels)
                position(0)
            }
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            atlas.width, atlas.height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
            pixels,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        uLutSize = atlas.lutSize.toFloat()
        uLutStride = (atlas.lutSize + 2).toFloat()
        uLutDim = atlas.width.toFloat()
        uLutTiles = atlas.tilesPerRow.toFloat()
        uploadedLut = lut
    }

    // ---------------------------------------------------------------- helpers

    private fun cacheUniforms(prog: GlProgram) {
        uSource = prog.uniform("uSource")
        uTexMatrix = prog.uniform("uTexMatrix")
        uLut = prog.uniform("uLut")
        uLutEnabled = prog.uniform("uLutEnabled")
        uLutSizeLoc = prog.uniform("uLutSize")
        uLutStrideLoc = prog.uniform("uLutStride")
        uLutDimLoc = prog.uniform("uLutDim")
        uLutTilesLoc = prog.uniform("uLutTiles")
        uPeaking = prog.uniform("uPeaking")
        uPeakingThreshold = prog.uniform("uPeakingThreshold")
        uZebra = prog.uniform("uZebra")
        uZebraThreshold = prog.uniform("uZebraThreshold")
        uGrid = prog.uniform("uGrid")
        uSafeArea = prog.uniform("uSafeArea")
        uDesqueeze = prog.uniform("uDesqueeze")
        uTexel = prog.uniform("uTexel")
        uVideoPixels = prog.uniform("uVideoPixels")
        uVideoOrigin = prog.uniform("uVideoOrigin")
        uTestPattern = prog.uniform("uTestPattern")
        uQuarterTurns = prog.uniform("uQuarterTurns")
        uMirror = prog.uniform("uMirror")
        aPosition = prog.attrib("aPosition")
        aTexCoord = prog.attrib("aTexCoord")
    }

    private fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
        )
        return ids[0]
    }

    private fun create2dTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }
}
