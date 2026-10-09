package org.drivecast.car.adb

import org.drivecast.protocol.Pairing
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android 11+ "无线调试 → 使用配对码配对"的主机端（AOSP adb/pairing_auth、pairing_connection）：
 * TLS 1.3 → 导出 64 字节密钥材料接在配对码后面当 SPAKE2 密码 → 交换 SPAKE2 消息 →
 * 用协商出的密钥加密交换 PeerInfo：车机发 ADB 公钥（手机写进 adb_keys，和 USB "一律允许"同一个文件），
 * 手机回它的 GUID（无线调试 mDNS 服务的实例名，之后靠它认出这台手机）。
 */
object AdbPairing {
    /** 返回手机的 GUID。配对码错了抛 IOException。 */
    fun pair(host: String, port: Int, code: String, key: AdbKey, bind: (Socket) -> Unit = {}): String {
        val raw = Socket()
        try {
            bind(raw)
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            raw.soTimeout = READ_TIMEOUT_MS
            Tls.handshake(raw, key).use { s ->
                val input = DataInputStream(s.inputStream)
                val out = s.outputStream
                val spake = Spake2(alice = true, code.toByteArray(Charsets.US_ASCII) + Tls.exportKey(s))
                // 双方都是先发再收
                out.write(packet(SPAKE2_MSG, spake.msg))
                val aes = SecretKeySpec(
                    Pairing.hkdf(spake.process(read(input, SPAKE2_MSG)), ByteArray(32), HKDF_INFO, 16),
                    "AES",
                )
                val mine = ByteArray(PEER_INFO_SIZE)
                mine[0] = ADB_RSA_PUB_KEY
                key.publicKeyPayload().copyInto(mine, 1) // "base64 名称\0"，后面补 0
                out.write(packet(PEER_INFO, gcm(Cipher.ENCRYPT_MODE, aes, mine)))
                val theirs = try {
                    gcm(Cipher.DECRYPT_MODE, aes, read(input, PEER_INFO))
                } catch (_: BadPaddingException) { // AEADBadTagException：标签对不上
                    throw IOException("配对码不对")
                }
                if (theirs.size != PEER_INFO_SIZE || theirs[0] != ADB_DEVICE_GUID) throw IOException("手机发来的信息格式不对")
                val guid = String(theirs.copyOfRange(1, theirs.size), Charsets.US_ASCII).substringBefore('\u0000')
                // 存进 Prefs（逗号分隔），也是 mDNS 实例名
                if (!GUID.matches(guid)) throw IOException("手机的 GUID 格式不对")
                return guid
            }
        } catch (e: IOException) {
            throw if (e.message.orEmpty().startsWith("配对码")) e else IOException("配对失败：${e.message ?: e}", e)
        } finally {
            raw.close()
        }
    }

    /** 包头 6 字节：版本 1 · 类型 · 负载长度 u32 大端。 */
    private fun packet(type: Byte, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(6 + payload.size).put(1.toByte()).put(type).putInt(payload.size).put(payload).array()

    private fun read(input: DataInputStream, type: Byte): ByteArray {
        val version = input.readByte()
        val t = input.readByte()
        val len = input.readInt()
        if (version != 1.toByte() || t != type || len !in 1..MAX_PAYLOAD) throw IOException("配对消息格式不对")
        return ByteArray(len).also(input::readFully)
    }

    /** AES-128-GCM，nonce 是 u64 小端计数器 + 4 个 0：每个方向只发一条，计数器都是 0。没有 AAD。 */
    private fun gcm(mode: Int, key: SecretKeySpec, data: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(mode, key, GCMParameterSpec(128, ByteArray(12)))
        doFinal(data)
    }

    private const val SPAKE2_MSG: Byte = 0
    private const val PEER_INFO: Byte = 1
    private const val ADB_RSA_PUB_KEY: Byte = 0
    private const val ADB_DEVICE_GUID: Byte = 1
    private const val PEER_INFO_SIZE = 8192
    private const val MAX_PAYLOAD = 2 * PEER_INFO_SIZE
    private val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)
    private val GUID = Regex("[A-Za-z0-9._-]{1,128}")
    private const val CONNECT_TIMEOUT_MS = 3_000

    /** 配对很快；超时只防手机关掉配对窗口后卡住。 */
    private const val READ_TIMEOUT_MS = 10_000
}
