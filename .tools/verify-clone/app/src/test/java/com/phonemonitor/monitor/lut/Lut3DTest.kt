package com.phonemonitor.monitor.lut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** LUT 图集打包的纯 JVM 单元测试（无需真机 / 模拟器） */
class Lut3DTest {

    /** 2 阶恒等 LUT：R 变化最快 */
    private val identity2 = Lut3D(
        2,
        floatArrayOf(
            0f, 0f, 0f,   1f, 0f, 0f,
            0f, 1f, 0f,   1f, 1f, 0f,
            0f, 0f, 1f,   1f, 0f, 1f,
            0f, 1f, 1f,   1f, 1f, 1f,
        ),
    )

    @Test
    fun tilesAndDimensions() {
        assertEquals(2, Lut3D.tilesPerRow(2))
        assertEquals(5, Lut3D.tilesPerRow(17))
        assertEquals(2 * (2 + 2), Lut3D.atlasDimension(2))
        assertEquals(5 * (17 + 2), Lut3D.atlasDimension(17))
    }

    @Test
    fun atlasSizeMatchesDeclared() {
        val atlas = identity2.packAtlas()
        assertEquals(atlas.width, atlas.height)
        assertEquals(atlas.width * atlas.height * 4, atlas.pixels.size)
        assertEquals(2, atlas.lutSize)
        assertEquals(2, atlas.tilesPerRow)
    }

    @Test
    fun edgePaddingReplicatesContent() {
        val atlas = identity2.packAtlas()
        val dim = atlas.width

        fun px(x: Int, y: Int): Triple<Int, Int, Int> {
            val o = (y * dim + x) * 4
            return Triple(
                atlas.pixels[o].toInt() and 0xFF,
                atlas.pixels[o + 1].toInt() and 0xFF,
                atlas.pixels[o + 2].toInt() and 0xFF,
            )
        }

        // 第一个切片（z=0）位于 tile(0,0)，内容从 (1,1) 开始
        // 左上角边距 (0,0) 应复制内容角点 (1,1)
        assertEquals(px(1, 1), px(0, 0))
        // 上边距整行复制首行内容
        for (x in 1..2) {
            assertEquals(px(x, 1), px(x, 0))
        }
        // 左列边距复制首列内容
        for (y in 1..2) {
            assertEquals(px(1, y), px(0, y))
        }
    }

    @Test
    fun identityCornerValuesArePreserved() {
        val atlas = identity2.packAtlas()
        val dim = atlas.width

        fun px(x: Int, y: Int): Triple<Int, Int, Int> {
            val o = (y * dim + x) * 4
            return Triple(
                atlas.pixels[o].toInt() and 0xFF,
                atlas.pixels[o + 1].toInt() and 0xFF,
                atlas.pixels[o + 2].toInt() and 0xFF,
            )
        }

        // z=0 切片左上角 = 黑 (0,0,0)，右下角 = (1,1,0)
        assertEquals(Triple(0, 0, 0), px(1, 1))
        assertEquals(Triple(255, 255, 0), px(2, 2))
        // z=1 切片在 tile(1,0)，origin x = 4
        assertEquals(Triple(0, 0, 255), px(5, 1))
        assertEquals(Triple(255, 255, 255), px(6, 2))
    }

    @Test
    fun rejectsInvalidSize() {
        assertThrows(IllegalArgumentException::class.java) {
            Lut3D(1, floatArrayOf(0f, 0f, 0f))
        }
    }

    @Test
    fun rejectsShortData() {
        assertThrows(IllegalArgumentException::class.java) {
            Lut3D(2, floatArrayOf(0f, 0f, 0f))
        }
    }
}
