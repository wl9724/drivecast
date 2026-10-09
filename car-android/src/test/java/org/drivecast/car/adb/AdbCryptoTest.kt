package org.drivecast.car.adb

import org.drivecast.protocol.Pairing
import org.drivecast.protocol.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** SPAKE2 的向量由 tools/spake2_ref.py 生成，那个模型和 BoringSSL 逐字节核对过（两种角色）。 */
class AdbCryptoTest {
    private val pw = "123456".toByteArray() + ByteArray(64) { it.toByte() } // 配对码 + 64 字节 TLS exporter

    @Test
    fun spake2MatchesBoringSslInBothRoles() {
        val alice = Spake2(alice = true, pw, ByteArray(64) { 0x11 })
        val bob = Spake2(alice = false, pw, ByteArray(64) { 0x22 })
        assertEquals("e24a22e895375f3449ee318faf0a57b6cbdc7e2cac90354f2e988316099c6615", alice.msg.hex())
        assertEquals("e972fae2cb51943c37f0f2f1ad744fa092ea8802051e338df70f70a91f1e967b", bob.msg.hex())
        val key = alice.process(bob.msg)
        assertEquals(
            "e64b8c8a760d86d048bcf7dfa0c8140eb81948ff49524cc8eba15950cb73ff7d" +
                "69a068d424db0bfa9487bb887759275f1e1bd496277b00fc204739eb1dedd9e0",
            key.hex(),
        )
        assertArrayEquals(key, bob.process(alice.msg))
        val aes = Pairing.hkdf(key, ByteArray(32), "adb pairing_auth aes-128-gcm key".toByteArray(), 16)
        assertEquals("d6b9ec542301342ce97cda5c45b30222", aes.hex())
    }

    @Test
    fun wrongPasswordGivesDifferentKeys() {
        val alice = Spake2(alice = true, pw)
        val bob = Spake2(alice = false, "123457".toByteArray() + pw.copyOfRange(6, pw.size))
        assertFalse(alice.process(bob.msg).contentEquals(bob.process(alice.msg)))
    }

    @Test(expected = IOException::class)
    fun pointOffTheCurveIsRejected() {
        Spake2(alice = true, pw).process(ByteArray(32).also { it[0] = 2 }) // y = 2 没有对应的 x
    }

    @Test
    fun certificateCarriesTheAdbKey() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val der = AdbCert.of(kp).encoded
        // 用 JDK 自己的解析器重新读一遍：DER 手写错了这里会失败
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
        assertEquals(3, cert.version)
        cert.verify(kp.public)
        assertArrayEquals(kp.public.encoded, cert.publicKey.encoded)
        assertEquals("SHA256withRSA", cert.sigAlgName)
    }
}
