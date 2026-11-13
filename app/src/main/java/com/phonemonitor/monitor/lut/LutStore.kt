package com.phonemonitor.monitor.lut

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * LUT 仓库：分两类
 *  - **内置**：APK 资源目录 `assets/luts` 下的 `.cube` 文件，只读，UI 上不允许删除
 *  - **用户导入**：应用私有目录 `filesDir/luts`，可导入、可删除
 */
class LutStore(private val context: Context) {

    /** 一条 LUT 记录（内置 / 用户导入） */
    data class Entry(val name: String, val builtIn: Boolean)

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val userDir: File = File(context.filesDir, "luts").apply { mkdirs() }

    /** 全部 LUT：内置在前、用户导入在后，各自按名称排序 */
    fun list(): List<Entry> {
        val builtIn = builtInNames().map { Entry(it, true) }
        val user = userNames().map { Entry(it, false) }
        return builtIn + user
    }

    var selectedName: String?
        get() = prefs.getString(KEY_SELECTED_LUT, null)
        set(value) {
            prefs.edit().putString(KEY_SELECTED_LUT, value).apply()
        }

    fun isBuiltIn(name: String): Boolean = name in builtInNames()

    /** 读取 LUT；不存在或解析失败返回 null */
    fun load(name: String): Lut3D? = runCatching {
        val stream = if (isBuiltIn(name)) {
            context.assets.open("$ASSET_DIR/$name.cube")
        } else {
            File(userDir, "$name.cube").inputStream()
        }
        stream.use { CubeParser.parse(it) }
    }.getOrNull()

    /** 从 SAF Uri 导入为「用户 LUT」，返回去重后的名称；不允许与内置同名 */
    fun import(uri: Uri, displayName: String): String {
        val base = sanitize(displayName)
        var name = base
        var n = 2
        while (isBuiltIn(name) || File(userDir, "$name.cube").exists()) {
            name = "${base}_$n"
            n++
        }
        val target = File(userDir, "$name.cube")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("无法读取所选文件")
        return name
    }

    /**
     * 删除用户导入的 LUT。
     * @return true 删除成功；false 表示是内置 LUT（禁止删除）或文件不存在
     */
    fun deleteUserLut(name: String): Boolean {
        if (isBuiltIn(name)) return false
        val file = File(userDir, "$name.cube")
        if (!file.exists()) return false
        return file.delete()
    }

    // ---------------------------------------------------------------- internals

    private fun builtInNames(): List<String> = runCatching {
        context.assets.list(ASSET_DIR)
            ?.filter { it.endsWith(".cube", ignoreCase = true) }
            ?.map { it.removeSuffix(".cube") }
            ?.sorted()
            ?: emptyList()
    }.getOrDefault(emptyList())

    private fun userNames(): List<String> =
        userDir.listFiles { f -> f.isFile && f.name.endsWith(".cube", ignoreCase = true) }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

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
        private const val ASSET_DIR = "luts"
    }
}
