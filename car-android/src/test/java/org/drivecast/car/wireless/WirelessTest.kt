package org.drivecast.car.wireless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessTest {

    @Test
    fun gatewayAndRememberedComeFirstAndUnreachableAreDropped() {
        val c = Candidates.of(
            remembered = listOf("10.7.7.7", "192.168.43.20", "192.168.43.1"),
            gateway = "192.168.43.1",
            subnets = listOf(Subnet("192.168.43.100", 24, viaWifi = true)),
        )
        // 10.7.7.7 不在任何本地子网里；网关重复只留一个
        assertEquals(
            listOf(Candidate("192.168.43.20", true), Candidate("192.168.43.1", true)),
            c.quick,
        )
        // 253 个主机地址，去掉自己和已在 quick 里的两个
        assertEquals(253 - 1 - 2, c.scan.size)
        assertTrue(c.scan.none { it.host in setOf("192.168.43.100", "192.168.43.0", "192.168.43.255") })
    }

    @Test
    fun softApSubnetIsScannedUnboundAndWideSubnetsOnlyAroundOwnIp() {
        val c = Candidates.of(
            remembered = emptyList(),
            gateway = null,
            subnets = listOf(
                Subnet("10.20.30.1", 24, viaWifi = false), // 车机自己的热点
                Subnet("172.16.5.9", 16, viaWifi = true), // 大网段：只扫 172.16.5.0/24
            ),
        )
        assertTrue(c.quick.isEmpty())
        val ap = c.scan.filter { it.host.startsWith("10.20.30.") }
        assertEquals(253, ap.size)
        assertTrue(ap.none { it.viaWifi })
        val wide = c.scan.filter { it.host.startsWith("172.16.") }
        assertEquals(253, wide.size)
        assertTrue(wide.all { it.host.startsWith("172.16.5.") && it.viaWifi })
    }

    @Test
    fun smallSubnetsAreScannedExactly() {
        assertEquals(
            listOf("192.168.1.5", "192.168.1.6"),
            Candidates.hostsAround(Subnet("192.168.1.6", 30, viaWifi = false)).toList(),
        )
    }

    @Test
    fun parsesPhoneAddressesIncludingHotspotInterfaces() {
        val out = """
            1: lo    inet 127.0.0.1/8 scope host lo\       valid_lft forever preferred_lft forever
            12: rmnet_data0    inet 10.120.3.4/30 scope global rmnet_data0\       valid_lft forever preferred_lft forever
            30: wlan0    inet 192.168.1.5/24 brd 192.168.1.255 scope global wlan0\       valid_lft forever preferred_lft forever
            41: ap0    inet 10.183.22.94/24 brd 10.183.22.255 scope global ap0\       valid_lft forever preferred_lft forever
        """.trimIndent()
        assertEquals(listOf("192.168.1.5", "10.183.22.94"), WirelessAdb.parseIps(out))
    }
}
