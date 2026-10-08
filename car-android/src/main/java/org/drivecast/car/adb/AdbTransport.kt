package org.drivecast.car.adb

import java.io.Closeable

/** ADB 的字节通道：现在是 USB，P2 会加 TCP（adb tcpip）。 */
interface AdbTransport : Closeable {
    fun write(data: ByteArray)

    /** 读满 [data]，读不满就抛 IOException。 */
    fun readFully(data: ByteArray)
}
