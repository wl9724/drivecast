package org.drivecast.car.adb

import java.security.KeyPair
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * 车机 ADB 密钥的自签证书，TLS 时出示给手机。adbd 只比较证书里的公钥和 adb_keys 里的是否一致，
 * 不看签发者、有效期，所以手写最小的 X.509 v3（DER，SHA256withRSA，无扩展），不引入证书库。
 */
object AdbCert {
    fun of(kp: KeyPair): X509Certificate {
        val name = seq(tlv(0x31, seq(CN + tlv(0x0c, "DriveCast".toByteArray()))))
        val tbs = seq(
            tlv(0xa0, tlv(0x02, byteArrayOf(2))) + // v3
                tlv(0x02, byteArrayOf(1)) + // 序列号
                SHA256_RSA + name +
                seq(tlv(0x17, "000101000000Z".toByteArray()) + tlv(0x18, "99991231235959Z".toByteArray())) +
                name + kp.public.encoded, // SubjectPublicKeyInfo
        )
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(kp.private)
            update(tbs)
            sign()
        }
        val der = seq(tbs + SHA256_RSA + tlv(0x03, byteArrayOf(0) + sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    /** OID 2.5.4.3（commonName）。 */
    private val CN = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)

    /** AlgorithmIdentifier：sha256WithRSAEncryption（1.2.840.113549.1.1.11）+ NULL 参数。 */
    private val SHA256_RSA = seq(
        byteArrayOf(0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b, 0x05, 0x00),
    )

    private fun seq(v: ByteArray) = tlv(0x30, v)

    private fun tlv(tag: Int, v: ByteArray): ByteArray {
        val len = when {
            v.size < 0x80 -> byteArrayOf(v.size.toByte())
            v.size < 0x100 -> byteArrayOf(0x81.toByte(), v.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (v.size shr 8).toByte(), v.size.toByte())
        }
        return byteArrayOf(tag.toByte()) + len + v
    }
}
