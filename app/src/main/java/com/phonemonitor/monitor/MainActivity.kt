package com.phonemonitor.monitor

import android.graphics.Color
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.phonemonitor.monitor.gl.MonitorRenderer
import com.phonemonitor.monitor.lut.Lut3D
import com.phonemonitor.monitor.lut.LutStore
import com.phonemonitor.monitor.settings.MonitorSettings
import com.phonemonitor.monitor.ui.HistogramView
import com.phonemonitor.monitor.uvc.FrameSource
import com.phonemonitor.monitor.uvc.UvcCameraSource
import java.util.Locale

/**
 * 主界面：全屏 UVC 监看 + LUT + 峰值/斑马/网格 + 直方图。
 * UI 全部代码创建，避免布局 XML 与渲染层耦合。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: MonitorRenderer
    private lateinit var lutStore: LutStore
    private lateinit var settings: MonitorSettings
    private lateinit var source: UvcCameraSource
    private lateinit var statusText: TextView
    private var fpsText: TextView? = null
    private lateinit var histogramView: HistogramView
    private lateinit var controls: LinearLayout

    @Volatile
    private var currentLut: Lut3D? = null

    private var sourceStarted = false

    /** Activity 已销毁后，USB/GL 线程迟到的回调不应再碰视图 */
    @Volatile
    private var destroyed = false

    private val sourceCallback = object : FrameSource.Callback {
        override fun onStatus(status: FrameSource.Status, message: String) {
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                renderer.sourceConnected = status == FrameSource.Status.CONNECTED
                statusText.text = message
                if (status != FrameSource.Status.CONNECTED) {
                    fpsText?.text = "--"
                }
                glView.requestRender()
            }
        }

        override fun onPreviewSize(width: Int, height: Int) {
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                renderer.requestedPreviewSize = width to height
                glView.requestRender()
            }
        }
    }

    private val lutPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            importLut(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        settings = MonitorSettings(this)
        lutStore = LutStore(this)
        currentLut = lutStore.selectedName?.let { lutStore.load(it) }

        source = UvcCameraSource(this)
        renderer = MonitorRenderer(
            settings = settings,
            lutProvider = { currentLut },
            onHistogram = { hist ->
                runOnUiThread {
                    if (!destroyed && settings.histogram) histogramView.setHistogram(hist)
                }
            },
            onSurfaceTextureReady = { st -> source.attach(st) },
            onFps = { fps ->
                runOnUiThread {
                    if (!destroyed) updateFps(fps)
                }
            },
        )

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            setOnClickListener { toggleControls() }
        }
        renderer.requestRender = { glView.requestRender() }

        setContentView(buildRoot())
        statusText.text = getString(R.string.status_waiting)
    }

    // ------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        destroyed = false
        glView.onResume()
        if (!sourceStarted) {
            source.start(sourceCallback)
            sourceStarted = true
        }
    }

    override fun onPause() {
        // 先停采集卡（会释放相机的预览 Surface），再暂停 GL 线程
        if (sourceStarted) {
            source.stop()
            sourceStarted = false
        }
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        source.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------- UI 构建

    private fun buildRoot(): FrameLayout {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        root.addView(
            glView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        controls.addView(
            statusText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        // 帧率显示（渲染线程每 30 帧回调一次）
        val fpsView = TextView(this).apply {
            text = "--"
            setTextColor(Color.argb(220, 200, 200, 200))
            textSize = 12f
            setPadding(dp(8), 0, dp(8), 0)
        }
        fpsText = fpsView
        controls.addView(fpsView)

        controls.addView(actionButton("LUT") { showLutMenu() })
        controls.addView(actionButton("设置") { showSettings() })
        controls.addView(actionButton("隐藏") { toggleControls() })

        root.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )

        histogramView = HistogramView(this).apply {
            visibility = if (settings.histogram) View.VISIBLE else View.GONE
        }
        val histLp = FrameLayout.LayoutParams(dp(220), dp(110), Gravity.BOTTOM or Gravity.END)
        histLp.setMargins(0, 0, dp(16), dp(16))
        root.addView(histogramView, histLp)

        return root
    }

    private fun toggleControls() {
        val visible = controls.visibility == View.VISIBLE
        controls.visibility = if (visible) View.GONE else View.VISIBLE
        histogramView.visibility =
            if (visible || !settings.histogram) View.GONE else View.VISIBLE
    }

    private fun actionButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        setText(text)
        setTextColor(Color.WHITE)
        textSize = 14f
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------- LUT

    private fun showLutMenu() {
        val names = lutStore.list()
        if (names.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("还没有 LUT")
                .setMessage("导入索尼官方 S-Log3→Rec.709 还原 LUT（.cube 文件）即可实时套用。")
                .setPositiveButton("导入 .cube") { _, _ ->
                    lutPicker.launch(arrayOf("*/*"))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val items = mutableListOf("不使用 LUT")
        items.addAll(names)
        val selected = lutStore.selectedName
        val checked = if (settings.lutEnabled && selected != null) {
            names.indexOf(selected) + 1
        } else {
            0
        }

        AlertDialog.Builder(this)
            .setTitle("选择 LUT")
            .setSingleChoiceItems(items.toTypedArray(), checked) { dialog, which ->
                if (which == 0) {
                    settings.lutEnabled = false
                    currentLut = null
                } else {
                    val name = names[which - 1]
                    val lut = lutStore.load(name)
                    if (lut != null) {
                        lutStore.selectedName = name
                        currentLut = lut
                        settings.lutEnabled = true
                        toast("已套用：$name")
                    } else {
                        toast("LUT 解析失败")
                    }
                }
                glView.requestRender()
                dialog.dismiss()
            }
            .setNegativeButton("导入新文件") { _, _ ->
                lutPicker.launch(arrayOf("*/*"))
            }
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun importLut(uri: Uri) {
        Thread {
            try {
                val name = lutStore.import(uri, queryDisplayName(uri))
                val lut = lutStore.load(name)
                runOnUiThread {
                    // 异步导入期间用户可能已退出，避免对已销毁的 Activity 弹窗
                    if (destroyed) return@runOnUiThread
                    if (lut == null) {
                        toast("解析失败：不是有效的 .cube 文件")
                        return@runOnUiThread
                    }
                    lutStore.selectedName = name
                    currentLut = lut
                    settings.lutEnabled = true
                    glView.requestRender()
                    toast("已导入并套用：$name")
                    showLutMenu()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    if (!destroyed) toast("导入失败：${t.message}")
                }
            }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        cursor.getString(idx)?.let { return it }
                    }
                }
            }
        return uri.lastPathSegment ?: "lut.cube"
    }

    // ------------------------------------------------------------- 设置面板

    private fun showSettings() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }

        addCheck(container, "峰值对焦", settings.peaking) { checked ->
            settings.peaking = checked
            glView.requestRender()
        }
        addSeek(container, "峰值阈值（越小越灵敏）", 100, (settings.peakingThreshold * 100).toInt()) {
            settings.peakingThreshold = it / 100f
            glView.requestRender()
        }

        addCheck(container, "斑马纹", settings.zebra) { checked ->
            settings.zebra = checked
            glView.requestRender()
        }
        // 阈值范围 50%~100% IRE
        addSeek(container, "斑马阈值 (IRE %)", 100, (settings.zebraThreshold * 100).toInt()) {
            settings.zebraThreshold = it.coerceIn(50, 100) / 100f
            glView.requestRender()
        }

        addCheck(container, "构图网格", settings.grid) { checked ->
            settings.grid = checked
            glView.requestRender()
        }
        addCheck(container, "安全框", settings.safeArea) { checked ->
            settings.safeArea = checked
            glView.requestRender()
        }

        val desqueezeOptions = floatArrayOf(1.0f, 1.33f, 2.0f)
        val currentIdx = desqueezeOptions.indexOfFirst { it == settings.desqueeze }
            .takeIf { it >= 0 } ?: 0
        addSeek(container, "变形宽银幕拉伸 (×1.0 → ×2.0)", 2, currentIdx) {
            settings.desqueeze = desqueezeOptions[it.coerceIn(0, 2)]
            glView.requestRender()
        }

        addCheck(container, "显示直方图", settings.histogram) { checked ->
            settings.histogram = checked
            histogramView.visibility = if (checked) View.VISIBLE else View.GONE
            glView.requestRender()
        }

        addCheck(container, "低延迟模式（关闭垂直同步，可能撕裂）", settings.lowLatency) { checked ->
            settings.lowLatency = checked
            glView.requestRender()
        }

        val scroll = ScrollView(this).apply { addView(container) }
        AlertDialog.Builder(this)
            .setTitle("监看设置")
            .setView(scroll)
            .setPositiveButton("完成", null)
            .show()
    }

    private fun addCheck(
        parent: LinearLayout,
        label: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        val check = CheckBox(this).apply {
            text = label
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        parent.addView(check)
    }

    private fun addSeek(
        parent: LinearLayout,
        label: String,
        max: Int,
        initial: Int,
        onChange: (Int) -> Unit,
    ) {
        val text = TextView(this).apply {
            this.text = label
            textSize = 13f
        }
        val seek = SeekBar(this).apply {
            this.max = max
            progress = initial.coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) onChange(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        parent.addView(text)
        parent.addView(seek)
    }

    // ------------------------------------------------------------- 杂项

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun updateFps(fps: Float) {
        fpsText?.text = String.format(Locale.US, "%.0f fps", fps)
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
