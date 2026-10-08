package org.drivecast.car.wireless

/** 车机本地的一个 IPv4 子网。[viaWifi] 为 true 表示它属于车机连上的 Wi-Fi，socket 要绑到该网络。 */
data class Subnet(val ownIp: String, val prefix: Int, val viaWifi: Boolean)

data class Candidate(val host: String, val viaWifi: Boolean)

object Candidates {
    /**
     * 要尝试的手机地址：记住的手机 IP（开启无线时手机报告的、上次连上的）+ Wi-Fi 网关
     * （车机连手机热点时，网关就是手机）。不在任何本地子网里的到不了，丢掉。
     *
     * 故意不盲扫整个网段：ADB 的认证令牌不绑定连接，局域网里的恶意主机可以把手机发来的
     * 令牌转给车机签名、再拿签名冒充车机登录手机。所以只对这些"认识"的地址签名。
     */
    fun of(remembered: List<String>, gateway: String?, subnets: List<Subnet>): List<Candidate> {
        val seen = subnets.map { it.ownIp }.toHashSet()
        return (remembered + listOfNotNull(gateway)).mapNotNull { h ->
            val net = subnets.firstOrNull { contains(it, h) } ?: return@mapNotNull null
            if (seen.add(h)) Candidate(h, net.viaWifi) else null
        }
    }

    private fun contains(net: Subnet, host: String): Boolean {
        val ip = toInt(host) ?: return false
        val own = toInt(net.ownIp) ?: return false
        val mask = if (net.prefix == 0) 0 else (-1 shl (32 - net.prefix))
        return (ip and mask) == (own and mask)
    }

    /** 只认点分十进制字面量，不走 DNS。 */
    private fun toInt(ip: String): Int? {
        val parts = ip.split('.').map { it.toIntOrNull() ?: return null }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return null
        return parts.fold(0) { acc, p -> (acc shl 8) or p }
    }
}
