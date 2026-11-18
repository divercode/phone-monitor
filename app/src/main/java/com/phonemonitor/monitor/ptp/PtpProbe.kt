package com.phonemonitor.monitor.ptp

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import java.util.Locale

/**
 * Type-C PTP（相机 PC Remote 模式）协议诊断。
 *
 * 按 ISO 15740 + 索尼 SDIO 扩展走一遍最小握手，把每一步的响应码记下来：
 *   发现设备/接口 → 申请 USB 权限 → OpenSession → GetDeviceInfo
 *   → SDIO_CONNECT phase 1/2/3 → SDIO_GET_EXT_DEVICE_INFO → 读 0xD221
 *
 * 这一步用于回答"相机到底走没走 PTP、卡在哪一步"。实时取景（GET_OBJECT 0xFFFFC002）
 * 是后续 M4.2 的工作。
 */
object PtpProbe {

    private const val TAG = "PtpProbe"
    private const val SONY_VENDOR_ID = 0x054C

    private const val RESPONSE_OK = 0x2001

    private const val OP_GET_DEVICE_INFO = 0x1001
    private const val OP_OPEN_SESSION = 0x1002
    private const val OP_CLOSE_SESSION = 0x1003
    private const val OP_GET_DEVICE_PROP_VALUE = 0x1015

    private const val SDIO_CONNECT = 0x9201
    private const val SDIO_GET_EXT_DEVICE_INFO = 0x9202
    private const val PROP_LIVE_VIEW_STATUS = 0xD221

    data class Result(val lines: List<String>, val ok: Boolean)

    fun run(context: Context): Result {
        val lines = mutableListOf<String>()
        fun log(msg: String) {
            Log.i(TAG, msg)
            lines += msg
        }

        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = manager.deviceList.values.firstOrNull { it.vendorId == SONY_VENDOR_ID }
        if (device == null) {
            log("未找到索尼 USB 设备（vendor 0x054C）")
            log("请检查：数据线是否支持数据传输、相机 USB 模式是否为「电脑遥控(PC Remote)」")
            return Result(lines, false)
        }
        log("发现设备: ${device.deviceName}")

        if (!manager.hasPermission(device)) {
            log("请求 USB 权限…（请在系统弹窗中选择允许）")
            requestPermission(context, manager, device)
            var waited = 0
            while (!manager.hasPermission(device) && waited < 15000) {
                Thread.sleep(200)
                waited += 200
            }
            if (!manager.hasPermission(device)) {
                log("USB 权限被拒绝或超时")
                return Result(lines, false)
            }
        }
        log("USB 权限 OK")

        val connection = manager.openDevice(device)
            ?: return Result(lines.apply { log("openDevice 失败") }, false)

        try {
            val resolved = findInterface(device)
                ?: return Result(lines.apply { log("没有找到可用的 bulk 接口") }, false)
            val (intf, inEp, outEp) = resolved
            log(
                "接口 #${intf.id} class=${intf.interfaceClass} " +
                    "bulk IN=0x${inEp.address.toString(16)} OUT=0x${outEp.address.toString(16)}",
            )
            if (!connection.claimInterface(intf, true)) {
                log("claimInterface 失败（可能被其他 App 占用）")
                return Result(lines, false)
            }

            val transport = PtpTransport(connection, inEp, outEp)

            log("OpenSession…")
            val open = transport.transaction(OP_OPEN_SESSION, intArrayOf(1))
            log("OpenSession 响应=0x${hex(open.code)}")
            if (open.code != RESPONSE_OK) {
                log("OpenSession 失败：相机可能不在 PTP/PC Remote 模式，或已被其他程序占用")
                return Result(lines, false)
            }

            log("GetDeviceInfo…")
            val info = transport.transaction(OP_GET_DEVICE_INFO, expectData = true)
            log("GetDeviceInfo 响应=0x${hex(info.code)}，数据 ${info.data?.size ?: 0} 字节")

            log("索尼 SDIO 握手…")
            for (phase in 1..3) {
                val resp = transport.transaction(SDIO_CONNECT, intArrayOf(phase, 0, 0))
                log("SDIO_CONNECT phase$phase 响应=0x${hex(resp.code)}")
                if (resp.code != RESPONSE_OK) {
                    log("SDIO_CONNECT 在 phase$phase 失败")
                    return Result(lines, false)
                }
            }

            for (version in intArrayOf(0x012C, 0x00C8)) {
                val resp = transport.transaction(
                    SDIO_GET_EXT_DEVICE_INFO,
                    intArrayOf(version, 1),
                    expectData = true,
                )
                log(
                    "SDIO_GET_EXT_DEVICE_INFO(ver=0x${hex(version)}) " +
                        "响应=0x${hex(resp.code)} 数据 ${resp.data?.size ?: 0} 字节",
                )
                if (resp.code == RESPONSE_OK) break
            }

            val prop = transport.transaction(
                OP_GET_DEVICE_PROP_VALUE,
                intArrayOf(PROP_LIVE_VIEW_STATUS),
                expectData = true,
            )
            log(
                "GetDevicePropValue(0xD221 LIVE_VIEW_STATUS) 响应=0x${hex(prop.code)} " +
                    "数据=${prop.data?.joinToString(" ") { "%02X".format(it) } ?: "无"}",
            )

            runCatching { transport.transaction(OP_CLOSE_SESSION) }
            log("诊断完成：协议栈可用，下一步可接实时取景（M4.2）")
            return Result(lines, true)
        } catch (t: Throwable) {
            Log.e(TAG, "probe failed", t)
            log("异常：${t.javaClass.simpleName}: ${t.message}")
            return Result(lines, false)
        } finally {
            runCatching { connection.close() }
        }
    }

    // ---------------------------------------------------------------- usb

    private fun findInterface(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
        fun endpoints(intf: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            return if (inEp != null && outEp != null) inEp to outEp else null
        }

        val candidates = interfaces.mapNotNull { intf ->
            endpoints(intf)?.let { Triple(intf, it.first, it.second) }
        }
        return candidates.firstOrNull { it.first.interfaceClass == 6 }
            ?: candidates.firstOrNull()
    }

    private fun requestPermission(context: Context, manager: UsbManager, device: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        val pending = PendingIntent.getBroadcast(context, 0, intent, flags)
        manager.requestPermission(device, pending)
    }

    private fun hex(value: Int): String = String.format(Locale.US, "%04X", value and 0xFFFF)

    private const val ACTION_USB_PERMISSION = "com.phonemonitor.USB_PERMISSION"
}
