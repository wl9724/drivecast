package org.drivecast.car.adb

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * 无线 ADB：手机执行过 tcpip:5555 后，adbd 在 TCP 上跑同一套明文协议；
 * Android 11+ 无线调试的端口先明文 CNXN、再升级到 TLS（[startTls]）。
 * [socket] 始终是底层 TCP socket：读超时设在它上面，TLS 也生效。
 */
class TcpTransport(val socket: Socket) : AdbTransport {
    private var input = DataInputStream(BufferedInputStream(socket.getInputStream(), 1 shl 16))
    private var output: OutputStream = socket.getOutputStream()
    private var tls: SSLSocket? = null

    override fun write(data: ByteArray) = output.write(data)

    override fun readFully(data: ByteArray) = input.readFully(data)

    override fun startTls(key: AdbKey) {
        // 包在底层 socket 上，不是包在带缓冲的流上：adbd 发完 STLS 就等 ClientHello，缓冲里不该有数据，有就是协议错了
        if (input.available() > 0) throw IOException("STLS 之后收到了多余的明文数据")
        val s = Tls.handshake(socket, key)
        tls = s
        input = DataInputStream(BufferedInputStream(s.inputStream, 1 shl 16))
        output = s.outputStream
    }

    override fun close() {
        socket.close() // 先关底层 socket：阻塞在读写上的线程立刻返回
        tls?.let { runCatching { it.close() } }
    }

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
