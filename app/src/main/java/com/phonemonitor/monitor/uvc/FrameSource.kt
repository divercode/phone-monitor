package com.phonemonitor.monitor.uvc

import android.graphics.SurfaceTexture

/**
 * 视频帧来源抽象。
 *
 * 当前只有 [UvcCameraSource]（USB HDMI 采集卡）一个实现；
 * 以后若加索尼 Wi-Fi 直连，只需要新增实现而不动渲染层。
 */
interface FrameSource {

    enum class Status { WAITING, CONNECTED, ERROR }

    interface Callback {
        fun onStatus(status: Status, message: String)
        fun onPreviewSize(width: Int, height: Int)

        /** 预览画面相对屏幕的旋转（0/1/2/3 个 90°，系统相机需要，UVC 恒为 0） */
        fun onOrientation(quarterTurns: Int) {}

        /** 是否需要左右镜像（系统相机预览可能有镜像，UVC 为 false） */
        fun onMirror(mirror: Boolean) {}
    }

    /** 开始监听设备接入（幂等） */
    fun start(callback: Callback)

    /** 渲染器创建好 SurfaceTexture 后注入，用于直接出画面 */
    fun attach(surfaceTexture: SurfaceTexture)

    /** 暂停：停止预览、关闭相机，但保留 USB 监听可再次 start */
    fun stop()

    /** 彻底释放：退出 Activity 时调用 */
    fun release() {
        // 默认实现为空，非 UVC 实现可不覆写
    }
}
