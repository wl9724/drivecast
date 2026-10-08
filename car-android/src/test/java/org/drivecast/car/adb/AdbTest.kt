package org.drivecast.car.adb

import org.drivecast.car.adb.AdbMessage.Companion.AUTH
import org.drivecast.car.adb.AdbMessage.Companion.AUTH_SIGNATURE
import org.drivecast.car.adb.AdbMessage.Companion.AUTH_TOKEN
import org.drivecast.car.adb.AdbMessage.Companion.CLSE
import org.drivecast.car.adb.AdbMessage.Companion.CNXN
import org.drivecast.car.adb.AdbMessage.Companion.OKAY
import org.drivecast.car.adb.AdbMessage.Companion.OPEN
import org.drivecast.car.adb.AdbMessage.Companion.WRTE
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher

class AdbTest {

    /** 从字节流读消息的只读通道。 */
    private open class Replay(bytes: ByteArray) : AdbTransport {
        private val input = ByteArrayInputStream(bytes)
        fun hasMore() = input.available() > 0
        override fun write(data: ByteArray) {}
        override fun readFully(data: ByteArray) {
            if (input.read(data) != data.size) throw EOFException()
        }
        override fun close() {}
    }

    /** 按脚本回放手机的消息，记录车机发出的消息。 */
    private class FakePhone(vararg replies: AdbMessage) : Replay(encode(*replies)) {
        private val output = ByteArrayOutputStream()
        override fun write(data: ByteArray) = output.write(data)

        fun sent(): List<AdbMessage> {
            val r = Replay(output.toByteArray())
            return generateSequence { if (r.hasMore()) AdbMessage.read(r) else null }.toList()
        }
    }

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun headerLayout() {
        val m = AdbMessage(CNXN, 0x01000000, 4096, "host::\u0000".toByteArray())
        val b = ByteBuffer.wrap(m.header()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("CNXN", AdbMessage.name(b.int))
        assertEquals(0x01000000, b.int)
        assertEquals(4096, b.int)
        assertEquals(7, b.int)
        assertEquals(AdbMessage.checksum(m.payload), b.int)
        assertEquals(CNXN.inv(), b.int)
    }

    @Test
    fun connectAndShellWithoutAuth() {
        val phone = FakePhone(
            AdbMessage(CNXN, 0x01000001, 1024 * 1024, "device::ro.product.model=Xiaomi 15 Pro;\u0000".toByteArray()),
            AdbMessage(OKAY, 7, 1),
            AdbMessage(WRTE, 7, 1, "hello\n".toByteArray()),
            AdbMessage(CLSE, 7, 1),
        )
        val adb = AdbConnection(phone, AdbKey(keyPair, "test"))
        assertTrue(adb.connect().startsWith("device::"))
        assertEquals(AdbConnection.MAX_DATA, adb.maxData)
        assertEquals("hello\n", adb.shell("echo hello"))

        val sent = phone.sent()
        assertEquals(listOf(CNXN, OPEN, OKAY, CLSE), sent.map { it.command })
        assertEquals("shell:echo hello\u0000", String(sent[1].payload))
        assertEquals(listOf(1, 7), listOf(sent[2].arg0, sent[2].arg1))
        assertEquals(listOf(1, 7), listOf(sent[3].arg0, sent[3].arg1))
    }

    @Test
    fun signsTokenTheWayAdbdVerifies() {
        val token = ByteArray(20) { it.toByte() }
        val phone = FakePhone(
            AdbMessage(AUTH, AUTH_TOKEN, 0, token),
            AdbMessage(CNXN, 0x01000000, 4096, "device::\u0000".toByteArray()),
        )
        AdbConnection(phone, AdbKey(keyPair, "test")).connect()

        val auth = phone.sent()[1]
        assertEquals(listOf(AUTH, AUTH_SIGNATURE), listOf(auth.command, auth.arg0))
        // adbd 的 RSA_verify(NID_sha1, token, sig)：公钥解出 PKCS#1 v1.5 块，内容应为 SHA-1 DigestInfo + token
        val decoded = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.DECRYPT_MODE, keyPair.public)
            doFinal(auth.payload)
        }
        assertArrayEquals(SHA1_DIGEST_INFO + token, decoded)
    }

    @Test
    fun androidPublicKeyStruct() {
        val pub = keyPair.public as RSAPublicKey
        val raw = AdbKey.androidPublicKey(pub)
        assertEquals(524, raw.size)
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, b.int)
        val n0inv = b.int.toLong() and 0xffffffffL
        val n = ByteArray(256).also { b.get(it) }
        val rr = ByteArray(256).also { b.get(it) }
        assertEquals(pub.publicExponent.toInt(), b.int)

        val modulus = BigInteger(1, n.reversedArray())
        assertEquals(pub.modulus, modulus)
        // n0inv * n ≡ -1 (mod 2^32)
        assertEquals(0xffffffffL, (n0inv * (modulus.toLong() and 0xffffffffL)) and 0xffffffffL)
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(modulus), BigInteger(1, rr.reversedArray()))
    }

    companion object {
        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        private fun encode(vararg ms: AdbMessage) =
            ByteArrayOutputStream().apply { ms.forEach { write(it.header()); write(it.payload) } }.toByteArray()
    }
}
