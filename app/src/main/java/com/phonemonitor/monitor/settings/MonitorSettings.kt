package com.phonemonitor.monitor.settings

import android.content.Context

/**
 * 监看参数持久化（SharedPreferences）。
 * 所有参数变更后由 Activity 通知渲染器 requestRender。
 */
class MonitorSettings(context: Context) {

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 是否启用 LUT */
    var lutEnabled: Boolean
        get() = prefs.getBoolean(KEY_LUT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LUT_ENABLED, value).apply()

    /** 峰值对焦 */
    var peaking: Boolean
        get() = prefs.getBoolean(KEY_PEAKING, false)
        set(value) = prefs.edit().putBoolean(KEY_PEAKING, value).apply()

    /** 峰值阈值 0..1（越大越只标最锐的边） */
    var peakingThreshold: Float
        get() = prefs.getFloat(KEY_PEAKING_THRESHOLD, 0.12f)
        set(value) = prefs.edit().putFloat(KEY_PEAKING_THRESHOLD, value).apply()

    /** 斑马纹 */
    var zebra: Boolean
        get() = prefs.getBoolean(KEY_ZEBRA, false)
        set(value) = prefs.edit().putBoolean(KEY_ZEBRA, value).apply()

    /** 斑马纹阈值 0..1（0.9 ≈ 90 IRE） */
    var zebraThreshold: Float
        get() = prefs.getFloat(KEY_ZEBRA_THRESHOLD, 0.85f)
        set(value) = prefs.edit().putFloat(KEY_ZEBRA_THRESHOLD, value).apply()

    /** 构图网格 */
    var grid: Boolean
        get() = prefs.getBoolean(KEY_GRID, false)
        set(value) = prefs.edit().putBoolean(KEY_GRID, value).apply()

    /** 安全框 */
    var safeArea: Boolean
        get() = prefs.getBoolean(KEY_SAFE_AREA, false)
        set(value) = prefs.edit().putBoolean(KEY_SAFE_AREA, value).apply()

    /** 变形宽银幕拉伸系数（1.0 = 不处理，1.33 / 2.0 = 变形镜头） */
    var desqueeze: Float
        get() = prefs.getFloat(KEY_DESQUEEZE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_DESQUEEZE, value).apply()

    /** 直方图显示 */
    var histogram: Boolean
        get() = prefs.getBoolean(KEY_HISTOGRAM, false)
        set(value) = prefs.edit().putBoolean(KEY_HISTOGRAM, value).apply()

    /** 低延迟模式：关闭垂直同步（可能轻微撕裂，但少约 1 帧显示延迟） */
    var lowLatency: Boolean
        get() = prefs.getBoolean(KEY_LOW_LATENCY, false)
        set(value) = prefs.edit().putBoolean(KEY_LOW_LATENCY, value).apply()

    companion object {
        private const val PREFS_NAME = "monitor_settings"
        private const val KEY_LUT_ENABLED = "lut_enabled"
        private const val KEY_PEAKING = "peaking"
        private const val KEY_PEAKING_THRESHOLD = "peaking_threshold"
        private const val KEY_ZEBRA = "zebra"
        private const val KEY_ZEBRA_THRESHOLD = "zebra_threshold"
        private const val KEY_GRID = "grid"
        private const val KEY_SAFE_AREA = "safe_area"
        private const val KEY_DESQUEEZE = "desqueeze"
        private const val KEY_HISTOGRAM = "histogram"
        private const val KEY_LOW_LATENCY = "low_latency"
    }
}
