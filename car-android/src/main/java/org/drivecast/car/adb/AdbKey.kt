package org.drivecast.car.adb

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 车机的 ADB RSA-2048 密钥。必须持久保存：手机勾选"一律允许"记住的是这把公钥，
 * 换了密钥每次都会重新弹授权框。
 */
class AdbKey(val keyPair: KeyPair, private val name: String) {

    /** adbd 用 RSA_sign(NID_sha1, token) 校验：把 20 字节 token 当作 SHA-1 摘要做 PKCS#1 v1.5 签名。 */
    fun sign(token: ByteArray): ByteArray = Signature.getInstance("NONEwithRSA").run {
        initSign(keyPair.private)
        update(SHA1_DIGEST_INFO)
        update(token)
        sign()
    }

    /**
     * AUTH(RSAPUBLICKEY) 和无线配对 PeerInfo 的负载：base64(Android 格式公钥) + " 名称\0"。
     * 手机把第一个空格后面当名称显示，名称里不能再有空格。
     */
    @OptIn(ExperimentalEncodingApi::class) // 纯 Kotlin 实现：java.util.Base64 要 API 26，android.util.Base64 在单元测试里没有
    fun publicKeyPayload(): ByteArray {
        val b64 = Base64.encode(androidPublicKey(keyPair.public as RSAPublicKey))
        return "$b64 ${name.replace(' ', '_')}\u0000".toByteArray(Charsets.US_ASCII)
    }

    companion object {
        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
        private const val WORDS = 64 // 2048 位 / 32

        fun loadOrCreate(dir: File, name: String): AdbKey {
            val priv = File(dir, "adbkey")
            val pub = File(dir, "adbkey.pub.der")
            val kf = KeyFactory.getInstance("RSA")
            if (priv.exists() && pub.exists()) {
                return AdbKey(
                    KeyPair(
                        kf.generatePublic(X509EncodedKeySpec(pub.readBytes())),
                        kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())),
                    ),
                    name,
                )
            }
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            priv.writeBytes(kp.private.encoded)
            pub.writeBytes(kp.public.encoded)
            return AdbKey(kp, name)
        }

        /**
         * adbd 认的公钥结构（小端）：
         * u32 字数(64) | u32 n0inv = -1/n mod 2^32 | u32[64] n | u32[64] rr = 2^4096 mod n | u32 e
         */
        fun androidPublicKey(key: RSAPublicKey): ByteArray {
            val n = key.modulus
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
            val rr = BigInteger.ONE.shiftLeft(WORDS * 32 * 2).mod(n)
            return ByteBuffer.allocate(4 + 4 + WORDS * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(WORDS)
                .putInt(n0inv.toInt())
                .put(littleEndian(n))
                .put(littleEndian(rr))
                .putInt(key.publicExponent.toInt())
                .array()
        }

        private fun littleEndian(v: BigInteger): ByteArray {
            val be = v.toByteArray()
            return ByteArray(WORDS * 4) { i -> be.getOrElse(be.size - 1 - i) { 0 } }
        }
    }
}
