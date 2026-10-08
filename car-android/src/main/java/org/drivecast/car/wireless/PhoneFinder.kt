package org.drivecast.car.wireless

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.drivecast.car.adb.AdbConnection
import org.drivecast.car.adb.AdbKey
import org.drivecast.car.adb.TcpTransport
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** 在车机所在的局域网里找开了无线调试（tcpip 5555）且已授权本车机的手机。 */
class PhoneFinder(context: Context, private val key: AdbKey, private val log: (String) -> Unit) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    class Found(val adb: AdbConnection, val host: String, val banner: String)

    fun find(remembered: List<String>): Found? {
        val wifi = wifiNetwork()
        val c = Candidates.of(remembered, wifi?.let(::gatewayOf), subnets(wifi))
        if (c.quick.isEmpty() && c.scan.isEmpty()) {
            log("车机没有连上 Wi-Fi，也没有开热点")
            return null
        }
        connectFirst(probe(c.quick, wifi), wifi)?.let { return it }
        log("正在局域网中寻找手机…")
        return connectFirst(probe(c.scan, wifi), wifi)
    }

    /**
     * 不能用 activeNetwork：车机连的是没有外网的手机热点、自己又有 4G 时，默认网络是 4G，
     * 发往热点网段的连接会被路由到运营商。所以单独找出 Wi-Fi 网络，连接时绑定到它。
     */
    @Suppress("DEPRECATION") // allNetworks：API 21 起可用，minSdk 21 没有替代
    private fun wifiNetwork(): Network? = cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun gatewayOf(wifi: Network): String? = cm.getLinkProperties(wifi)?.routes
        ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address && !it.gateway!!.isAnyLocalAddress }
        ?.gateway?.hostAddress

    /**
     * Wi-Fi 网络的地址（要绑定）+ 车机其他网卡的地址（比如车机自己开的热点，不能绑定：
     * 绑定后的 socket 走不到热点网段）。蜂窝网卡跳过，避免扫运营商网络。
     */
    @Suppress("DEPRECATION")
    private fun subnets(wifi: Network?): List<Subnet> {
        val wifiLp = wifi?.let { cm.getLinkProperties(it) }
        val wifiNets = wifiLp?.linkAddresses.orEmpty()
            .filter { it.address is Inet4Address }
            .map { Subnet(it.address.hostAddress!!, it.prefixLength, viaWifi = true) }
        val skip = cm.allNetworks
            .filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true }
            .mapNotNull { cm.getLinkProperties(it)?.interfaceName }
            .toSet() + listOfNotNull(wifiLp?.interfaceName)
        val others = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) && it.name !in skip }
            .flatMap { nif ->
                nif.interfaceAddresses
                    .filter { a -> a.address is Inet4Address && a.address.isSiteLocalAddress }
                    .map { a -> Subnet(a.address.hostAddress!!, a.networkPrefixLength.toInt(), viaWifi = false) }
            }
        return wifiNets + others
    }

    /** 并发探测 5555 端口，按候选顺序返回能连上的。 */
    private fun probe(hosts: List<Candidate>, wifi: Network?): List<Candidate> {
        if (hosts.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(PARALLELISM, hosts.size))
        try {
            return pool.invokeAll(hosts.map { c -> Callable { c.takeIf { isOpen(it, wifi) } } })
                .mapNotNull { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun isOpen(c: Candidate, wifi: Network?): Boolean = try {
        Socket().use { s ->
            if (c.viaWifi) wifi?.bindSocket(s)
            s.connect(InetSocketAddress(c.host, TcpTransport.PORT), PROBE_TIMEOUT_MS)
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun connectFirst(hosts: List<Candidate>, wifi: Network?): Found? {
        for (c in hosts) {
            var transport: TcpTransport? = null
            try {
                transport = TcpTransport.connect(c.host, CONNECT_TIMEOUT_MS) { s -> if (c.viaWifi) wifi?.bindSocket(s) }
                transport.socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                val adb = AdbConnection(transport, key)
                val banner = adb.connect(allowPrompt = false)
                // 投屏时视频至少每 100ms 一帧，这么久收不到任何数据就是断了
                transport.socket.soTimeout = CAST_READ_TIMEOUT_MS
                return Found(adb, c.host, banner)
            } catch (e: Exception) {
                transport?.close()
                log("${c.host}：${e.message ?: e}")
            }
        }
        return null
    }

    private companion object {
        const val PARALLELISM = 48
        const val PROBE_TIMEOUT_MS = 400
        const val CONNECT_TIMEOUT_MS = 1_500
        const val HANDSHAKE_TIMEOUT_MS = 5_000
        const val CAST_READ_TIMEOUT_MS = 10_000
    }
}
