package com.phonemonitor.monitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.phonemonitor.monitor.BuildConfig
import com.phonemonitor.monitor.camera.SystemCameraSource
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
 * UI 全部代码构建，采用「悬浮 HUD + 快捷开关条」布局。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: MonitorRenderer
    private lateinit var lutStore: LutStore
    private lateinit var settings: MonitorSettings
    private lateinit var source: FrameSource
    private var latestSurfaceTexture: SurfaceTexture? = null
    private var resumed = false

    private lateinit var topPanel: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private var fpsText: TextView? = null
    private lateinit var histogramView: HistogramView

    private lateinit var peakPill: TextView
    private lateinit var zebraPill: TextView
    private lateinit var gridPill: TextView
    private lateinit var histPill: TextView
    private lateinit var latencyPill: TextView

    @Volatile
    private var currentLut: Lut3D? = null

    private var sourceStarted = false

    @Volatile
    private var destroyed = false

    private val sourceCallback = object : FrameSource.Callback {
        override fun onStatus(status: FrameSource.Status, message: String) {
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                renderer.sourceConnected = status == FrameSource.Status.CONNECTED
                statusText.text = message
                statusDot.backgroundTintList = ColorStateList.valueOf(
                    color(
                        when (status) {
                            FrameSource.Status.CONNECTED -> R.color.status_ok
                            FrameSource.Status.WAITING -> R.color.status_wait
                            FrameSource.Status.ERROR -> R.color.status_err
                        },
                    ),
                )
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

        override fun onOrientation(quarterTurns: Int) {
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                renderer.quarterTurns = quarterTurns
                glView.requestRender()
            }
        }
    }

    private val lutPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            importLut(uri)
        }

    /** 切换到系统相机前请求 CAMERA 权限 */
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                settings.useSystemCamera = true
                rebuildSource()
            } else {
                settings.useSystemCamera = false
                toast("未授予相机权限，已切回 UVC 采集卡")
                rebuildSource()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        settings = MonitorSettings(this)
        lutStore = LutStore(this)
        currentLut = lutStore.selectedName?.let { lutStore.load(it) }

        source = createSource()
        renderer = MonitorRenderer(
            settings = settings,
            lutProvider = { currentLut },
            onHistogram = { hist ->
                runOnUiThread {
                    if (!destroyed && settings.histogram) histogramView.setHistogram(hist)
                }
            },
            onSurfaceTextureReady = { st ->
                latestSurfaceTexture = st
                source.attach(st)
            },
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
            setOnClickListener { toggleHud() }
        }
        renderer.requestRender = { glView.requestRender() }

        setContentView(buildRoot())
        statusText.text = getString(R.string.status_waiting)
        refreshPills()
    }

    // ------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        destroyed = false
        resumed = true
        glView.onResume()
        if (!sourceStarted) {
            source.start(sourceCallback)
            sourceStarted = true
        }
    }

    override fun onPause() {
        resumed = false
        if (sourceStarted) {
            source.stop()
            sourceStarted = false
        }
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        resumed = false
        source.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------- 输入源

    private fun createSource(): FrameSource =
        if (settings.useSystemCamera) {
            SystemCameraSource(applicationContext)
        } else {
            UvcCameraSource(this)
        }

    /** 输入源切换（UVC 采集卡 ↔ 系统相机）：停旧源、建新源、重新挂到同一个 SurfaceTexture */
    private fun rebuildSource() {
        if (sourceStarted) {
            runCatching { source.stop() }
            sourceStarted = false
        }
        runCatching { source.release() }
        renderer.quarterTurns = 0
        source = createSource()
        latestSurfaceTexture?.let { source.attach(it) }
        if (resumed && !destroyed) {
            source.start(sourceCallback)
            sourceStarted = true
        }
        glView.requestRender()
    }

    // ------------------------------------------------------------- UI 构建

    private fun buildRoot(): FrameLayout {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(glView, matchParent())

        // ---- 顶部 HUD：状态灯 + 状态文字 + 帧率 + 图标按钮
        topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = drawable(R.drawable.bg_hud_panel)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        statusDot = View(this).apply {
            background = drawable(R.drawable.status_dot)
            backgroundTintList = ColorStateList.valueOf(color(R.color.status_wait))
        }
        topPanel.addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)))

        statusText = TextView(this).apply {
            setTextColor(color(R.color.text_primary))
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        topPanel.addView(
            statusText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(8) },
        )

        val fps = TextView(this).apply {
            text = "--"
            setTextColor(color(R.color.text_secondary))
            textSize = 11f
            background = drawable(R.drawable.bg_chip)
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
        fpsText = fps
        topPanel.addView(
            fps,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) },
        )

        topPanel.addView(
            iconButton(R.drawable.ic_lut, getString(R.string.btn_lut)) { showLutMenu() },
            wrapLp(marginStart = dp(8)),
        )
        topPanel.addView(
            iconButton(R.drawable.ic_settings, getString(R.string.btn_settings)) { showSettings() },
            wrapLp(marginStart = dp(6)),
        )
        topPanel.addView(
            iconButton(R.drawable.ic_visibility, getString(R.string.btn_hide)) { toggleHud() },
            wrapLp(marginStart = dp(6)),
        )

        root.addView(
            topPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP
                setMargins(dp(12), dp(12), dp(12), 0)
            },
        )

        // ---- 底部快捷开关条
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = drawable(R.drawable.bg_hud_panel)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        peakPill = togglePill(R.drawable.ic_peak, R.string.toggle_peak, settings.peaking) {
            settings.peaking = it
            glView.requestRender()
        }
        zebraPill = togglePill(R.drawable.ic_zebra, R.string.toggle_zebra, settings.zebra) {
            settings.zebra = it
            glView.requestRender()
        }
        gridPill = togglePill(R.drawable.ic_grid, R.string.toggle_grid, settings.grid) {
            settings.grid = it
            glView.requestRender()
        }
        histPill = togglePill(R.drawable.ic_histogram, R.string.toggle_hist, settings.histogram) {
            settings.histogram = it
            refreshHistogramVisibility()
            glView.requestRender()
        }
        latencyPill = togglePill(R.drawable.ic_bolt, R.string.toggle_low_latency, settings.lowLatency) {
            settings.lowLatency = it
            glView.requestRender()
        }

        root.addView(
            bottomBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, dp(16))
            },
        )

        // ---- 直方图浮层
        histogramView = HistogramView(this).apply {
            background = drawable(R.drawable.bg_hud_panel)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        root.addView(
            histogramView,
            FrameLayout.LayoutParams(dp(240), dp(110)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, dp(16), dp(96))
            },
        )

        return root
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    )

    private fun wrapLp(marginStart: Int = 0, marginEnd: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            this.marginStart = marginStart
            this.marginEnd = marginEnd
        }

    private fun iconButton(iconRes: Int, desc: String, onClick: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(iconRes)
            contentDescription = desc
            setColorFilter(color(R.color.text_primary))
            background = drawable(R.drawable.bg_pill)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setOnClickListener { onClick() }
        }

    private fun pill(iconRes: Int, labelRes: Int): TextView = TextView(this).apply {
        setText(labelRes)
        textSize = 11f
        setTextColor(color(R.color.text_secondary))
        setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
        compoundDrawablePadding = dp(5)
        setCompoundDrawableTintList(ColorStateList.valueOf(color(R.color.text_secondary)))
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = drawable(R.drawable.bg_pill)
        gravity = Gravity.CENTER
    }

    private fun togglePill(
        iconRes: Int,
        labelRes: Int,
        initial: Boolean,
        onToggle: (Boolean) -> Unit,
    ): TextView {
        val view = pill(iconRes, labelRes)
        view.isSelected = initial
        applyPillStyle(view)
        view.setOnClickListener {
            val next = !view.isSelected
            view.isSelected = next
            applyPillStyle(view)
            onToggle(next)
        }
        bottomBar.addView(view, wrapLp(marginStart = dp(3), marginEnd = dp(3)))
        return view
    }

    private fun applyPillStyle(view: TextView) {
        val active = view.isSelected
        view.background = drawable(
            if (active) R.drawable.bg_pill_active else R.drawable.bg_pill,
        )
        val tint = color(if (active) R.color.accent else R.color.text_secondary)
        view.setTextColor(tint)
        view.setCompoundDrawableTintList(ColorStateList.valueOf(tint))
    }

    private fun refreshPills() {
        peakPill.isSelected = settings.peaking
        zebraPill.isSelected = settings.zebra
        gridPill.isSelected = settings.grid
        histPill.isSelected = settings.histogram
        latencyPill.isSelected = settings.lowLatency
        listOf(peakPill, zebraPill, gridPill, histPill, latencyPill).forEach { applyPillStyle(it) }
        refreshHistogramVisibility()
    }

    private fun refreshHistogramVisibility() {
        val show = settings.histogram && topPanel.visibility == View.VISIBLE
        histogramView.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun toggleHud() {
        val show = topPanel.visibility == View.GONE
        topPanel.visibility = if (show) View.VISIBLE else View.GONE
        bottomBar.visibility = if (show) View.VISIBLE else View.GONE
        refreshHistogramVisibility()
    }

    private fun updateFps(fps: Float) {
        fpsText?.text = String.format(Locale.US, "%.0f fps", fps)
    }

    // ------------------------------------------------------------- LUT

    private fun showLutMenu() {
        val entries = lutStore.list()
        if (entries.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("还没有 LUT")
                .setMessage(
                    "默认不内置第三方版权 LUT。\n\n" +
                        "• 从索尼官网下载官方 .cube 后，用「导入」使用（可删除）\n" +
                        "• 若想把官方 LUT 作为「内置、不可删除」，把文件放进\n" +
                        "  app/src/main/assets/luts/ 后重新构建",
                )
                .setPositiveButton("打开索尼官网") { _, _ -> openSonyLutPage() }
                .setNeutralButton("导入 .cube") { _, _ ->
                    lutPicker.launch(arrayOf("*/*"))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val items = mutableListOf("从索尼官网获取 LUT…", "不使用 LUT")
        items.addAll(
            entries.map {
                if (it.builtIn) "${lutStore.displayName(it.name)}（内置）" else it.name
            },
        )
        val selected = lutStore.selectedName
        val checked = if (settings.lutEnabled && selected != null) {
            entries.indexOfFirst { it.name == selected }.let { if (it >= 0) it + 2 else 1 }
        } else {
            1
        }

        AlertDialog.Builder(this)
            .setTitle("选择 LUT（内置不可删除）")
            .setSingleChoiceItems(items.toTypedArray(), checked) { dialog, which ->
                when {
                    which == 0 -> openSonyLutPage()
                    which == 1 -> {
                        settings.lutEnabled = false
                        currentLut = null
                    }
                    else -> {
                        val entry = entries[which - 2]
                        val lut = lutStore.load(entry.name)
                        if (lut != null) {
                            lutStore.selectedName = entry.name
                            currentLut = lut
                            settings.lutEnabled = true
                            val shown = lutStore.displayName(entry.name)
                            toast("已套用：$shown${if (entry.builtIn) "（内置）" else ""}")
                        } else {
                            toast("LUT 解析失败")
                        }
                    }
                }
                glView.requestRender()
                dialog.dismiss()
            }
            .setNeutralButton("删除…") { _, _ -> showDeleteLutDialog() }
            .setNegativeButton("导入新文件") { _, _ ->
                lutPicker.launch(arrayOf("*/*"))
            }
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 打开索尼官方 LUT 页面（由用户自行下载，本项目不打包索尼版权文件） */
    private fun openSonyLutPage() {
        val url = "https://www.sony.co.uk/alphauniverse/lut-gallery"
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            toast("没有可用浏览器，请手动访问 $url")
        }
    }

    /** 只允许删除用户导入的 LUT；内置 LUT 不出现在删除列表里 */
    private fun showDeleteLutDialog() {
        val userLuts = lutStore.list().filter { !it.builtIn }
        if (userLuts.isEmpty()) {
            toast("没有可删除的自导入 LUT（内置 LUT 不可删除）")
            return
        }
        val names = userLuts.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("删除自导入的 LUT")
            .setItems(names) { _, which ->
                val name = names[which]
                AlertDialog.Builder(this)
                    .setTitle("删除 $name ？")
                    .setMessage("删除后不可恢复。内置 LUT 不允许删除。")
                    .setPositiveButton("删除") { _, _ ->
                        val ok = lutStore.deleteUserLut(name)
                        if (ok) {
                            if (lutStore.selectedName == name) {
                                lutStore.selectedName = null
                                currentLut = null
                                settings.lutEnabled = false
                            }
                            glView.requestRender()
                            toast("已删除：$name")
                            showLutMenu()
                        } else {
                            toast("删除失败（内置 LUT 不可删除）")
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importLut(uri: Uri) {
        Thread {
            try {
                val name = lutStore.import(uri, queryDisplayName(uri))
                val lut = lutStore.load(name)
                runOnUiThread {
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
            setPadding(dp(20), 0, dp(20), dp(8))
        }

        container.addView(sectionTitle(R.string.section_tools))

        addSwitch(container, R.string.switch_peak, settings.peaking) {
            settings.peaking = it
            refreshPills()
            glView.requestRender()
        }
        addSwitch(container, R.string.switch_zebra, settings.zebra) {
            settings.zebra = it
            refreshPills()
            glView.requestRender()
        }
        addSwitch(container, R.string.switch_grid, settings.grid) {
            settings.grid = it
            refreshPills()
            glView.requestRender()
        }
        addSwitch(container, R.string.switch_safe_area, settings.safeArea) {
            settings.safeArea = it
            glView.requestRender()
        }
        addSwitch(container, R.string.switch_histogram, settings.histogram) {
            settings.histogram = it
            refreshPills()
            glView.requestRender()
        }
        addSwitch(container, R.string.switch_low_latency, settings.lowLatency) {
            settings.lowLatency = it
            refreshPills()
            glView.requestRender()
        }

        addSwitch(container, R.string.switch_system_camera, settings.useSystemCamera) { checked ->
            if (checked && ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                settings.useSystemCamera = true
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                settings.useSystemCamera = checked
                rebuildSource()
            }
        }

        container.addView(sectionTitle(R.string.section_params))

        addSlider(
            container,
            R.string.slider_peak_threshold,
            100,
            (settings.peakingThreshold * 100).toInt(),
            { "$it" },
        ) {
            settings.peakingThreshold = it / 100f
            glView.requestRender()
        }

        addSlider(
            container,
            R.string.slider_zebra_threshold,
            100,
            (settings.zebraThreshold * 100).toInt(),
            { "$it%" },
        ) {
            settings.zebraThreshold = it.coerceIn(50, 100) / 100f
            glView.requestRender()
        }

        val desqueezeOptions = floatArrayOf(1.0f, 1.33f, 2.0f)
        val desqueezeLabels = arrayOf("×1.0", "×1.33", "×2.0")
        val currentIdx = desqueezeOptions.indexOfFirst { it == settings.desqueeze }
            .takeIf { it >= 0 } ?: 0
        addSlider(
            container,
            R.string.slider_desqueeze,
            2,
            currentIdx,
            { desqueezeLabels[it.coerceIn(0, 2)] },
        ) {
            settings.desqueeze = desqueezeOptions[it.coerceIn(0, 2)]
            glView.requestRender()
        }

        // 版本号（发版规则要求每次更新递增，见 docs/RELEASING.md）
        container.addView(
            TextView(this).apply {
                text = "版本 v${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）"
                setTextColor(color(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(20), 0, 0)
            },
        )

        val scroll = ScrollView(this).apply { addView(container) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(scroll)
            .setPositiveButton(R.string.settings_done, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(drawable(R.drawable.bg_hud_panel))
        }
        dialog.show()
    }

    private fun sectionTitle(res: Int): TextView = TextView(this).apply {
        setText(res)
        setTextColor(color(R.color.accent))
        textSize = 12f
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun addSwitch(
        parent: LinearLayout,
        labelRes: Int,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        val switch = SwitchCompat(this).apply {
            setText(labelRes)
            setTextColor(color(R.color.text_primary))
            textSize = 14f
            isChecked = initial
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(color(R.color.accent), 0xFF8A8A8A.toInt()),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0x66FF5252, 0x33FFFFFF),
            )
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        parent.addView(
            switch,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6); bottomMargin = dp(6) },
        )
    }

    private fun addSlider(
        parent: LinearLayout,
        labelRes: Int,
        max: Int,
        initial: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            setText(labelRes)
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
        }
        val value = TextView(this).apply {
            text = format(initial.coerceIn(0, max))
            textSize = 13f
            setTextColor(color(R.color.text_primary))
        }
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(value)
        parent.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        val seek = SeekBar(this).apply {
            this.max = max
            progress = initial.coerceIn(0, max)
            progressTintList = ColorStateList.valueOf(color(R.color.accent))
            thumbTintList = ColorStateList.valueOf(color(R.color.accent))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                    value.text = format(p)
                    if (fromUser) onChange(p)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
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

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)

    private fun drawable(res: Int) = ContextCompat.getDrawable(this, res)

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
