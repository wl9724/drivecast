package org.drivecast.car.wireless

import org.drivecast.car.adb.AdbConnection
import org.drivecast.car.adb.TcpTransport
import java.io.IOException

/** 通过已建立的 ADB 连接打开 / 关闭手机的无线调试（adbd 的 tcpip: / usb: 服务）。 */
object WirelessAdb {

    /**
     * 让手机的 adbd 同时监听 TCP 5555，返回手机当前的 IPv4 地址（之后优先尝试）。
     * adbd 会立即重启：这条连接（以及上面的投屏）随之断开，属正常现象。
     * 这个设置手机重启后失效。
     */
    fun enable(adb: AdbConnection): List<String> {
        val ips = parseIps(adb.shell("ip -4 -o addr show"))
        // 已经开着就别再开：tcpip: 无论端口变没变都会重启 adbd
        if (tcpPort(adb) != TcpTransport.PORT.toString()) {
            val reply = readAll(adb, "tcpip:${TcpTransport.PORT}")
            if ("invalid" in reply) throw IOException("手机拒绝开启无线调试：${reply.trim()}")
        }
        return ips
    }

    /** 让 adbd 回到只走 USB。同样会重启 adbd、断开当前连接，所以本来就没开时什么都不做。 */
    fun disable(adb: AdbConnection) {
        val port = tcpPort(adb)
        if (port.isEmpty() || port == "0") return
        readAll(adb, "usb:")
    }

    /** adbd 实际监听的端口：service.adb.tcp.port 为空时它会用 persist.adb.tcp.port。 */
    private fun tcpPort(adb: AdbConnection): String {
        val port = adb.shell("getprop service.adb.tcp.port").trim()
        return port.ifEmpty { adb.shell("getprop persist.adb.tcp.port").trim() }
    }

    /** adbd 写完回复就退出，回复和 CLSE 都可能丢，连接直接断开也算成功。 */
    private fun readAll(adb: AdbConnection, service: String): String = runCatching {
        adb.open(service).use { String(it.input.readBytes(), Charsets.US_ASCII) }
    }.getOrDefault("")

    /**
     * 解析 `ip -4 -o addr show` 的输出，例如
     * `30: wlan0    inet 192.168.1.5/24 brd 192.168.1.255 scope global wlan0\       valid_lft forever ...`。
     * 不按 wlan* 过滤：手机开热点时接口可能叫 ap0、swlan0、wlan1。只去掉回环和蜂窝接口。
     */
    fun parseIps(output: String): List<String> = output.lineSequence()
        .mapNotNull { LINE.find(it) }
        .filter { m -> CELLULAR.none { m.groupValues[1].startsWith(it) } }
        .map { it.groupValues[2] }
        .filter { !it.startsWith("127.") }
        .distinct()
        .toList()

    private val LINE = Regex("""^\d+:\s+(\S+)\s+inet\s+(\d{1,3}(?:\.\d{1,3}){3})/\d+""")
    private val CELLULAR = listOf("lo", "rmnet", "ccmni", "dummy", "v4-", "r_rmnet", "seth")
}
