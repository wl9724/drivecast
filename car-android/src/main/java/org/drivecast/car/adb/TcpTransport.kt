package org.drivecast.car.adb

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/** 无线 ADB：手机执行过 tcpip:5555 后，adbd 在 TCP 上跑同一套明文协议。 */
class TcpTransport(val socket: Socket) : AdbTransport {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 1 shl 16))
    private val output = socket.getOutputStream()

    override fun write(data: ByteArray) = output.write(data)

    override fun readFully(data: ByteArray) = input.readFully(data)

    override fun close() = socket.close()

    companion object {
        const val PORT = 5555

        /** [bind] 在 connect 前调用，用来把 socket 绑到 Wi-Fi 网络（车机默认网络可能是 4G）。 */
        fun connect(host: String, timeoutMs: Int, port: Int = PORT, bind: (Socket) -> Unit = {}): TcpTransport {
            val socket = Socket()
            try {
                bind(socket)
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            } catch (e: Exception) {
                socket.close()
                throw e
            }
            return TcpTransport(socket)
        }
    }
}
