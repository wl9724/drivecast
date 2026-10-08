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
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher

class AdbTest {

    /**
     * 模拟手机：脚本里每条回复写成 "车机已发出 n 条消息后" 才送达，
     * 这样后台读线程不会抢在 open() 登记流之前读到回复。脚本放完后阻塞，直到 close()。
     */
    private class FakePhone(private vararg val script: Pair<Int, AdbMessage>) : AdbTransport {
        private val lock = Object()
        private val output = ByteArrayOutputStream()
        private var pending = ByteArray(0)
        private var pos = 0
        private var next = 0
        private var closed = false

        override fun write(data: ByteArray) = synchronized(lock) {
            output.write(data)
            lock.notifyAll()
        }

        override fun readFully(data: ByteArray) = synchronized(lock) {
            var off = 0
            while (off < data.size) {
                when {
                    pos < pending.size -> {
                        val n = minOf(data.size - off, pending.size - pos)
                        System.arraycopy(pending, pos, data, off, n)
                        pos += n
                        off += n
                    }
                    next < script.size && sent().size >= script[next].first -> {
                        pending = encode(script[next++].second)
                        pos = 0
                    }
                    closed -> throw IOException("closed")
                    else -> lock.wait()
                }
            }
        }

        override fun close() = synchronized(lock) {
            closed = true
            lock.notifyAll()
        }

        /** 车机已完整发出的消息。 */
        fun sent(): List<AdbMessage> = synchronized(lock) {
            val input = ByteArrayInputStream(output.toByteArray())
            val r = object : AdbTransport {
                override fun write(data: ByteArray) {}
                override fun readFully(data: ByteArray) {
                    if (input.read(data) != data.size) throw EOFException()
                }
                override fun close() {}
            }
            val list = mutableListOf<AdbMessage>()
            try {
                while (input.available() > 0) list += AdbMessage.read(r)
            } catch (_: EOFException) {
                // 头已写、负载还没写
            }
            list
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
            1 to AdbMessage(CNXN, 0x01000001, 1024 * 1024, "device::ro.product.model=Xiaomi 15 Pro;\u0000".toByteArray()),
            2 to AdbMessage(OKAY, 7, 1),
            2 to AdbMessage(WRTE, 7, 1, "hello\n".toByteArray()),
            3 to AdbMessage(CLSE, 7, 1),
        )
        AdbConnection(phone, AdbKey(keyPair, "test")).use { adb ->
            assertTrue(adb.connect().startsWith("device::"))
            assertEquals(AdbConnection.MAX_DATA, adb.maxData)
            assertEquals("hello\n", adb.shell("echo hello"))
        }

        val sent = phone.sent()
        assertEquals(listOf(CNXN, OPEN, OKAY), sent.map { it.command })
        assertEquals("shell:echo hello\u0000", String(sent[1].payload))
        assertEquals(listOf(1, 7), listOf(sent[2].arg0, sent[2].arg1))
    }

    @Test
    fun writeWaitsForOkayAndSplitsByMaxData() {
        val phone = FakePhone(
            1 to AdbMessage(CNXN, 0x01000000, 4, "device::\u0000".toByteArray()),
            2 to AdbMessage(OKAY, 7, 1),
            3 to AdbMessage(OKAY, 7, 1),
            4 to AdbMessage(OKAY, 7, 1),
        )
        AdbConnection(phone, AdbKey(keyPair, "test")).use { adb ->
            adb.connect()
            adb.open("exec:cat").write("0123456789".toByteArray())
        }

        val writes = phone.sent().filter { it.command == WRTE }
        assertEquals(listOf("0123", "4567", "89"), writes.map { String(it.payload) })
        assertTrue(writes.all { it.arg0 == 1 && it.arg1 == 7 })
    }

    @Test(expected = IOException::class)
    fun openRejectedThrows() {
        val phone = FakePhone(
            1 to AdbMessage(CNXN, 0x01000000, 4096, "device::\u0000".toByteArray()),
            2 to AdbMessage(CLSE, 0, 1),
        )
        AdbConnection(phone, AdbKey(keyPair, "test")).use { adb ->
            adb.connect()
            adb.open("exec:nope")
        }
    }

    @Test
    fun signsTokenTheWayAdbdVerifies() {
        val token = ByteArray(20) { it.toByte() }
        val phone = FakePhone(
            1 to AdbMessage(AUTH, AUTH_TOKEN, 0, token),
            2 to AdbMessage(CNXN, 0x01000000, 4096, "device::\u0000".toByteArray()),
        )
        AdbConnection(phone, AdbKey(keyPair, "test")).use { it.connect() }

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
