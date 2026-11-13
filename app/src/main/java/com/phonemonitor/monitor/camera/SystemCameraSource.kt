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
class SystemCameraSource(private val context: Context) : FrameSource {

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
            previewSize = chooseSize(map?.getOutputSizes(SurfaceTexture::class.java))
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
                            callback?.onStatus(
                                FrameSource.Status.CONNECTED,
                                "系统相机 ${previewSize.width}x${previewSize.height}",
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

    private fun chooseSize(sizes: Array<Size>?): Size {
        if (sizes.isNullOrEmpty()) return Size(1280, 720)
        for ((w, h) in PREFERRED_SIZES) {
            sizes.firstOrNull { it.width == w && it.height == h }?.let { return it }
        }
        return sizes.maxByOrNull { it.width * it.height } ?: Size(1280, 720)
    }

    companion object {
        private const val TAG = "SystemCameraSource"
        private val PREFERRED_SIZES = listOf(1920 to 1080, 1280 to 720, 640 to 480)
    }
}
