package org.drivecast.car.wireless

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.drivecast.car.adb.AdbConnection
import org.drivecast.car.adb.AdbKey
import org.drivecast.car.adb.AdbPairing
import org.drivecast.car.adb.TcpTransport
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** 在车机所在的局域网里找开了无线调试（tcpip 5555）且已授权本车机的手机。 */
class PhoneFinder(context: Context, private val key: AdbKey, private val log: (String) -> Unit) {
    // applicationContext：Android 6 的 ConnectivityManager 会把传入的 Context 存进静态字段
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    class Found(val adb: AdbConnection, val host: String, val banner: String)

    /** 找到了手机但它不认本车机的密钥（没勾"一律允许"或 7 天没连过），这时要提示插线重新授权。 */
    var unauthorized = false
        private set

    fun find(remembered: List<String>): Found? {
        val wifi = wifiNetwork()
        val candidates = Candidates.of(remembered, wifi?.let(::gatewayOf), subnets(wifi))
        return connectFirst(probe(candidates, wifi), wifi)
    }

    /** Android 11+ 无线调试配对（用户在车机上输入手机显示的地址和配对码），返回手机的 GUID。 */
    fun pair(host: String, port: Int, code: String): String = AdbPairing.pair(host, port, code, key, binder(host))

    /**
     * 经无线调试的 TLS 端口连上配对过的手机，直接在这条连接上投屏。
     * 只认 TLS、不签 AUTH 令牌：这个地址来自 mDNS 或用户输入，见 [AdbConnection.connect] 的 requireTls。
     * 也不开 5555、不记这个地址：对方是谁确认不了，不能交给会签 AUTH 令牌的 5555 自动连接。
     */
    fun connectTls(host: String, port: Int): Found = connect(host, port, binder(host), requireTls = true)

    /** 和 5555 一样只连本地网段里的地址（不走 DNS），Wi-Fi 网段的绑到 Wi-Fi 网络。 */
    private fun binder(host: String): (Socket) -> Unit {
        val wifi = wifiNetwork()
        val c = Candidates.of(listOf(host), null, subnets(wifi)).firstOrNull()
            ?: throw IOException("$host 不在车机所在的网段里：手机要连车机的热点，或和车机连同一个 Wi-Fi")
        return { s -> if (c.viaWifi) wifi?.bindSocket(s) }
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
     * 绑定后的 socket 走不到热点网段）。蜂窝网卡跳过，不往运营商网络发连接。
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

    /** 并发探测 5555 端口，按候选顺序返回能连上的（候选只有几个，先探测避免逐个等握手超时）。 */
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
            try {
                return connect(c.host, TcpTransport.PORT, { s -> if (c.viaWifi) wifi?.bindSocket(s) }, requireTls = false)
            } catch (e: Exception) {
                if (e.message.orEmpty().contains("未授权")) unauthorized = true
            }
        }
        return null
    }

    private fun connect(host: String, port: Int, bind: (Socket) -> Unit, requireTls: Boolean): Found {
        val transport = TcpTransport.connect(host, CONNECT_TIMEOUT_MS, port, bind)
        try {
            transport.socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val adb = AdbConnection(transport, key)
            val banner = adb.connect(allowPrompt = false, requireTls = requireTls)
            // 投屏时视频至少每 100ms 一帧，这么久收不到任何数据就是断了
            transport.socket.soTimeout = CAST_READ_TIMEOUT_MS
            return Found(adb, host, banner)
        } catch (e: Exception) {
            transport.close()
            throw e
        }
    }

    private companion object {
        const val PARALLELISM = 8
        const val PROBE_TIMEOUT_MS = 400
        const val CONNECT_TIMEOUT_MS = 1_500
        const val HANDSHAKE_TIMEOUT_MS = 5_000
        const val CAST_READ_TIMEOUT_MS = 10_000
    }
}
