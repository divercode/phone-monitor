package com.phonemonitor.monitor.lut

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * 3D LUT 数据（来自 .cube 文件）。
 *
 * @param size 每维采样数（.cube 的 LUT_3D_SIZE，常见 17 / 33 / 64）
 * @param data 按 .cube 规范顺序存储：R 变化最快，其次 G，最后 B。
 *             长度 = size^3 * 3，取值已归一化到 0..1。
 */
class Lut3D(val size: Int, val data: FloatArray) {

    init {
        require(size >= 2) { "LUT 尺寸非法: $size" }
        val expected = size * size * size * 3
        require(data.size >= expected) { "LUT 数据长度 ${data.size} < 期望 $expected" }
    }

    companion object {
        /** 把 size 个 z 切片排成 tiles x tiles 的网格，每边 tile 数 */
        fun tilesPerRow(size: Int): Int = ceil(sqrt(size.toDouble())).toInt()

        /**
         * 2D 图集边长（像素）。
         * 每个切片占 (size+2)^2 像素：内容 size x size，四周各留 1px 边距，
         * 边距复制边缘值，保证 GPU 双线性采样在切片边界不串色。
         */
        fun atlasDimension(size: Int): Int = tilesPerRow(size) * (size + 2)
    }

    /** 打包成 RGBA8 图集，供 GL 上传为 2D 纹理（GL_RGBA / GL_UNSIGNED_BYTE） */
    fun packAtlas(): LutAtlas {
        val tiles = tilesPerRow(size)
        val stride = size + 2
        val dim = tiles * stride
        val pixels = ByteArray(dim * dim * 4)

        fun comp(x: Int, y: Int, z: Int): FloatArray {
            val cx = x.coerceIn(0, size - 1)
            val cy = y.coerceIn(0, size - 1)
            val cz = z.coerceIn(0, size - 1)
            val i = ((cz * size + cy) * size + cx) * 3
            return floatArrayOf(data[i], data[i + 1], data[i + 2])
        }

        for (z in 0 until size) {
            val tileX = z % tiles
            val tileY = z / tiles
            val originX = tileX * stride
            val originY = tileY * stride

            for (py in -1..size) {
                for (px in -1..size) {
                    val c = comp(px, py, z)
                    val tx = originX + 1 + px
                    val ty = originY + 1 + py
                    val offset = (ty * dim + tx) * 4
                    pixels[offset] = toByte(c[0])
                    pixels[offset + 1] = toByte(c[1])
                    pixels[offset + 2] = toByte(c[2])
                    pixels[offset + 3] = 0xFF.toByte()
                }
            }
        }
        return LutAtlas(
            width = dim,
            height = dim,
            pixels = pixels,
            lutSize = size,
            tilesPerRow = tiles,
        )
    }

    private fun toByte(v: Float): Byte =
        (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
}

/** 打包后的 2D LUT 图集 */
class LutAtlas(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
    val lutSize: Int,
    val tilesPerRow: Int,
)
