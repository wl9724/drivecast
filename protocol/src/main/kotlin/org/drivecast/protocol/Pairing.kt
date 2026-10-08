package org.drivecast.protocol

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * iPhone 配对和认证用的密码学，规范见 docs/protocol.md「iPhone：TCP + 配对 + 加密」，
 * 参考实现 tools/pairing_ref.py。只用安卓 API 21 就有的 JCA：EC、ECDH、HmacSHA256、AES/GCM。
 */
object Pairing {
    const val ID_LEN = 16
    const val NONCE_LEN = 16
    const val PK_LEN = 65
    const val CODE_BITS = 20
    const val MAX_NAME = 64

    /** P-256 的曲线参数，取自本机生成的密钥：解析对方公钥时和自己的私钥用同一个 provider 的参数。 */
    private val P256: ECParameterSpec by lazy { (keyPair().public as ECPublicKey).params }

    fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** 65 字节未压缩格式 04 || X || Y。 */
    fun encode(pk: PublicKey): ByteArray {
        val w = (pk as ECPublicKey).w
        return byteArrayOf(4) + fixed32(w.affineX) + fixed32(w.affineY)
    }

    /** 解析对方公钥，必须在 P-256 曲线上（防无效曲线攻击）。P-256 的余因子是 1，在曲线上就在子群里。 */
    fun decode(pk: ByteArray): ECPublicKey {
        if (pk.size != PK_LEN || pk[0] != 4.toByte()) throw IOException("公钥格式不对")
        val x = BigInteger(1, pk.copyOfRange(1, 33))
        val y = BigInteger(1, pk.copyOfRange(33, 65))
        val curve = P256.curve
        val p = (curve.field as ECFieldFp).p
        if (x >= p || y >= p || y.multiply(y).subtract(x.pow(3).add(curve.a.multiply(x)).add(curve.b)).mod(p).signum() != 0) {
            throw IOException("公钥不在 P-256 曲线上")
        }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), P256)) as ECPublicKey
    }

    /** P-256 ECDH，共享密钥是 32 字节的 x 坐标。 */
    fun ecdh(sk: PrivateKey, peer: ByteArray): ByteArray {
        val z = KeyAgreement.getInstance("ECDH").run {
            init(sk)
            doPhase(decode(peer), true)
            generateSecret()
        }
        // Lollipop 的 Conscrypt 结果不足 32 字节时有 bug，宁可失败
        if (z.size != 32) throw IOException("ECDH 结果长度异常：${z.size}")
        return z
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach(::update)
        doFinal()
    }

    /** RFC 5869。安卓没有平台 HKDF；salt 不能为空（SecretKeySpec 不收空密钥），规范里用 32 个 0。 */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, n: Int = 32): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < n) {
            t = hmac(prk, t, info, byteArrayOf(i++.toByte()))
            out.write(t)
        }
        return out.toByteArray().copyOf(n)
    }

    /** [label] 是 "DCv1 P"（手机的承诺）或 "DCv1 C"（车机的承诺），[self] 是承诺方自己的公钥。 */
    fun commit(label: String, self: ByteArray, peer: ByteArray, nonce: ByteArray, bit: Int): ByteArray =
        hmac(nonce, ascii(label), self, peer, byteArrayOf((0x80 or bit).toByte()))

    fun ltk(z: ByteArray, carId: ByteArray, phoneId: ByteArray, pkP: ByteArray, pkC: ByteArray): ByteArray =
        hkdf(z, ByteArray(32), ascii("DCv1 LTK") + carId + phoneId + pkP + pkC)

    fun authTag(ltk: ByteArray, carId: ByteArray, phoneId: ByteArray, nonceC: ByteArray, nonceP: ByteArray): ByteArray =
        hmac(ltk, ascii("DCv1 AUTH"), carId, phoneId, nonceC, nonceP)

    /** 返回 (kP2C 手 → 车, kC2P 车 → 手)。 */
    fun sessionKeys(
        ltk: ByteArray, carId: ByteArray, phoneId: ByteArray, nonceC: ByteArray, nonceP: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        val k = hkdf(ltk, nonceC + nonceP, ascii("DCv1 SESS") + carId + phoneId)
        return k.copyOf(16) to k.copyOfRange(16, 32)
    }

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray() // 可能多一个符号位 0，也可能不足 32 字节
        val n = minOf(32, b.size)
        return ByteArray(32).also { System.arraycopy(b, b.size - n, it, 32 - n, n) }
    }
}

/**
 * 认证之后的加密帧：type u8 · len u32 BE（= 明文长度 + 16）· AES-128-GCM 密文 || tag。
 * nonce 是每个方向各自的帧计数器，5 字节头是 AAD。seal 只在发送线程上调用、按发出的顺序调用，
 * open 只在接收线程上调用：计数器错位就解不开，同一个 nonce 用两次 GCM 就破了。
 */
class SecureChannel(private val txKey: ByteArray, private val rxKey: ByteArray) {
    private var txCtr = 0L
    private var rxCtr = 0L
    private val tx = Cipher.getInstance("AES/GCM/NoPadding")
    private val rx = Cipher.getInstance("AES/GCM/NoPadding")

    fun seal(type: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(5 + payload.size + TAG)
        ByteBuffer.wrap(out).put(type.toByte()).putInt(payload.size + TAG)
        init(tx, Cipher.ENCRYPT_MODE, txKey, txCtr++, out)
        tx.doFinal(payload, 0, payload.size, out, 5)
        return out
    }

    /** 读一帧并解密，被改过、重放或乱序的帧都抛 IOException。 */
    fun open(input: DataInputStream): Frame {
        val hdr = ByteArray(5).also(input::readFully)
        val len = ByteBuffer.wrap(hdr, 1, 4).int
        if (len !in TAG..MAX_PAYLOAD + TAG) throw IOException("帧长度异常：$len")
        val body = ByteArray(len).also(input::readFully)
        return try {
            init(rx, Cipher.DECRYPT_MODE, rxKey, rxCtr++, hdr)
            Frame(hdr[0].toInt() and 0xff, rx.doFinal(body))
        } catch (e: GeneralSecurityException) {
            throw IOException("解密失败", e)
        }
    }

    private fun init(c: Cipher, mode: Int, key: ByteArray, ctr: Long, hdr: ByteArray) {
        val nonce = ByteBuffer.allocate(12).putInt(0).putLong(ctr).array()
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, nonce))
        c.updateAAD(hdr, 0, 5)
    }

    private companion object {
        const val TAG = 16
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
