package com.phonemonitor.monitor.uvc

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.util.Log
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size
import com.serenegiant.utils.UVCUtils

/**
 * UVC 采集卡输入源，使用 `com.herohan:UVCAndroid` 的公开封装 [CameraHelper]。
 *
 * 与直接调用 `USBMonitor` 相比，这一层由库负责：
 *  - USB 热插拔监听与权限申请（拒绝时回调 [ICameraHelper.StateCallback.onCancel]）
 *  - 设备打开/关闭、相机打开/关闭的完整状态机
 *  - 所有回调都切到主线程派发
 *
 * 画面通过 `addSurface(SurfaceTexture, false)` 直接进 GL 线程的外部 OES 纹理，
 * 零 CPU 拷贝，延迟只由相机 HDMI 输出 + 采集卡编码 + 一帧 GL 渲染组成。
 */
class UvcCameraSource(
    context: Context,
    private val preferredWidth: Int = 1920,
    private val preferredHeight: Int = 1080,
) : FrameSource {

    private val appContext: Context = context.applicationContext

    /** 库的公开封装；首次 start() 时创建，release() 后置空 */
    private var helper: CameraHelper? = null

    @Volatile
    private var surfaceTexture: SurfaceTexture? = null

    /** 当前 SurfaceTexture 是否已成功交给 CameraHelper（用于重建时先摘除旧的） */
    @Volatile
    private var surfaceAttached = false

    @Volatile
    private var callback: FrameSource.Callback? = null

    /** 当前实际生效的预览尺寸（打开相机后协商得到） */
    @Volatile
    private var previewWidth = preferredWidth

    @Volatile
    private var previewHeight = preferredHeight

    private val stateCallback = object : ICameraHelper.StateCallback {

        override fun onAttach(device: UsbDevice) {
            Log.i(TAG, "onAttach: $device")
            if (!isUvcDevice(device)) return
            // 选中设备后库内部会申请 USB 权限，成功则回调 onDeviceOpen
            helper?.selectDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            Log.i(TAG, "onDeviceOpen: $device isFirstOpen=$isFirstOpen")
            // 设备就绪后打开相机（成功后回调 onCameraOpen）
            helper?.openCamera(desiredSize())
        }

        override fun onCameraOpen(device: UsbDevice) {
            Log.i(TAG, "onCameraOpen: $device")
            val h = helper ?: return

            // 采集卡支持的尺寸/格式差异很大：打开后按 1080p > 720p > 最大面积 协商，
            // 同分辨率优先未压缩（延迟最低），其次 MJPEG（省带宽）
            val target = choosePreviewSize(h)
            val current = h.previewSize
            if (target != null &&
                (current == null || target.width != current.width || target.height != current.height)
            ) {
                Log.i(
                    TAG,
                    "切换预览尺寸 ${current?.width}x${current?.height} -> " +
                        "${target.width}x${target.height}@${target.fps}",
                )
                runCatching { h.setPreviewSize(target) }
                previewWidth = target.width
                previewHeight = target.height
            } else {
                previewWidth = current?.width ?: preferredWidth
                previewHeight = current?.height ?: preferredHeight
            }
            val liveSize = target ?: current
            val fpsTag = liveSize?.fps?.takeIf { it > 0 }?.let { "@$it" } ?: ""
            callback?.onPreviewSize(previewWidth, previewHeight)

            // 渲染器可能还没就绪：没有 SurfaceTexture 时只报告状态，等 attach() 再挂载并起预览
            val st = surfaceTexture
            if (st != null) {
                st.setDefaultBufferSize(previewWidth, previewHeight)
                h.addSurface(st, false)
                surfaceAttached = true
                h.startPreview()
                callback?.onStatus(
                    FrameSource.Status.CONNECTED,
                    "UVC 已连接 ${previewWidth}x$previewHeight$fpsTag",
                )
            } else {
                callback?.onStatus(
                    FrameSource.Status.CONNECTED,
                    "UVC 已连接 ${previewWidth}x$previewHeight$fpsTag（等待画面）",
                )
            }
        }

        override fun onCameraClose(device: UsbDevice) {
            Log.i(TAG, "onCameraClose: $device")
            callback?.onStatus(FrameSource.Status.WAITING, "相机已关闭")
        }

        override fun onDeviceClose(device: UsbDevice) {
            Log.i(TAG, "onDeviceClose: $device")
        }

        override fun onDetach(device: UsbDevice) {
            Log.i(TAG, "onDetach: $device")
            callback?.onStatus(FrameSource.Status.WAITING, "采集卡已拔出")
        }

        override fun onCancel(device: UsbDevice) {
            Log.i(TAG, "onCancel: $device")
            callback?.onStatus(FrameSource.Status.ERROR, "USB 权限被拒绝")
        }

        override fun onError(device: UsbDevice, e: CameraException) {
            Log.e(TAG, "onError: $device $e")
            callback?.onStatus(
                FrameSource.Status.ERROR,
                "相机错误：${e.message ?: e.code}",
            )
        }
    }

    override fun start(callback: FrameSource.Callback) {
        this.callback = callback
        val h = ensureHelper()
        // 冷启动时设备可能已经插着：主动选中一次
        val device = h.deviceList?.firstOrNull { isUvcDevice(it) }
        if (device != null) {
            h.selectDevice(device)
        } else {
            callback.onStatus(
                FrameSource.Status.WAITING,
                "等待 UVC 采集卡…（当前显示测试图案）",
            )
        }
    }

    override fun attach(surfaceTexture: SurfaceTexture) {
        val old = this.surfaceTexture
        this.surfaceTexture = surfaceTexture
        val h = helper ?: return

        // GL 上下文重建后 SurfaceTexture 会换新，旧的需要先摘掉，避免库内部持有失效纹理
        if (surfaceAttached && old != null && old !== surfaceTexture) {
            runCatching { h.removeSurface(old) }
            surfaceAttached = false
        }
        if (h.isCameraOpened) {
            h.addSurface(surfaceTexture, false)
            surfaceAttached = true
            h.startPreview()
        }
    }

    override fun stop() {
        val h = helper ?: return
        runCatching { h.stopPreview() }
        surfaceTexture?.let { st -> runCatching { h.removeSurface(st) } }
        surfaceAttached = false
        runCatching { h.closeCamera() }
    }

    override fun release() {
        val h = helper ?: run {
            callback = null
            surfaceTexture = null
            surfaceAttached = false
            return
        }
        helper = null
        surfaceAttached = false
        surfaceTexture = null
        callback = null
        runCatching { h.releaseAll() }
    }

    // ------------------------------------------------------------------ helpers

    private fun ensureHelper(): CameraHelper {
        helper?.let { return it }
        // 库内部通过 UVCUtils.getApplication() 取 Application，先显式初始化避免反射失败
        UVCUtils.init(appContext)
        val h = CameraHelper()
        h.setStateCallback(stateCallback)
        helper = h
        return h
    }

    private fun desiredSize(): Size =
        Size(
            MJPEG_FRAME_TYPE,
            preferredWidth.coerceAtLeast(640),
            preferredHeight.coerceAtLeast(480),
            30,
            arrayListOf(30),
        )

    /**
     * 在设备支持的尺寸里选一个：优先 1080p，其次 720p，最后最大面积。
     * 同分辨率下**优先未压缩格式（低延迟）**，其次 MJPEG（省带宽），最后任意格式。
     * 这样既能吃满采集卡的低延迟模式，也能兼容只有 MJPEG 的廉价卡。
     */
    private fun choosePreviewSize(h: CameraHelper): Size? {
        val list = h.supportedSizeList ?: return null
        if (list.isEmpty()) return null

        fun find(width: Int, height: Int, type: Int?): Size? =
            list.firstOrNull {
                it.width == width && it.height == height && (type == null || it.type == type)
            }

        return find(1920, 1080, UNCOMPRESSED_FRAME_TYPE)
            ?: find(1920, 1080, MJPEG_FRAME_TYPE)
            ?: find(1920, 1080, null)
            ?: find(1280, 720, UNCOMPRESSED_FRAME_TYPE)
            ?: find(1280, 720, MJPEG_FRAME_TYPE)
            ?: find(1280, 720, null)
            ?: list.maxByOrNull { it.width * it.height }
    }

    private fun isUvcDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == USB_CLASS_VIDEO) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == USB_CLASS_VIDEO) return true
        }
        return false
    }

    companion object {
        private const val TAG = "UvcCameraSource"
        private const val USB_CLASS_VIDEO = 14

        /** libuvccamera 中 UVC_VS_FRAME_MJPEG 的值 */
        private const val MJPEG_FRAME_TYPE = 0x07

        /** libuvccamera 中 UVC_VS_FRAME_UNCOMPRESSED 的值（YUYV/NV12 等未压缩流，延迟更低） */
        private const val UNCOMPRESSED_FRAME_TYPE = 0x05
    }
}
