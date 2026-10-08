package org.drivecast.car.iphone

import org.drivecast.protocol.Hello
import org.drivecast.protocol.HelloAck
import org.drivecast.protocol.MAGIC
import org.drivecast.protocol.Msg
import org.drivecast.protocol.Pairing
import org.drivecast.protocol.SecureChannel
import org.drivecast.protocol.encodeFrame
import org.drivecast.protocol.hex
import org.drivecast.protocol.readFrame
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 车机握手 vs 用协议原语搭的测试 iPhone，走本机真实 TCP。 */
class HandshakeTest {
    private val carId = ByteArray(16) { it.toByte() }
    private val phoneId = ByteArray(16) { (16 + it).toByte() }
    private val store = ConcurrentHashMap<String, ByteArray>()
    private val codes = LinkedBlockingQueue<Int>() // 车机屏幕上显示过的配对码
    private val pairing: PairingMode = PairingMode(onChange = { shown() })
    private val pool = Executors.newCachedThreadPool()
    private val sockets = mutableListOf<Socket>()
    private val random = SecureRandom()

    private fun shown() {
        pairing.code?.let(codes::put)
    }

    @After
    fun tearDown() {
        sockets.forEach { it.close() }
        pool.shutdownNow()
    }

    private class Conn(val input: DataInputStream, val out: OutputStream)

    /** 起一个车机端握手，返回 (iPhone 这头的连接, 车机握手结果和车机那头的连接)。 */
    private fun connect(): Pair<Conn, Future<Pair<SecureChannel?, Conn>>> {
        val server = ServerSocket(0)
        val car = pool.submit<Pair<SecureChannel?, Conn>> {
            val s = server.use { it.accept() }
            synchronized(sockets) { sockets += s }
            val c = Conn(DataInputStream(BufferedInputStream(s.getInputStream())), s.getOutputStream())
            val save = { id: ByteArray, ltk: ByteArray, _: String -> store[id.hex()] = ltk }
            CarHandshake(carId, { store[it.hex()] }, save, pairing).run(c.input, c.out) to c
        }
        val s = Socket("127.0.0.1", server.localPort).apply { soTimeout = 5_000 }
        synchronized(sockets) { sockets += s }
        return Conn(DataInputStream(s.getInputStream()), s.getOutputStream()) to car
    }

    private fun Conn.send(type: Int, payload: ByteArray) = out.write(encodeFrame(type, payload))

    private fun Conn.expect(type: Int): ByteArray = input.readFrame().also { assertEquals(type, it.type) }.payload

    /** 发魔数，收 AUTH_CHALLENGE，返回 nonceC。 */
    private fun Conn.hello(): ByteArray {
        out.write(MAGIC)
        val ch = expect(Msg.AUTH_CHALLENGE)
        assertArrayEquals(carId, ch.copyOf(16))
        return ch.copyOfRange(16, 32)
    }

    private fun bytes16() = ByteArray(16).also(random::nextBytes)

    /** 测试 iPhone 按规范配对；[wrongCode] 时输错最低位。成功返回 LTK，被车机 BYE 返回 null。 */
    private fun pair(c: Conn, wrongCode: Boolean): ByteArray? {
        val nonceC = c.hello()
        val kp = Pairing.keyPair()
        val pkP = Pairing.encode(kp.public)
        c.send(Msg.PAIR_START, phoneId + pkP + "测试 iPhone".toByteArray())
        val pkC = c.expect(Msg.PAIR_KEY)
        val code = codes.poll(5, TimeUnit.SECONDS)!! xor (if (wrongCode) 1 else 0)
        for (i in 0 until Pairing.CODE_BITS) {
            val bit = code shr i and 1
            val nP = bytes16()
            c.send(Msg.PAIR_COMMIT, Pairing.commit("DCv1 P", pkP, pkC, nP, bit))
            val commitC = c.expect(Msg.PAIR_COMMIT)
            c.send(Msg.PAIR_REVEAL, nP)
            val f = c.input.readFrame()
            if (f.type == Msg.BYE) return null
            assertEquals(Msg.PAIR_REVEAL, f.type)
            assertArrayEquals(commitC, Pairing.commit("DCv1 C", pkC, pkP, f.payload, bit))
        }
        val ltk = Pairing.ltk(Pairing.ecdh(kp.private, pkC), carId, phoneId, pkP, pkC)
        val notice = authenticate(c, ltk, nonceC).open(c.input)
        assertEquals(Msg.NOTICE, notice.type)
        assertEquals("paired", String(notice.payload))
        return ltk
    }

    private fun authenticate(c: Conn, ltk: ByteArray, nonceC: ByteArray): SecureChannel {
        val nonceP = bytes16()
        c.send(Msg.AUTH_RESPONSE, phoneId + nonceP + Pairing.authTag(ltk, carId, phoneId, nonceC, nonceP))
        val (p2c, c2p) = Pairing.sessionKeys(ltk, carId, phoneId, nonceC, nonceP)
        return SecureChannel(txKey = p2c, rxKey = c2p)
    }

    private fun assertCarFailed(car: Future<*>) {
        try {
            car.get(5, TimeUnit.SECONDS)
            fail("车机应当拒绝")
        } catch (e: ExecutionException) {
            assertTrue(e.cause is IOException)
        }
    }

    @Test
    fun pairsThenAuthenticatesAndExchangesEncryptedFrames() {
        pairing.open()
        val (c1, car1) = connect()
        val ltk = pair(c1, wrongCode = false)!!
        assertNull(car1.get(5, TimeUnit.SECONDS).first)
        assertArrayEquals(ltk, store[phoneId.hex()])
        assertFalse(pairing.active) // 配对成功就关闭配对模式

        val (c2, car2) = connect()
        val phone = authenticate(c2, ltk, c2.hello())
        val (carChannel, carConn) = car2.get(5, TimeUnit.SECONDS)
        val hello = Hello(1280, 720, 160, 30, 4_000_000)
        carConn.out.write(carChannel!!.seal(Msg.HELLO, hello.encode()))
        assertEquals(hello, Hello.decode(phone.open(c2.input).also { assertEquals(Msg.HELLO, it.type) }.payload))
        c2.out.write(phone.seal(Msg.HELLO_ACK, HelloAck(-1).encode()))
        assertEquals(HelloAck(-1), HelloAck.decode(carChannel.open(carConn.input).payload))
    }

    @Test
    fun wrongCodeIsRejectedAndDiscarded() {
        pairing.open()
        repeat(PairingMode.MAX_FAILURES) {
            val (c, car) = connect()
            assertNull(pair(c, wrongCode = true))
            assertCarFailed(car)
            assertNull(pairing.code) // 失败一次配对码就作废
        }
        assertTrue(store.isEmpty())
        assertFalse(pairing.active) // 失败 3 次关闭配对模式

        // 不在配对模式：PAIR_START 直接 BYE，不弹配对码
        val (c, car) = connect()
        c.hello()
        c.send(Msg.PAIR_START, phoneId + Pairing.encode(Pairing.keyPair().public))
        c.expect(Msg.BYE)
        assertCarFailed(car)
        assertTrue(codes.isEmpty())

        // 没配对过的 iPhone 认证：BYE
        val (c3, car3) = connect()
        authenticate(c3, bytes16() + bytes16(), c3.hello())
        c3.expect(Msg.BYE)
        assertCarFailed(car3)
    }
}
