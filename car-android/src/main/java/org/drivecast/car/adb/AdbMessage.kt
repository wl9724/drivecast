package org.drivecast.car.adb

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ADB 传输层消息。格式见 AOSP packages/modules/adb/protocol.txt。 */
class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = ByteArray(0),
) {
    fun header(): ByteArray = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(command)
        .putInt(arg0)
        .putInt(arg1)
        .putInt(payload.size)
        .putInt(checksum(payload))
        .putInt(command.inv())
        .array()

    override fun toString() = "${name(command)}($arg0, $arg1, ${payload.size}B)"

    companion object {
        const val HEADER_SIZE = 24

        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257

        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        fun checksum(data: ByteArray) = data.sumOf { it.toInt() and 0xff }

        fun name(command: Int) = String(
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(command).array(),
            Charsets.US_ASCII,
        )

        fun read(transport: AdbTransport): AdbMessage {
            val h = ByteArray(HEADER_SIZE).also(transport::readFully)
            val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            val command = b.int
            val arg0 = b.int
            val arg1 = b.int
            val length = b.int
            b.int // 校验和：新版 adbd 协商后可能填 0，不校验
            if (b.int != command.inv()) throw IOException("ADB 消息头损坏")
            val payload = ByteArray(length).also { if (length > 0) transport.readFully(it) }
            return AdbMessage(command, arg0, arg1, payload)
        }
    }
}
