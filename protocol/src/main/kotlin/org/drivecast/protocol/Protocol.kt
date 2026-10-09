package org.drivecast.protocol

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/** DriveCast 协议 v1，规范见 docs/protocol.md。多字节整数一律大端序。 */
object Msg {
    const val HELLO = 0x01
    const val HELLO_ACK = 0x02
    const val VIDEO_CONFIG = 0x10
    const val VIDEO_FRAME = 0x11
    const val REQUEST_KEYFRAME = 0x12
    const val TOUCH = 0x20
    const val KEY = 0x21
    const val LAUNCH = 0x31
    const val PING = 0x40

    // iPhone 的认证和配对，握手阶段明文
    const val AUTH_CHALLENGE = 0x50
    const val AUTH_RESPONSE = 0x51
    const val PAIR_START = 0x52
    const val PAIR_KEY = 0x53
    const val PAIR_COMMIT = 0x54
    const val PAIR_REVEAL = 0x55

    const val NOTICE = 0x7E
    const val BYE = 0x7F
}

const val VERSION = 1

/** 安卓手机端服务监听的抽象 Unix socket 名，车机通过 ADB 的 `localabstract:` 连接。 */
const val SOCKET_NAME = "drivecast"

/** 手机端启动后先输出这 4 字节，车机跳过它之前的杂散输出（如 linker 警告）。 */
val MAGIC = "DCv1".toByteArray(Charsets.US_ASCII)

const val MAX_PAYLOAD = 8 shl 20

/** 车机每秒发一次 PING；手机端这么久收不到任何消息就退出（adbd 发现不了无线断开）。 */
const val HEARTBEAT_MS = 1_000L
const val PHONE_WATCHDOG_MS = 5_000L

class Frame(val type: Int, val payload: ByteArray)

/** 整帧编成一个数组：ADB 流上每次 write 都是一条 WRTE，拆开写会多出往返。 */
fun encodeFrame(type: Int, payload: ByteArray = ByteArray(0)): ByteArray =
    ByteBuffer.allocate(5 + payload.size).put(type.toByte()).putInt(payload.size).put(payload).array()

/** [max]：认证之前的帧都很小，别让陌生人一个长度字段就让车机分配 8MB。 */
fun DataInputStream.readFrame(max: Int = MAX_PAYLOAD): Frame {
    val type = readUnsignedByte()
    val len = readInt()
    if (len !in 0..max) throw IOException("帧长度异常：$len")
    return Frame(type, ByteArray(len).also(::readFully))
}

fun InputStream.skipToMagic() {
    var matched = 0
    while (matched < MAGIC.size) {
        val b = read()
        if (b < 0) throw EOFException("手机端没有启动")
        matched = when (b.toByte()) {
            MAGIC[matched] -> matched + 1
            MAGIC[0] -> 1
            else -> 0
        }
    }
}

private fun ByteBuffer.u8() = get().toInt() and 0xff
private fun ByteBuffer.u16() = short.toInt() and 0xffff
private fun ByteBuffer.rest() = ByteArray(remaining()).also(::get)

/** 车 → 手：车机画面区域和期望的编码参数。 */
data class Hello(val width: Int, val height: Int, val dpi: Int, val fps: Int, val bitrate: Int) {
    fun encode(): ByteArray = ByteBuffer.allocate(12).put(VERSION.toByte())
        .putShort(width.toShort()).putShort(height.toShort()).putShort(dpi.toShort())
        .put(fps.toByte()).putInt(bitrate).array()

    companion object {
        fun decode(p: ByteArray): Hello {
            val b = ByteBuffer.wrap(p)
            val v = b.u8()
            if (v != VERSION) throw IOException("协议版本不一致：车机 $v，手机 $VERSION")
            return Hello(b.u16(), b.u16(), b.u16(), b.u8(), b.int)
        }
    }
}

/** 手 → 车：虚拟屏已创建。 */
data class HelloAck(val displayId: Int) {
    fun encode(): ByteArray = ByteBuffer.allocate(5).put(VERSION.toByte()).putInt(displayId).array()

    companion object {
        fun decode(p: ByteArray) = ByteBuffer.wrap(p).let { it.u8(); HelloAck(it.int) }
    }
}

/** 手 → 车：H.264 SPS/PPS（Annex B），车机据此（重新）创建解码器。 */
class VideoConfig(val width: Int, val height: Int, val csd: ByteArray) {
    fun encode(): ByteArray = ByteBuffer.allocate(4 + csd.size)
        .putShort(width.toShort()).putShort(height.toShort()).put(csd).array()

    companion object {
        fun decode(p: ByteArray) = ByteBuffer.wrap(p).let { VideoConfig(it.u16(), it.u16(), it.rest()) }
    }
}

/** 手 → 车：一帧 H.264（Annex B）。 */
class VideoFrame(val ptsUs: Long, val keyframe: Boolean, val data: ByteArray) {
    fun encode(): ByteArray = ByteBuffer.allocate(9 + data.size)
        .putLong(ptsUs).put(if (keyframe) 1 else 0).put(data).array()

    companion object {
        fun decode(p: ByteArray) = ByteBuffer.wrap(p).let { VideoFrame(it.long, it.u8() and 1 != 0, it.rest()) }
    }
}

/** 一根手指：[id] 是车机 MotionEvent 的 pointerId，坐标是视频像素坐标。 */
data class Pointer(val id: Int, val x: Int, val y: Int)

/**
 * 车 → 手：一次触摸事件，带上当前按着的所有手指，对应安卓的一个 MotionEvent。
 * action 与 MotionEvent.getActionMasked 一致：0 按下 / 1 抬起 / 2 移动 / 3 取消 /
 * 5 又一根手指按下 / 6 某根手指抬起（其余手指还按着）。[actionId] 是按下或抬起的那根手指的 id。
 * 布局：action u8 · actionId u8 · count u8 · [id u8 · x u16 · y u16] × count
 */
data class Touch(val action: Int, val actionId: Int, val pointers: List<Pointer>) {
    fun encode(): ByteArray {
        val b = ByteBuffer.allocate(3 + 5 * pointers.size)
            .put(action.toByte()).put(actionId.toByte()).put(pointers.size.toByte())
        pointers.forEach { b.put(it.id.toByte()).putShort(it.x.toShort()).putShort(it.y.toShort()) }
        return b.array()
    }

    companion object {
        /** 和安卓 MotionEvent 的上限（input/Input.h MAX_POINTERS）一致，车机上的事件不会超过它。 */
        const val MAX_POINTERS = 16

        fun decode(p: ByteArray): Touch {
            val b = ByteBuffer.wrap(p)
            val action = b.u8()
            val actionId = b.u8()
            val count = b.u8()
            if (count !in 1..MAX_POINTERS || p.size != 3 + 5 * count) throw IOException("TOUCH 格式不对")
            return Touch(action, actionId, List(count) { Pointer(b.u8(), b.u16(), b.u16()) })
        }
    }
}

/** 车 → 手：按键。action 0 按下 / 1 抬起，keycode 为 Android KEYCODE_*。 */
data class Key(val action: Int, val keycode: Int) {
    fun encode(): ByteArray = ByteBuffer.allocate(3).put(action.toByte()).putShort(keycode.toShort()).array()

    companion object {
        fun decode(p: ByteArray) = ByteBuffer.wrap(p).let { Key(it.u8(), it.u16()) }
    }
}
