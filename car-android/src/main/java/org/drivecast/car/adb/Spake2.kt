package org.drivecast.car.adb

import org.drivecast.protocol.unhex
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * adb 配对用的 SPAKE2：BoringSSL 的 spake25519（edwards25519），车机是 alice（"adb pair client\0"），
 * 手机是 bob（"adb pair server\0"）。消息 32 字节，密钥 64 字节。
 * 参考模型和与 BoringSSL 的互通检查见 tools/spake2_ref.py。
 *
 * 密码错了不会在这里报错：两边算出的密钥不同，之后 AES-GCM 解不开。
 */
class Spake2(
    private val alice: Boolean,
    password: ByteArray,
    rand64: ByteArray = ByteArray(64).also(SecureRandom()::nextBytes),
) {
    private val x = le(rand64).mod(L).shiftLeft(3)
    private val pwHash = MessageDigest.getInstance("SHA-512").digest(password)

    /** BoringSSL 的"password scalar hack"：加 l 的倍数让 w 是 8 的倍数。M、N 在素数阶子群里，结果不变，照抄。 */
    private val w = (0..2).fold(le(pwHash).mod(L)) { v, i -> if (v.testBit(i)) v.add(L.shiftLeft(i)) else v }

    val msg: ByteArray = encode(B.mul(x).add((if (alice) M else N).mul(w)))

    fun process(theirs: ByteArray): ByteArray {
        if (theirs.size != 32) throw IOException("SPAKE2 消息长度不对")
        val q = decode(theirs) ?: throw IOException("SPAKE2 消息不在曲线上")
        val k = encode(q.add((if (alice) N else M).mul(w).neg()).mul(x))
        val (a, b) = if (alice) msg to theirs else theirs to msg
        val sha = MessageDigest.getInstance("SHA-512")
        // 每段前面是 u64 小端长度
        for (part in listOf(CLIENT, SERVER, a, b, k, pwHash)) {
            sha.update(ByteArray(8) { i -> (part.size.toLong() shr (8 * i)).toByte() })
            sha.update(part)
        }
        return sha.digest()
    }

    /** 扩展坐标 (X:Y:Z:T)，x = X/Z、y = Y/Z、T = XY/Z：加法不用求逆，老车机上也快。 */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger) {
        /** RFC 8032 5.1.4 的统一加法公式（a = -1），也用来倍点。 */
        fun add(o: Point): Point {
            val a = y.subtract(x).multiply(o.y.subtract(o.x)).mod(P)
            val b = y.add(x).multiply(o.y.add(o.x)).mod(P)
            val c = t.multiply(D2).multiply(o.t).mod(P)
            val d = z.multiply(o.z).shiftLeft(1).mod(P)
            val e = b.subtract(a)
            val f = d.subtract(c)
            val g = d.add(c)
            val h = b.add(a)
            return Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
        }

        fun mul(k: BigInteger): Point {
            var r = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
            for (i in k.bitLength() - 1 downTo 0) {
                r = r.add(r)
                if (k.testBit(i)) r = r.add(this)
            }
            return r
        }

        fun neg() = Point(x.negate().mod(P), y, z, t.negate().mod(P))
    }

    private companion object {
        val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
        val D: BigInteger = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
        val D2: BigInteger = D.shiftLeft(1).mod(P)
        val SQRT_M1: BigInteger = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

        val B = decode("5866666666666666666666666666666666666666666666666666666666666666".unhex())!!
        val M = decode("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e".unhex())!!
        val N = decode("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778".unhex())!!

        val CLIENT = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)
        val SERVER = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)

        fun le(b: ByteArray) = BigInteger(1, b.reversedArray())

        fun encode(p: Point): ByteArray {
            val zi = p.z.modInverse(P)
            val x = p.x.multiply(zi).mod(P)
            var v = p.y.multiply(zi).mod(P)
            if (x.testBit(0)) v = v.setBit(255)
            val be = v.toByteArray()
            return ByteArray(32) { i -> be.getOrElse(be.size - 1 - i) { 0 } }
        }

        /** RFC 8032 解码；和 BoringSSL 一样只要求在曲线上（y 不要求规范形式）。不在曲线上返回 null。 */
        fun decode(b: ByteArray): Point? {
            val y = le(b).clearBit(255).mod(P)
            val yy = y.multiply(y)
            val xx = yy.subtract(BigInteger.ONE).multiply(D.multiply(yy).add(BigInteger.ONE).modInverse(P)).mod(P)
            var x = xx.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
            if (x.multiply(x).subtract(xx).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
            if (x.multiply(x).subtract(xx).mod(P).signum() != 0) return null
            if (x.testBit(0) != ((b[31].toInt() and 0x80) != 0)) x = x.negate().mod(P)
            return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
        }
    }
}
