package org.drivecast.car.adb

import org.drivecast.car.adb.AdbMessage.Companion.AUTH
import org.drivecast.car.adb.AdbMessage.Companion.AUTH_TOKEN
import org.drivecast.car.adb.AdbMessage.Companion.CLSE
import org.drivecast.car.adb.AdbMessage.Companion.CNXN
import org.drivecast.car.adb.AdbMessage.Companion.OKAY
import org.drivecast.car.adb.AdbMessage.Companion.OPEN
import org.drivecast.car.adb.AdbMessage.Companion.STLS
import org.drivecast.car.adb.AdbMessage.Companion.STLS_VERSION
import org.drivecast.car.adb.AdbMessage.Companion.WRTE
import org.drivecast.protocol.Pairing
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit.SECONDS
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android 11+ 无线调试：本机 TCP 上的假手机，真的 TLS 1.3（JVM 版 Conscrypt）。
 * 假手机的配对包格式和 AES-GCM 照 AOSP 单独写一遍，不调被测代码；SPAKE2 有向量，直接用。
 */
class WirelessDebuggingTest {
    private fun rsa() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val car = AdbKey(rsa(), "drivecast@Head Unit")
    private val phoneKey = AdbKey(rsa(), "phone") // adbd 每次启动随机生成的服务端密钥
    private val pool = Executors.newSingleThreadExecutor()

    private fun <T> phone(script: (Socket) -> T): Pair<Int, Future<T>> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        return server.localPort to pool.submit<T> { server.use { it.accept().use(script) } }
    }

    /** 不带缓冲的 ADB 消息读写：STLS 之后紧跟着 TLS 握手，多读一个字节都会弄丢。 */
    private class Stream(input: InputStream, private val out: OutputStream) : AdbTransport {
        private val input = DataInputStream(input)
        override fun write(data: ByteArray) = out.write(data)
        override fun readFully(data: ByteArray) = input.readFully(data)
        override fun close() {}

        fun send(m: AdbMessage) {
            write(m.header())
            if (m.payload.isNotEmpty()) write(m.payload)
        }

        fun expect(command: Int): AdbMessage =
            AdbMessage.read(this).also { assertEquals(AdbMessage.name(command), AdbMessage.name(it.command)) }
    }

    /** 手机的配对服务（adbd 的 PairingServer）：要求客户端证书、SPAKE2 bob、交换 PeerInfo。返回对方发来的 PeerInfo。 */
    private fun pairingServer(code: String, raw: Socket, client: AdbKey? = car): ByteArray {
        val s = Tls.handshake(raw, phoneKey, client = false)
        if (client != null) assertArrayEquals(client.keyPair.public.encoded, s.session.peerCertificates[0].publicKey.encoded)
        val input = DataInputStream(s.inputStream)
        val spake = Spake2(alice = false, code.toByteArray() + Tls.exportKey(s))
        s.outputStream.write(byteArrayOf(1, 0, 0, 0, 0, 32) + spake.msg)
        val key = SecretKeySpec(
            Pairing.hkdf(spake.process(readPacket(input, 0)), ByteArray(32), "adb pairing_auth aes-128-gcm key".toByteArray(), 16),
            "AES",
        )
        val info = ByteArray(8192).also { it[0] = 1 } // ADB_DEVICE_GUID
        GUID.toByteArray().copyInto(info, 1)
        val sealed = gcm(Cipher.ENCRYPT_MODE, key, info)
        s.outputStream.write(byteArrayOf(1, 1) + ByteBuffer.allocate(4).putInt(sealed.size).array() + sealed)
        return gcm(Cipher.DECRYPT_MODE, key, readPacket(input, 1))
    }

    private fun readPacket(input: DataInputStream, type: Int): ByteArray {
        assertEquals(1, input.readByte().toInt())
        assertEquals(type, input.readByte().toInt())
        return ByteArray(input.readInt()).also(input::readFully)
    }

    /** nonce = u64 小端计数器（0）+ 4 个 0，没有 AAD。 */
    private fun gcm(mode: Int, key: SecretKeySpec, data: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(mode, key, GCMParameterSpec(128, ByteArray(12)))
        doFinal(data)
    }

    @Test
    fun pairsWithTheRightCode() {
        val (port, phone) = phone { pairingServer("482916", it) }
        assertEquals(GUID, AdbPairing.pair("127.0.0.1", port, "482916", car))
        val info = phone.get(10, SECONDS)
        assertEquals(0, info[0].toInt()) // ADB_RSA_PUB_KEY
        val text = String(info, 1, info.size - 1, Charsets.US_ASCII).substringBefore('\u0000')
        assertEquals(String(car.publicKeyPayload(), Charsets.US_ASCII).trimEnd('\u0000'), text)
        assertEquals("drivecast@Head_Unit", text.substringAfter(' ')) // 手机把第一个空格后面当名称
    }

    @Test
    fun wrongCodeFailsCleanly() {
        val (port, phone) = phone { runCatching { pairingServer("482916", it) } }
        try {
            AdbPairing.pair("127.0.0.1", port, "482917", car)
            fail("配对码错了不该成功")
        } catch (e: IOException) {
            assertEquals("配对码不对", e.message)
        }
        assertTrue(phone.get(10, SECONDS).isFailure) // 手机那边也解不开车机的 PeerInfo
    }

    /**
     * 真的 AOSP adb（CI 机器上 Android SDK 自带的主机端）来配对假手机：假手机和车机共用 Spake2/Tls，
     * 这里核对它们和 AOSP 的包格式、导出标签、SPAKE2、HKDF、AES-GCM 一致。没有 adb 时跳过。
     */
    @Test
    fun realAdbPairsWithTheFakePhone() {
        val adb = listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
            .map { File(it, "platform-tools/adb") }.firstOrNull { it.canExecute() }
        assumeTrue("没有 Android SDK 的 adb", adb != null)
        val (port, phone) = phone { pairingServer("135790", it, client = null) }
        try {
            val p = ProcessBuilder(adb!!.path, "pair", "127.0.0.1:$port", "135790").redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            p.waitFor(30, SECONDS)
            assertTrue(out, out.contains("Successfully paired") && out.contains(GUID))
            val info = phone.get(10, SECONDS)
            assertEquals(0, info[0].toInt()) // ADB_RSA_PUB_KEY，和车机发的格式一样
            val text = String(info, 1, info.size - 1, Charsets.US_ASCII).substringBefore('\u0000')
            val pub = File(System.getProperty("user.home"), ".android/adbkey.pub")
            if (pub.exists()) assertEquals(pub.readText().trim(), text.trim())
        } finally {
            ProcessBuilder(adb!!.path, "kill-server").start().waitFor(10, SECONDS)
        }
    }

    @Test
    fun connectsOverStlsAndRunsShell() {
        val (port, phone) = phone { raw ->
            val plain = Stream(raw.getInputStream(), raw.getOutputStream())
            plain.expect(CNXN)
            plain.send(AdbMessage(STLS, STLS_VERSION, 0))
            assertEquals(STLS_VERSION, plain.expect(STLS).arg0)
            val s = Tls.handshake(raw, phoneKey, client = false)
            // adbd 只核对证书里的公钥是不是 adb_keys 里的那把
            assertArrayEquals(car.keyPair.public.encoded, s.session.peerCertificates[0].publicKey.encoded)
            val t = Stream(s.inputStream, s.outputStream)
            t.send(AdbMessage(CNXN, 0x01000000, 4096, "device::ro.product.model=Pixel 9;\u0000".toByteArray()))
            val open = t.expect(OPEN)
            assertEquals("shell:echo hi\u0000", String(open.payload))
            t.send(AdbMessage(OKAY, 9, open.arg0))
            t.send(AdbMessage(WRTE, 9, open.arg0, "hi\n".toByteArray()))
            t.expect(OKAY)
            t.send(AdbMessage(CLSE, 9, open.arg0))
        }
        AdbConnection(TcpTransport.connect("127.0.0.1", 2_000, port), car).use { adb ->
            assertTrue(adb.connect(allowPrompt = false, requireTls = true).contains("Pixel 9"))
            assertEquals("hi\n", adb.shell("echo hi"))
        }
        phone.get(10, SECONDS)
    }

    @Test
    fun requireTlsNeverSignsLegacyTokens() {
        val (port, phone) = phone { raw ->
            val t = Stream(raw.getInputStream(), raw.getOutputStream())
            t.expect(CNXN)
            t.send(AdbMessage(AUTH, AUTH_TOKEN, 0, ByteArray(20) { 7 }))
            // 车机不该回签名（可能被转发去冒充车机），而是直接断开
            try {
                fail("不该收到 ${AdbMessage.read(t)}")
            } catch (_: IOException) {
            }
        }
        try {
            AdbConnection(TcpTransport.connect("127.0.0.1", 2_000, port), car).use {
                it.connect(allowPrompt = false, requireTls = true)
            }
            fail("mDNS 发现的端口不该走旧的 AUTH")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("不签"))
        }
        phone.get(10, SECONDS)
    }

    @Test
    fun rejectedCertificateSaysNotPaired() {
        val (port, phone) = phone { raw ->
            val plain = Stream(raw.getInputStream(), raw.getOutputStream())
            plain.expect(CNXN)
            plain.send(AdbMessage(STLS, STLS_VERSION, 0))
            plain.expect(STLS)
            // 真 adbd 不认证书时发警报后断开；TLS 1.3 的客户端要到第一次读才发现。这里握手完直接断开
            Tls.handshake(raw, phoneKey, client = false)
        }
        try {
            AdbConnection(TcpTransport.connect("127.0.0.1", 2_000, port), car).use {
                it.connect(allowPrompt = false, requireTls = true)
            }
            fail("手机拒绝了证书，不该连上")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("没有配对"))
        }
        phone.get(10, SECONDS)
    }

    private companion object {
        const val GUID = "adb-1A2B3C4D-xYz123"
    }
}
