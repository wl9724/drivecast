package org.drivecast.car.adb

import org.drivecast.car.adb.AdbMessage.Companion.AUTH
import org.drivecast.car.adb.AdbMessage.Companion.AUTH_RSAPUBLICKEY
import org.drivecast.car.adb.AdbMessage.Companion.AUTH_SIGNATURE
import org.drivecast.car.adb.AdbMessage.Companion.AUTH_TOKEN
import org.drivecast.car.adb.AdbMessage.Companion.CLSE
import org.drivecast.car.adb.AdbMessage.Companion.CNXN
import org.drivecast.car.adb.AdbMessage.Companion.OKAY
import org.drivecast.car.adb.AdbMessage.Companion.OPEN
import org.drivecast.car.adb.AdbMessage.Companion.WRTE
import java.io.Closeable
import java.io.IOException
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
    private val nextLocalId = AtomicInteger(1)

    /**
     * 握手并完成认证，返回手机的 banner（如 "device::ro.product.model=..."）。
     * [allowPrompt] 为 false 时不发公钥：无线自动搜索不能在别人的手机上弹授权框。
     */
    fun connect(allowPrompt: Boolean = true): String {
        send(AdbMessage(CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray()))
        var signed = false
        while (true) {
            val m = AdbMessage.read(transport)
            when (m.command) {
                CNXN -> {
                    maxData = minOf(m.arg1, MAX_DATA)
                    thread(isDaemon = true, name = "adb-reader") { readLoop() }
                    return String(m.payload, Charsets.UTF_8).trimEnd('\u0000')
                }
                AUTH -> {
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
        send(AdbMessage(OPEN, s.localId, 0, "$service\u0000".toByteArray()))
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
