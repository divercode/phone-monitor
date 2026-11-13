package com.phonemonitor.monitor.lut

import java.io.InputStream

/**
 * .cube 文件解析器。
 *
 * 支持：
 *  - TITLE / LUT_3D_SIZE / DOMAIN_MIN / DOMAIN_MAX / 注释
 *  - 标准 .cube 数据行：R G B（0..1）
 *
 * 不支持：LUT_1D_SIZE（1D LUT 直接报错）。
 */
object CubeParser {

    fun parse(input: InputStream): Lut3D {
        var size = 0
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        val values = ArrayList<Float>(33 * 33 * 33 * 3)

        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                when {
                    line.startsWith("TITLE", ignoreCase = true) -> Unit
                    line.startsWith("LUT_3D_SIZE", ignoreCase = true) -> {
                        size = line.substringAfter("LUT_3D_SIZE", "").trim().toIntOrNull()
                            ?: throw IllegalArgumentException("LUT_3D_SIZE 解析失败: $line")
                    }
                    line.startsWith("LUT_1D_SIZE", ignoreCase = true) -> {
                        throw IllegalArgumentException("暂不支持 1D LUT 文件")
                    }
                    line.startsWith("DOMAIN_MIN", ignoreCase = true) -> {
                        domainMin = parseTriple(line)
                    }
                    line.startsWith("DOMAIN_MAX", ignoreCase = true) -> {
                        domainMax = parseTriple(line)
                    }
                    else -> {
                        val parts = line.split(WHITESPACE)
                        if (parts.size < 3) continue
                        val r = parts[0].toFloatOrNull() ?: continue
                        val g = parts[1].toFloatOrNull() ?: continue
                        val b = parts[2].toFloatOrNull() ?: continue
                        values.add(r)
                        values.add(g)
                        values.add(b)
                    }
                }
            }
        }

        require(size > 0) { "缺少 LUT_3D_SIZE，可能不是有效的 .cube 文件" }
        val expected = size * size * size * 3
        require(values.size >= expected) {
            "LUT 数据行不足：期望 $expected 个浮点数，实际 ${values.size} 个"
        }

        // 归一化 DOMAIN 范围到 0..1
        val out = FloatArray(expected)
        for (i in 0 until expected) {
            val ch = i % 3
            val min = domainMin[ch]
            val range = (domainMax[ch] - min).takeIf { it > 1e-6f } ?: 1f
            out[i] = ((values[i] - min) / range).coerceIn(0f, 1f)
        }
        return Lut3D(size, out)
    }

    private fun parseTriple(line: String): FloatArray {
        val parts = line.split(WHITESPACE)
        require(parts.size >= 4) { "DOMAIN 行格式错误: $line" }
        return floatArrayOf(
            parts[1].toFloat(),
            parts[2].toFloat(),
            parts[3].toFloat(),
        )
    }

    private val WHITESPACE = Regex("\\s+")
}
