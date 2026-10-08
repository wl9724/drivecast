package org.drivecast.car.adb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

/** 车机作为 USB Host，通过手机的 ADB 接口（bulk 端点）收发数据。 */
class UsbTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : AdbTransport {

    override fun write(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val n = connection.bulkTransfer(epOut, data, off, data.size - off, WRITE_TIMEOUT_MS)
            if (n < 0) throw IOException("USB 写入失败，检查数据线")
            off += n
        }
    }

    override fun readFully(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            // 超时 0 = 一直等：用户可能正在手机上点授权
            val n = connection.bulkTransfer(epIn, data, off, data.size - off, 0)
            if (n < 0) throw IOException("USB 读取失败，手机可能已断开")
            off += n
        }
    }

    override fun close() {
        connection.releaseInterface(intf)
        connection.close()
    }

    companion object {
        private const val WRITE_TIMEOUT_MS = 5_000

        /** ADB 接口：class 0xFF / subclass 0x42 / protocol 0x01。 */
        fun findAdbInterface(device: UsbDevice): UsbInterface? =
            (0 until device.interfaceCount).map(device::getInterface).firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                    it.interfaceSubclass == 0x42 &&
                    it.interfaceProtocol == 0x01
            }

        fun open(usb: UsbManager, device: UsbDevice): UsbTransport {
            val intf = findAdbInterface(device) ?: throw IOException("手机没有开启 USB 调试")
            val bulk = (0 until intf.endpointCount).map(intf::getEndpoint)
                .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val epIn = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
            val epOut = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
            if (epIn == null || epOut == null) throw IOException("ADB 接口缺少 bulk 端点")
            val conn = usb.openDevice(device) ?: throw IOException("无法打开 USB 设备，检查 USB 权限")
            if (!conn.claimInterface(intf, true)) {
                conn.close()
                throw IOException("ADB 接口被占用")
            }
            return UsbTransport(conn, intf, epIn, epOut)
        }
    }
}
