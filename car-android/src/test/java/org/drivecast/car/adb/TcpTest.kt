package org.drivecast.car.adb

import org.drivecast.car.adb.AdbMessage.Companion.AUTH
import org.drivecast.car.adb.AdbMessage.Companion.AUTH_SIGNATURE
import org.drivecast.car.adb.AdbMessage.Companion.AUTH_TOKEN
import org.drivecast.car.adb.AdbMessage.Companion.CLSE
import org.drivecast.car.adb.AdbMessage.Companion.CNXN
import org.drivecast.car.adb.AdbMessage.Companion.OKAY
import org.drivecast.car.adb.AdbMessage.Companion.OPEN
import org.drivecast.car.adb.AdbMessage.Companion.WRTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** 用本机 TCP 上的假 adbd 测无线连接：真实 socket、真实读线程。 */
class TcpTest {
    private val key = AdbKey(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair(), "test")
    private val pool = Executors.newSingleThreadExecutor()

    private fun <T> fakeAdbd(script: (TcpTransport) -> T): Pair<Int, Future<T>> {
        val server = ServerSocket(0)
        val f = pool.submit<T> {
            server.use { TcpTransport(it.accept()).use(script) }
        }
        return server.localPort to f
    }

    private fun TcpTransport.send(m: AdbMessage) {
        write(m.header())
        if (m.payload.isNotEmpty()) write(m.payload)
    }

    private fun TcpTransport.expect(command: Int): AdbMessage =
        AdbMessage.read(this).also { assertEquals(AdbMessage.name(command), AdbMessage.name(it.command)) }

    @Test
    fun connectsOverTcpWithKnownKeyAndRunsShell() {
        val (port, phone) = fakeAdbd { t ->
            t.expect(CNXN)
            t.send(AdbMessage(AUTH, AUTH_TOKEN, 0, ByteArray(20) { 7 }))
            assertEquals(AUTH_SIGNATURE, t.expect(AUTH).arg0)
            t.send(AdbMessage(CNXN, 0x01000000, 4096, "device::ro.product.model=Xiaomi 15 Pro;\u0000".toByteArray()))
            val open = t.expect(OPEN)
            assertEquals("shell:getprop service.adb.tcp.port\u0000", String(open.payload))
            t.send(AdbMessage(OKAY, 9, open.arg0))
            t.send(AdbMessage(WRTE, 9, open.arg0, "5555\n".toByteArray()))
            t.expect(OKAY)
            t.send(AdbMessage(CLSE, 9, open.arg0))
        }
        AdbConnection(TcpTransport.connect("127.0.0.1", 2_000, port), key).use { adb ->
            assertTrue(adb.connect(allowPrompt = false).contains("Xiaomi 15 Pro"))
            assertEquals("5555\n", adb.shell("getprop service.adb.tcp.port"))
        }
        phone.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun unauthorizedPhoneIsNotPromptedDuringAutoConnect() {
        val (port, phone) = fakeAdbd { t ->
            t.expect(CNXN)
            t.send(AdbMessage(AUTH, AUTH_TOKEN, 0, ByteArray(20)))
            t.expect(AUTH)
            t.send(AdbMessage(AUTH, AUTH_TOKEN, 0, ByteArray(20)))
            // 车机不应该再发公钥（那会在手机上弹授权框），而是直接断开
            try {
                val m = AdbMessage.read(t)
                fail("不该收到 $m")
            } catch (_: IOException) {
            }
        }
        try {
            AdbConnection(TcpTransport.connect("127.0.0.1", 2_000, port), key).use { it.connect(allowPrompt = false) }
            fail("应当因未授权失败")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("未授权"))
        }
        phone.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun silentLinkEndsStreamsAfterReadTimeout() {
        val (port, phone) = fakeAdbd { t ->
            t.expect(CNXN)
            t.send(AdbMessage(CNXN, 0x01000000, 4096, "device::\u0000".toByteArray()))
            val open = t.expect(OPEN)
            t.send(AdbMessage(OKAY, 9, open.arg0))
            Thread.sleep(3_000) // 之后一直不说话，模拟 Wi-Fi 断了但没有 FIN
        }
        val transport = TcpTransport.connect("127.0.0.1", 2_000, port)
        AdbConnection(transport, key).use { adb ->
            adb.connect()
            transport.socket.soTimeout = 300
            val stream = adb.open("exec:server")
            val start = System.nanoTime()
            assertEquals(-1, stream.input.read())
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2))
        }
        phone.get(5, TimeUnit.SECONDS)
    }
}
