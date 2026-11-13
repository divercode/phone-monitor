package com.phonemonitor.monitor.lut

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * LUT 仓库：管理应用私有目录里的 .cube 文件，并记录当前选中的 LUT。
 */
class LutStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dir: File = File(context.filesDir, "luts").apply { mkdirs() }

    /** 已导入的 LUT 名称（不含 .cube 后缀），按字母序 */
    fun list(): List<String> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".cube", ignoreCase = true) }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    var selectedName: String?
        get() = prefs.getString(KEY_SELECTED_LUT, null)
        set(value) {
            prefs.edit().putString(KEY_SELECTED_LUT, value).apply()
        }

    /** 从 SAF Uri 拷贝 .cube 到私有目录；返回去重后的名称 */
    fun import(uri: Uri, displayName: String): String {
        val base = sanitize(displayName)
        var name = base
        var target = File(dir, "$name.cube")
        var n = 2
        while (target.exists()) {
            name = "${base}_$n"
            target = File(dir, "$name.cube")
            n++
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("无法读取所选文件")
        return name
    }

    fun load(name: String): Lut3D? = runCatching {
        File(dir, "$name.cube").inputStream().use { CubeParser.parse(it) }
    }.getOrNull()

    private fun sanitize(raw: String): String {
        var name = raw.trim()
        if (name.endsWith(".cube", ignoreCase = true)) {
            name = name.dropLast(5)
        }
        name = name.replace(Regex("[^A-Za-z0-9_\\-\\u4e00-\\u9fa5]"), "_").trim('_')
        return name.ifBlank { "lut_${System.currentTimeMillis()}" }
    }

    companion object {
        private const val PREFS_NAME = "monitor_settings"
        private const val KEY_SELECTED_LUT = "selected_lut"
    }
}
