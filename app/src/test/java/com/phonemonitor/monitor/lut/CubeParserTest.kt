package com.phonemonitor.monitor.lut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/** .cube 解析器的纯 JVM 单元测试（无需真机 / 模拟器） */
class CubeParserTest {

    private fun stream(text: String): ByteArrayInputStream =
        ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8))

    /** 2x2x2 恒等 LUT：数据按 R 变化最快排列 */
    private val identityCube = """
        # 2 阶恒等 LUT
        TITLE "identity2"
        LUT_3D_SIZE 2
        DOMAIN_MIN 0 0 0
        DOMAIN_MAX 1 1 1
        0 0 0
        1 0 0
        0 1 0
        1 1 0
        0 0 1
        1 0 1
        0 1 1
        1 1 1
    """.trimIndent()

    @Test
    fun parsesSizeAndValues() {
        val lut = CubeParser.parse(stream(identityCube))

        assertEquals(2, lut.size)
        // data 下标 = ((b*N + g)*N + r)*3
        // r 变化最快：第 2 个采样点 (r=1,g=0,b=0)
        assertEquals(1.0, lut.data[3].toDouble(), 1e-6)
        assertEquals(0.0, lut.data[4].toDouble(), 1e-6)
        assertEquals(0.0, lut.data[5].toDouble(), 1e-6)
        // 最后一个点 (1,1,1)
        val last = (2 * 2 * 2 - 1) * 3
        assertEquals(1.0, lut.data[last].toDouble(), 1e-6)
        assertEquals(1.0, lut.data[last + 1].toDouble(), 1e-6)
        assertEquals(1.0, lut.data[last + 2].toDouble(), 1e-6)
    }

    @Test
    fun normalizesToDomainRange() {
        // DOMAIN 0..1023 时，1023 应归一到 1.0
        val cube = """
            LUT_3D_SIZE 2
            DOMAIN_MIN 0 0 0
            DOMAIN_MAX 1023 1023 1023
            0 0 0
            1023 0 0
            0 1023 0
            1023 1023 0
            0 0 1023
            1023 0 1023
            0 1023 1023
            1023 1023 1023
        """.trimIndent()
        val lut = CubeParser.parse(stream(cube))
        assertEquals(0.0, lut.data[0].toDouble(), 1e-6)
        assertEquals(1.0, lut.data[3].toDouble(), 1e-6)
    }

    @Test
    fun rejects1dLut() {
        val cube = """
            LUT_1D_SIZE 2
            0 0 0
            1 1 1
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            CubeParser.parse(stream(cube))
        }
    }

    @Test
    fun rejectsMissingSize() {
        assertThrows(IllegalArgumentException::class.java) {
            CubeParser.parse(stream("0 0 0\n1 1 1\n"))
        }
    }

    @Test
    fun rejectsInsufficientData() {
        val cube = """
            LUT_3D_SIZE 2
            0 0 0
            1 1 1
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            CubeParser.parse(stream(cube))
        }
    }

    @Test
    fun ignoresCommentsAndBlankLines() {
        val cube = """
            # header
            TITLE "x"

            LUT_3D_SIZE 2
            # data follows
            0 0 0
            1 0 0
            0 1 0
            1 1 0
            0 0 1
            1 0 1
            0 1 1
            1 1 1
        """.trimIndent()
        assertEquals(2, CubeParser.parse(stream(cube)).size)
    }
}
