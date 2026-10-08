package org.drivecast.car.wireless

import org.junit.Assert.assertEquals
import org.junit.Test

class WirelessTest {

    @Test
    fun onlyKnownAddressesInLocalSubnetsAreTried() {
        val c = Candidates.of(
            remembered = listOf("10.7.7.7", "192.168.43.20", "192.168.43.1", "10.20.30.5", "192.168.43.100", "not-an-ip"),
            gateway = "192.168.43.1",
            subnets = listOf(
                Subnet("192.168.43.100", 24, viaWifi = true), // 车机连着手机热点
                Subnet("10.20.30.1", 24, viaWifi = false), // 车机自己开的热点
            ),
        )
        // 10.7.7.7 不在任何本地子网里；网关重复只留一个；车机自己的地址和非法字符串丢掉；
        // 车机热点网段的地址不绑定到 Wi-Fi 网络
        assertEquals(
            listOf(
                Candidate("192.168.43.20", true),
                Candidate("192.168.43.1", true),
                Candidate("10.20.30.5", false),
            ),
            c,
        )
    }

    @Test
    fun subnetMaskIsHonored() {
        val nets = listOf(Subnet("172.16.5.9", 16, viaWifi = true), Subnet("192.168.1.6", 30, viaWifi = false))
        assertEquals(
            listOf(Candidate("172.16.200.1", true), Candidate("192.168.1.5", false)),
            Candidates.of(listOf("172.16.200.1", "172.17.0.1", "192.168.1.5", "192.168.1.9"), null, nets),
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
