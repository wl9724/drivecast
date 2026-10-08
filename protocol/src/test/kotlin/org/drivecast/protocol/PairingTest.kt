package org.drivecast.protocol

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec

/** 对照 tools/pairing_ref.py 生成的测试向量（iPhone 端的测试也对照同一份）。 */
class PairingTest {
    private val v = JSONObject(File("../docs/testvectors/ios-pairing.json").readText())
    private fun b(key: String) = v.getString(key).unhex()
    private val params: ECParameterSpec = Pairing.decode(b("pkP")).params

    @Test
    fun publicKeysAndSharedSecretMatchVectors() {
        for ((d, pk) in listOf("dP" to "pkP", "dC" to "pkC")) {
            val w = mul(BigInteger(v.getString(d), 16), params.generator)
            assertEquals(w, Pairing.decode(b(pk)).w)
            assertArrayEquals(b(pk), Pairing.encode(Pairing.decode(b(pk))))
        }
        assertArrayEquals(b("z"), Pairing.ecdh(priv("dP"), b("pkC")))
        assertArrayEquals(b("z"), Pairing.ecdh(priv("dC"), b("pkP")))
    }

    @Test
    fun commitmentsAndKeysMatchVectors() {
        val rounds = v.getJSONArray("rounds")
        assertEquals(Pairing.CODE_BITS, rounds.length())
        for (i in 0 until rounds.length()) {
            val r = rounds.getJSONObject(i)
            val bit = r.getInt("bit")
            assertEquals(v.getInt("code") shr i and 1, bit)
            assertArrayEquals(r.getString("commitP").unhex(), Pairing.commit("DCv1 P", b("pkP"), b("pkC"), r.getString("nP").unhex(), bit))
            assertArrayEquals(r.getString("commitC").unhex(), Pairing.commit("DCv1 C", b("pkC"), b("pkP"), r.getString("nC").unhex(), bit))
        }
        val ltk = Pairing.ltk(b("z"), b("carId"), b("phoneId"), b("pkP"), b("pkC"))
        assertArrayEquals(b("ltk"), ltk)
        assertArrayEquals(b("tag"), Pairing.authTag(ltk, b("carId"), b("phoneId"), b("nonceC"), b("nonceP")))
        val (p2c, c2p) = Pairing.sessionKeys(ltk, b("carId"), b("phoneId"), b("nonceC"), b("nonceP"))
        assertArrayEquals(b("kP2C"), p2c)
        assertArrayEquals(b("kC2P"), c2p)
    }

    @Test
    fun sealedFramesMatchVectors() {
        val phone = SecureChannel(b("kP2C"), b("kC2P"))
        val car = SecureChannel(b("kC2P"), b("kP2C"))
        val frames = v.getJSONArray("frames")
        for (i in 0 until frames.length()) {
            val f = frames.getJSONObject(i)
            val (tx, rx) = if (f.getString("dir") == "p2c") phone to car else car to phone
            val sealed = tx.seal(f.getInt("type"), f.getString("payload").unhex())
            assertArrayEquals(f.getString("sealed").unhex(), sealed)
            val opened = rx.open(stream(sealed))
            assertEquals(f.getInt("type"), opened.type)
            assertArrayEquals(f.getString("payload").unhex(), opened.payload)
        }
    }

    @Test
    fun rejectsReplayTamperAndReorder() {
        val tx = SecureChannel(b("kP2C"), ByteArray(16))
        val f1 = tx.seal(Msg.VIDEO_FRAME, "frame1".toByteArray())
        val f2 = tx.seal(Msg.VIDEO_FRAME, "frame2".toByteArray())
        fun rx() = SecureChannel(ByteArray(16), b("kP2C"))
        fun afterF1() = rx().also { assertArrayEquals("frame1".toByteArray(), it.open(stream(f1)).payload) }

        rejects { afterF1().open(stream(f1)) } // 重放
        rejects { afterF1().open(stream(f2.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() })) }
        rejects { afterF1().open(stream(f2.copyOf().also { it[0] = 0x12 })) } // 改头（AAD）
        rejects { rx().open(stream(f2)) } // 乱序、丢帧
        assertArrayEquals("frame2".toByteArray(), afterF1().open(stream(f2)).payload)
    }

    @Test
    fun rejectsInvalidPublicKeys() {
        val pk = b("pkP")
        rejects { Pairing.decode(pk.copyOf(64)) }
        rejects { Pairing.decode(pk.copyOf().also { it[0] = 2 }) }
        rejects { Pairing.decode(pk.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }) } // 不在曲线上
        val p = (params.curve.field as ECFieldFp).p
        val x = p.toByteArray().takeLast(32).toByteArray()
        rejects { Pairing.decode(byteArrayOf(4) + x + pk.copyOfRange(33, 65)) } // 坐标不小于 p
    }

    @Test
    fun hkdfMatchesRfc5869() {
        val okm = Pairing.hkdf(ByteArray(22) { 0x0b }, ByteArray(13) { it.toByte() }, ByteArray(10) { (0xf0 + it).toByte() }, 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.hex())
    }

    private fun priv(d: String) =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(v.getString(d), 16), params))

    private fun stream(b: ByteArray) = DataInputStream(ByteArrayInputStream(b))

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("应当被拒绝")
        } catch (_: IOException) {
        }
    }

    /** 测试专用的标量乘（仿射坐标、double-and-add），用来从私钥算公钥对照向量。 */
    private fun mul(d: BigInteger, g: ECPoint): ECPoint {
        var r = ECPoint.POINT_INFINITY
        var a = g
        for (i in 0 until d.bitLength()) {
            if (d.testBit(i)) r = add(r, a)
            a = add(a, a)
        }
        return r
    }

    private fun add(p1: ECPoint, p2: ECPoint): ECPoint {
        if (p1 == ECPoint.POINT_INFINITY) return p2
        if (p2 == ECPoint.POINT_INFINITY) return p1
        val p = (params.curve.field as ECFieldFp).p
        val (x1, y1, x2, y2) = listOf(p1.affineX, p1.affineY, p2.affineX, p2.affineY)
        if (x1 == x2 && (y1 + y2).mod(p).signum() == 0) return ECPoint.POINT_INFINITY
        val l = if (p1 == p2) {
            (x1 * x1 * BigInteger.valueOf(3) + params.curve.a) * (y1 * BigInteger.TWO).modInverse(p)
        } else {
            (y2 - y1) * (x2 - x1).modInverse(p)
        }.mod(p)
        val x3 = (l * l - x1 - x2).mod(p)
        return ECPoint(x3, (l * (x1 - x3) - y1).mod(p))
    }
}
