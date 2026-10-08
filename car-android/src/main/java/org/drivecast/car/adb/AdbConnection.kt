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
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException

/**
 * ADB 主机端连接。
 * ponytail: 同一时间只跑一条流；P1 视频流和控制流并存时再加按 localId 分发的多路复用。
 */
class AdbConnection(
    private val transport: AdbTransport,
    private val key: AdbKey,
    /** 手机不认识这把公钥、即将弹出"允许 USB 调试"时回调，用来提示用户看手机。 */
    private val onAuthPrompt: () -> Unit = {},
) : Closeable {

    var maxData = MAX_DATA
        private set
    private var nextLocalId = 1

    /** 握手并完成认证，返回手机的 banner（如 "device::ro.product.model=..."）。 */
    fun connect(): String {
        send(AdbMessage(CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray()))
        var signed = false
        while (true) {
            val m = AdbMessage.read(transport)
            when (m.command) {
                CNXN -> {
                    maxData = minOf(m.arg1, MAX_DATA)
                    return String(m.payload, Charsets.UTF_8).trimEnd('\u0000')
                }
                AUTH -> {
                    if (m.arg0 != AUTH_TOKEN) throw IOException("意外的 AUTH 类型 ${m.arg0}")
                    if (!signed) {
                        signed = true
                        send(AdbMessage(AUTH, AUTH_SIGNATURE, 0, key.sign(m.payload)))
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

    /** 执行一条 shell 命令，返回全部输出（stdout/stderr 合并）。 */
    fun shell(command: String): String {
        val localId = nextLocalId++
        send(AdbMessage(OPEN, localId, 0, "shell:$command\u0000".toByteArray()))
        val out = ByteArrayOutputStream()
        var remoteId = 0
        while (true) {
            val m = AdbMessage.read(transport)
            if (m.arg1 != localId) throw IOException("收到其他流的消息 $m")
            when (m.command) {
                OKAY -> remoteId = m.arg0
                WRTE -> {
                    out.write(m.payload)
                    send(AdbMessage(OKAY, localId, m.arg0))
                }
                CLSE -> {
                    if (remoteId == 0) throw IOException("手机拒绝打开 shell")
                    send(AdbMessage(CLSE, localId, remoteId))
                    return String(out.toByteArray(), Charsets.UTF_8)
                }
                else -> throw IOException("意外消息 $m")
            }
        }
    }

    private fun send(m: AdbMessage) {
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
