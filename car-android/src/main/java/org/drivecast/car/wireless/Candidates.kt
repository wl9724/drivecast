package org.drivecast.car.wireless

import java.net.Inet4Address
import java.net.InetAddress

/** 车机本地的一个 IPv4 子网。[viaWifi] 为 true 表示它属于车机连上的 Wi-Fi，socket 要绑到该网络。 */
data class Subnet(val ownIp: String, val prefix: Int, val viaWifi: Boolean)

data class Candidate(val host: String, val viaWifi: Boolean)

/**
 * 要尝试的手机地址，按可能性排序：
 * [quick] = 记住的手机 IP + Wi-Fi 网关（车机连手机热点时，网关就是手机），
 * [scan] = 各子网里的其他地址（手机连车机热点时只能扫）。
 * 不在任何本地子网里的地址到不了，直接丢掉。
 */
class Candidates(val quick: List<Candidate>, val scan: List<Candidate>) {
    companion object {
        fun of(remembered: List<String>, gateway: String?, subnets: List<Subnet>): Candidates {
            val own = subnets.map { it.ownIp }.toSet()
            val seen = HashSet<String>(own)
            fun pick(hosts: Sequence<String>) = hosts.mapNotNull { h ->
                val net = subnets.firstOrNull { contains(it, h) } ?: return@mapNotNull null
                if (seen.add(h)) Candidate(h, net.viaWifi) else null
            }.toList()

            val quick = pick((remembered + listOfNotNull(gateway)).asSequence())
            val scan = pick(subnets.asSequence().flatMap { hostsAround(it) })
            return Candidates(quick, scan)
        }

        /** 子网前缀不小于 24 时扫整个子网，否则只扫本机所在的 /24（/16 扫不完）。 */
        fun hostsAround(net: Subnet): Sequence<String> {
            val prefix = maxOf(net.prefix, 24)
            val mask = if (prefix == 32) -1 else (-1 shl (32 - prefix))
            val base = toInt(net.ownIp) and mask
            val size = 1 shl (32 - prefix)
            // 去掉网络地址和广播地址
            return (1 until size - 1).asSequence().map { toIp(base + it) }
        }

        private fun contains(net: Subnet, host: String): Boolean {
            val ip = parse(host) ?: return false
            val mask = if (net.prefix == 0) 0 else (-1 shl (32 - net.prefix))
            return (ip and mask) == (toInt(net.ownIp) and mask)
        }

        private fun parse(host: String): Int? = runCatching {
            if (!host.matches(IPV4)) return null
            toInt(host)
        }.getOrNull()

        private fun toInt(ip: String): Int {
            val b = (InetAddress.getByName(ip) as Inet4Address).address
            return (b[0].toInt() and 0xff shl 24) or (b[1].toInt() and 0xff shl 16) or
                (b[2].toInt() and 0xff shl 8) or (b[3].toInt() and 0xff)
        }

        private fun toIp(v: Int) = "${v ushr 24 and 0xff}.${v ushr 16 and 0xff}.${v ushr 8 and 0xff}.${v and 0xff}"

        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    }
}
