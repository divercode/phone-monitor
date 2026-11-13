package com.phonemonitor.monitor.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import com.phonemonitor.monitor.uvc.FrameSource

/**
 * 手机系统相机输入源（测试用）。
 *
 * 没有 HDMI 采集卡时，用手机自带的摄像头当信号源，验证 LUT/峰值/斑马/网格/直方图等
 * 全部渲染管线。与 UVC 采集卡共用同一个 SurfaceTexture 输入路径，渲染层无需区分来源。
 */
class SystemCameraSource(
    private val context: Context,
    /** true = 全画幅（按传感器比例，通常 4:3，避免上下裁剪）；false = 16:9 裁剪 */
    private val fullFrame: Boolean = true,
) : FrameSource {

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val handler = Handler(Looper.getMainLooper())

    private var callback: FrameSource.Callback? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    private var previewSize = Size(1280, 720)

    @Volatile
    private var quarterTurns = 0

    override fun start(callback: FrameSource.Callback) {
        this.callback = callback
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            callback.onStatus(FrameSource.Status.ERROR, "未授予相机权限")
            return
        }
        callback.onStatus(FrameSource.Status.WAITING, "正在打开系统相机…")
        handler.post { openCamera() }
    }

    override fun attach(surfaceTexture: SurfaceTexture) {
        this.surfaceTexture = surfaceTexture
        handler.post {
            if (cameraDevice == null) {
                openCamera()
            } else {
                closeSession()
                createSession()
            }
        }
    }

    override fun stop() {
        handler.post { closeCamera() }
    }

    override fun release() {
        handler.post {
            closeCamera()
            callback = null
            surfaceTexture = null
        }
    }

    // ---------------------------------------------------------------- camera2

    private fun openCamera() {
        if (cameraDevice != null) return
        val st = surfaceTexture ?: run {
            callback?.onStatus(FrameSource.Status.WAITING, "等待渲染表面…")
            return
        }
        runCatching {
            val id = pickCameraId()
            val chars = cameraManager.getCameraCharacteristics(id)
            quarterTurns = (((chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) / 90) % 4)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val targetAspect = if (fullFrame && active != null) {
                active.width().toFloat() / active.height().toFloat()
            } else {
                16f / 9f
            }
            previewSize = chooseSize(map?.getOutputSizes(SurfaceTexture::class.java), targetAspect)
            callback?.onOrientation(quarterTurns)
            // Camera2 预览到 SurfaceTexture 后画面左右镜像，需要渲染层翻转一次
            callback?.onMirror(true)
            callback?.onPreviewSize(previewSize.width, previewSize.height)
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            cameraManager.openCamera(id, deviceCallback, handler)
        }.onFailure {
            Log.e(TAG, "openCamera failed", it)
            callback?.onStatus(FrameSource.Status.ERROR, "系统相机打开失败：${it.message}")
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            createSession()
        }

        override fun onDisconnected(device: CameraDevice) {
            closeCamera()
            callback?.onStatus(FrameSource.Status.WAITING, "系统相机已断开")
        }

        override fun onError(device: CameraDevice, error: Int) {
            Log.e(TAG, "camera error $error")
            closeCamera()
            callback?.onStatus(FrameSource.Status.ERROR, "系统相机错误（$error）")
        }
    }

    private fun createSession() {
        val device = cameraDevice ?: return
        val st = surfaceTexture ?: return
        closeSession()
        previewSurface?.release()
        runCatching {
            val surface = Surface(st)
            previewSurface = surface
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
            }
            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(sess: CameraCaptureSession) {
                        session = sess
                        runCatching {
                            sess.setRepeatingRequest(request.build(), null, handler)
                        }.onSuccess {
                            // 按旋转后的方向显示分辨率（竖幅时宽高互换）
                            val dw = if (quarterTurns % 2 == 1) previewSize.height else previewSize.width
                            val dh = if (quarterTurns % 2 == 1) previewSize.width else previewSize.height
                            callback?.onStatus(
                                FrameSource.Status.CONNECTED,
                                "系统相机 ${dw}x${dh}",
                            )
                        }.onFailure {
                            callback?.onStatus(
                                FrameSource.Status.ERROR,
                                "系统相机预览失败：${it.message}",
                            )
                        }
                    }

                    override fun onConfigureFailed(sess: CameraCaptureSession) {
                        callback?.onStatus(FrameSource.Status.ERROR, "系统相机配置失败")
                    }
                },
                handler,
            )
        }.onFailure {
            Log.e(TAG, "createSession failed", it)
            callback?.onStatus(FrameSource.Status.ERROR, "系统相机会话失败：${it.message}")
        }
    }

    private fun closeSession() {
        runCatching { session?.close() }
        session = null
    }

    private fun closeCamera() {
        closeSession()
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { previewSurface?.release() }
        previewSurface = null
    }

    private fun pickCameraId(): String {
        val ids = cameraManager.cameraIdList
        if (ids.isEmpty()) throw IllegalStateException("没有可用摄像头")
        val back = ids.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        return back ?: ids.first()
    }

    /**
     * 选预览尺寸：优先宽高比最接近 [targetAspect]（全画幅模式 = 传感器比例），
     * 同比例下取面积最大的；避免 16:9 流把 4:3 传感器上下裁掉。
     */
    private fun chooseSize(sizes: Array<Size>?, targetAspect: Float): Size {
        if (sizes.isNullOrEmpty()) return Size(1280, 720)
        val candidates = sizes.filter { it.width <= 1920 && it.height <= 1920 }
        if (candidates.isEmpty()) return sizes[0]
        return candidates.minWithOrNull(
            compareBy<Size> {
                kotlin.math.abs(it.width.toFloat() / it.height - targetAspect)
            }.thenByDescending { it.width * it.height },
        ) ?: sizes[0]
    }

    companion object {
        private const val TAG = "SystemCameraSource"
    }
}
