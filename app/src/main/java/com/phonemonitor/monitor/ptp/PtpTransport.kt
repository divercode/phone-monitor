package com.phonemonitor.monitor.ptp

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PTP 响应容器 */
class PtpResponse(val code: Int, val params: IntArray, val data: ByteArray?)

/**
 * 极简 PTP（ISO 15740）USB 传输层：只实现"命令 + 可选小数据"的同步事务，
 * 用于协议诊断。数据阶段超过单容器（64KB）的 GetObject 实时取景留给后续 M4.2。
 */
class PtpTransport(
    private val connection: UsbDeviceConnection,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
) {
    private var transactionId = 0

    fun transaction(
        code: Int,
        params: IntArray = IntArray(0),
        dataOut: ByteArray? = null,
        expectData: Boolean = false,
    ): PtpResponse {
        val tid = ++transactionId
        writeCommand(code, params, tid)
        if (dataOut != null) writeData(code, tid, dataOut)
        val data = if (expectData) readSingleDataContainer() else null
        val response = readContainer()
        check(response.type == TYPE_RESPONSE) {
            "期望响应容器，实际 type=0x${response.type.toString(16)} code=0x${response.code.toString(16)}"
        }
        return PtpResponse(response.code, response.params, data)
    }

    // ---------------------------------------------------------------- write

    private fun writeCommand(code: Int, params: IntArray, tid: Int) {
        val payload = ByteArray(params.size * 4)
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        params.forEach { bb.putInt(it) }
        writeContainer(TYPE_COMMAND, code, tid, payload)
    }

    private fun writeData(code: Int, tid: Int, data: ByteArray) {
        val buffer = ByteBuffer.allocate(12 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(12 + data.size)
        buffer.putShort(TYPE_DATA.toShort())
        buffer.putShort(code.toShort())
        buffer.putInt(tid)
        buffer.put(data)
        // 写完后若总长度是端点包大小的整数倍，需要 ZLP 结束数据阶段
        val bytes = buffer.array()
        writeRaw(bytes)
        val maxPacket = endpointOut.maxPacketSize
        if (maxPacket > 0 && bytes.size % maxPacket == 0) {
            connection.bulkTransfer(endpointOut, ByteArray(0), 0, 0, TIMEOUT_MS)
        }
    }

    private fun writeContainer(type: Int, code: Int, tid: Int, payload: ByteArray) {
        val buffer = ByteBuffer.allocate(12 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(12 + payload.size)
        buffer.putShort(type.toShort())
        buffer.putShort(code.toShort())
        buffer.putInt(tid)
        buffer.put(payload)
        writeRaw(buffer.array())
    }

    private fun writeRaw(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val chunk = minOf(16 * 1024, bytes.size - offset)
            val sent = connection.bulkTransfer(endpointOut, bytes, offset, chunk, TIMEOUT_MS)
            check(sent > 0) { "USB 写入失败（offset=$offset）" }
            offset += sent
        }
    }

    // ---------------------------------------------------------------- read

    private fun readSingleDataContainer(): ByteArray {
        val container = readContainer()
        check(container.type == TYPE_DATA) {
            "期望数据容器，实际 type=0x${container.type.toString(16)} code=0x${container.code.toString(16)}"
        }
        return container.payload
    }

    private fun readContainer(): Container {
        val header = readFully(12)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val length = bb.int
        val type = bb.short.toInt() and 0xFFFF
        val code = bb.short.toInt() and 0xFFFF
        val tid = bb.int
        check(length >= 12) { "PTP 容器长度非法: $length" }
        val payload = if (length > 12) readFully(length - 12) else ByteArray(0)
        val params = if ((type == TYPE_COMMAND || type == TYPE_RESPONSE) && payload.size >= 4) {
            val p = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            IntArray(payload.size / 4) { p.int }
        } else {
            IntArray(0)
        }
        return Container(type, code, tid, payload, params)
    }

    private fun readFully(size: Int): ByteArray {
        val out = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = connection.bulkTransfer(
                endpointIn, out, offset, size - offset, TIMEOUT_MS,
            )
            check(read > 0) { "USB 读取失败（需要 $size 字节，已读 $offset）" }
            offset += read
        }
        return out
    }

    private class Container(
        val type: Int,
        val code: Int,
        val transactionId: Int,
        val payload: ByteArray,
        val params: IntArray,
    )

    companion object {
        private const val TIMEOUT_MS = 3000
        private const val TYPE_COMMAND = 1
        private const val TYPE_DATA = 2
        private const val TYPE_RESPONSE = 3
        private const val TYPE_EVENT = 4
    }
}
