package org.drivecast.car.adb

import java.io.Closeable
import java.io.IOException

/** ADB 的字节通道：USB，或 TCP（tcpip 5555 / Android 11+ 无线调试）。 */
interface AdbTransport : Closeable {
    fun write(data: ByteArray)

    /** 读满 [data]，读不满就抛 IOException。 */
    fun readFully(data: ByteArray)

    /** 手机回了 STLS：在这条连接上升级到 TLS，之后的读写都走 TLS。只有无线调试的 TCP 连接会遇到。 */
    fun startTls(key: AdbKey): Unit = throw IOException("这条连接不支持 TLS")
}
