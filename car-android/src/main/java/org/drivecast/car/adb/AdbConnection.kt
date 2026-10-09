package org.drivecast.car.adb

import org.drivecast.car.adb.AdbMessage.Companion.AUTH
import org.drivecast.car.adb.AdbMessage.Companion.AUTH_RSAPUBLICKEY
import org.drivecast.car.adb.AdbMessage.Companion.AUTH_SIGNATURE
import org.drivecast.car.adb.AdbMessage.Companion.AUTH_TOKEN
import org.drivecast.car.adb.AdbMessage.Companion.CLSE
import org.drivecast.car.adb.AdbMessage.Companion.CNXN
import org.drivecast.car.adb.AdbMessage.Companion.OKAY
import org.drivecast.car.adb.AdbMessage.Companion.OPEN
import org.drivecast.car.adb.AdbMessage.Companion.STLS
import org.drivecast.car.adb.AdbMessage.Companion.STLS_VERSION
import org.drivecast.car.adb.AdbMessage.Companion.WRTE
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** ADB 主机端连接。connect() 后由后台线程收消息，按 localId 分发给各条流。 */
class AdbConnection(
    private val transport: AdbTransport,
    private val key: AdbKey,
    /** 手机不认识这把公钥、即将弹出"允许 USB 调试"时回调，用来提示用户看手机。 */
    private val onAuthPrompt: () -> Unit = {},
) : Closeable {

    @Volatile
    var maxData = MAX_DATA
        private set
    private val streams = ConcurrentHashMap<Int, AdbStream>()

    /** 读线程已退出（断开）。之后打开的流不会再有人唤醒，必须当场失败。 */
    @Volatile
    private var dead = false
    private val nextLocalId = AtomicInteger(1)

    /**
     * 握手并完成认证，返回手机的 banner（如 "device::ro.product.model=..."）。
     * [allowPrompt] 为 false 时（无线自动连接）：不发公钥，不在别人的手机上弹授权框；
     * 并且对方必须要求认证、认可我们的签名（或在 TLS 里认可我们的证书），否则不是授权过本车机的手机。
     *
     * 手机回 STLS（Android 11+ 无线调试）时升级到 TLS：手机校验车机证书里的公钥，没有 AUTH。
     * [requireTls]：mDNS 上的服务谁都能发布，只认 TLS（TLS 1.3 的客户端签名绑定这次握手，转发不了），
     * 绝不签 AUTH 令牌（令牌不绑定连接，签了可能被转给手机冒充车机），也不接受明文 CNXN。
     */
    fun connect(allowPrompt: Boolean = true, requireTls: Boolean = false): String {
        send(AdbMessage(CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray()))
        var signed = false
        var tls = false
        while (true) {
            val m = try {
                AdbMessage.read(transport)
            } catch (e: IOException) {
                // TLS 1.3 里手机拒绝车机证书的警报要到握手后第一次读才收到
                if (tls && e !is SocketTimeoutException) {
                    throw IOException("手机不认这台车机：没有配对或配对已失效，请在车机上重新\"无线配对\"", e)
                }
                throw e
            }
            when (m.command) {
                STLS -> {
                    if (tls) throw IOException("重复的 STLS")
                    send(AdbMessage(STLS, STLS_VERSION, 0))
                    transport.startTls(key)
                    tls = true
                }
                CNXN -> {
                    if (requireTls && !tls) throw IOException("对方没有用 TLS，不是开着无线调试的手机")
                    if (!allowPrompt && !signed && !tls) throw IOException("对方没有要求授权，不是授权过本车机的手机")
                    maxData = m.arg1.coerceIn(1, MAX_DATA)
                    thread(isDaemon = true, name = "adb-reader") { readLoop() }
                    return String(m.payload, Charsets.UTF_8).trimEnd('\u0000')
                }
                AUTH -> {
                    if (requireTls || tls) throw IOException("无线调试的连接不签 ADB 令牌，对方不是开着无线调试的手机")
                    if (m.arg0 != AUTH_TOKEN) throw IOException("意外的 AUTH 类型 ${m.arg0}")
                    if (!signed) {
                        signed = true
                        send(AdbMessage(AUTH, AUTH_SIGNATURE, 0, key.sign(m.payload)))
                    } else if (!allowPrompt) {
                        throw IOException("手机未授权这台车机，请用数据线连接一次并勾选\"一律允许\"")
                    } else {
                        // 签名没被认可：发公钥，手机弹授权框，用户确认后回 CNXN
                        onAuthPrompt()
                        send(AdbMessage(AUTH, AUTH_RSAPUBLICKEY, 0, key.publicKeyPayload()))
                    }
                }
                else -> throw IOException("握手时收到意外消息 $m")
            }
        }
    }

    /** 打开一个服务（如 "shell:ls"、"exec:cmd"），手机拒绝时抛 IOException。 */
    fun open(service: String): AdbStream {
        val s = AdbStream(this, nextLocalId.getAndIncrement())
        streams[s.localId] = s
        // dead 在读线程清理之前写入、在这里登记之后读取：要么读线程唤醒它，要么这里唤醒
        if (dead) s.onRemoteClose() else send(AdbMessage(OPEN, s.localId, 0, "$service\u0000".toByteArray()))
        s.awaitOpen()
        return s
    }

    /** 执行一条 shell 命令，返回全部输出（stdout/stderr 合并）。 */
    fun shell(command: String): String =
        open("shell:$command").use { String(it.input.readBytes(), Charsets.UTF_8) }

    private fun readLoop() {
        try {
            while (true) {
                val m = AdbMessage.read(transport)
                val s = streams[m.arg1] ?: continue
                when (m.command) {
                    OKAY -> s.onOkay(m.arg0)
                    WRTE -> {
                        s.onData(m.payload)
                        send(AdbMessage(OKAY, s.localId, m.arg0))
                    }
                    CLSE -> {
                        streams.remove(s.localId)
                        s.onRemoteClose()
                    }
                }
            }
        } catch (_: IOException) {
            // 断开或 close()：下面统一通知各条流
        } finally {
            dead = true
            streams.values.forEach { it.onRemoteClose() }
            streams.clear()
        }
    }

    internal fun forget(localId: Int) = streams.remove(localId)

    @Synchronized
    internal fun send(m: AdbMessage) {
        transport.write(m.header())
        if (m.payload.isNotEmpty()) transport.write(m.payload)
    }

    override fun close() = transport.close()

    companion object {
        /** 0x01000000：不跳过校验和，兼容老手机。 */
        const val VERSION = 0x01000000
        const val MAX_DATA = 256 * 1024
    }
}
