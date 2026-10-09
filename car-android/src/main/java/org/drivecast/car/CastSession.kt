package org.drivecast.car

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.MotionEvent
import android.view.Surface
import org.drivecast.car.adb.AdbConnection
import org.drivecast.car.adb.AdbStream
import org.drivecast.protocol.Frame
import org.drivecast.protocol.HEARTBEAT_MS
import org.drivecast.protocol.Hello
import org.drivecast.protocol.HelloAck
import org.drivecast.protocol.Key
import org.drivecast.protocol.Msg
import org.drivecast.protocol.SOCKET_NAME
import org.drivecast.protocol.Touch
import org.drivecast.protocol.VideoConfig
import org.drivecast.protocol.VideoFrame
import org.drivecast.protocol.encodeFrame
import org.drivecast.protocol.readFrame
import org.drivecast.protocol.skipToMagic
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** 投屏会话跑在上面的帧通道：安卓手机是 ADB 流上的明文帧，iPhone 是 TCP 上的加密帧。 */
interface FrameLink : Closeable {
    /** 只在会话线程上调用。 */
    fun read(): Frame

    /** 只在发送线程上调用：一帧一次 write，帧之间不交错，加密帧的计数器也按这个顺序走。 */
    fun write(type: Int, payload: ByteArray)
}

/** 安卓手机：推送并启动手机端服务，协议跑在 localabstract socket 上（明文帧）。 */
class AdbLink(adb: AdbConnection, serverApk: ByteArray, log: (String) -> Unit) : FrameLink {
    private val process: AdbStream
    private val stream: AdbStream
    private val input: DataInputStream

    init {
        push(adb, serverApk, log)
        // 服务进程挂在这条 exec 流上：关掉它手机端就退出。它只用来等"已就绪"，
        // 协议走 localabstract socket（exec: 是 PTY，每次往返只能搬约 4KB，Wi-Fi 下太慢）
        process = adb.open("exec:CLASSPATH=$REMOTE_PATH app_process / org.drivecast.server.Server 2>/dev/null")
        process.input.skipToMagic()
        stream = adb.open("localabstract:$SOCKET_NAME")
        input = DataInputStream(BufferedInputStream(stream.input, 1 shl 16))
        input.skipToMagic() // 手机端 accept 后先发魔数再等 HELLO
        log("手机端已启动")
    }

    override fun read() = input.readFrame()

    override fun write(type: Int, payload: ByteArray) = stream.write(encodeFrame(type, payload))

    override fun close() {
        stream.close()
        process.close()
    }

    private companion object {
        const val REMOTE_PATH = "/data/local/tmp/drivecast-server.apk"

        /** 手机端是 shell 身份运行的 APK，每次连接都推一遍，保证和车机端版本一致。 */
        fun push(adb: AdbConnection, apk: ByteArray, log: (String) -> Unit) {
            val reply = adb.open("exec:head -c ${apk.size} > $REMOTE_PATH").use {
                it.write(apk)
                // head 读满后退出，手机关闭这条流；这期间收到的任何输出都是错误信息
                String(it.input.readBytes(), Charsets.UTF_8).trim()
            }
            if (reply.isNotEmpty()) throw IOException("推送手机端服务失败：$reply")
            log("已推送手机端服务（${apk.size / 1024} KB）")
        }
    }
}

/** 一次投屏：收视频解码到 [surface]，把触摸和按键发回手机。 */
class CastSession(
    val link: FrameLink,
    private val surface: Surface,
    private val hello: Hello,
    private val log: (String) -> Unit,
) : Closeable {
    private val touches = ConcurrentLinkedQueue<Touch>()
    private val draining = AtomicBoolean(false)
    private val sender = Executors.newSingleThreadScheduledExecutor()
    private var decoder: MediaCodec? = null
    private var waitKeyframe = false
    private var lastKeyRequest = 0L

    /** 能反向控制：安卓手机回 HELLO_ACK 时虚拟屏已建好；iPhone 回 displayId = -1，触摸和按键都不发。 */
    @Volatile
    private var control = false

    /** 阻塞运行直到断开，在后台线程调用。[firstApp] 是安卓手机连上后先打开的应用。 */
    fun run(firstApp: String?) {
        send(Msg.HELLO, hello.encode())
        firstApp?.let { send(Msg.LAUNCH, it.toByteArray(Charsets.UTF_8)) }
        sender.scheduleWithFixedDelay({ write(Msg.PING, ByteArray(0)) }, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS)
        while (true) {
            val f = link.read()
            when (f.type) {
                Msg.HELLO_ACK -> HelloAck.decode(f.payload).displayId.let {
                    control = it >= 0
                    log(if (control) "虚拟屏 $it 已创建" else "手机已连接（只能显示，不能在车机上操作）")
                }
                Msg.VIDEO_CONFIG -> startDecoder(VideoConfig.decode(f.payload))
                Msg.VIDEO_FRAME -> decode(VideoFrame.decode(f.payload))
                Msg.NOTICE -> log(String(f.payload, Charsets.UTF_8))
                Msg.BYE -> throw IOException("手机端退出：${String(f.payload, Charsets.UTF_8)}")
            }
        }
    }

    /**
     * 触摸按顺序发送，只把连续的移动事件合并成最后一个：无线时每帧都要等一次往返，
     * 60~120Hz 的 MOVE 逐个发会越积越多，拖地图跟不上手指。按下/抬起（含第二根手指）
     * 不能和移动事件调换顺序，否则手机收到的手指数前后对不上，系统会丢掉整个手势。
     */
    fun touch(t: Touch) {
        if (!control) return
        touches.add(t)
        if (draining.compareAndSet(false, true)) runCatching { sender.execute(::drainTouches) }
    }

    /** 只在 sender 线程上运行。 */
    private fun drainTouches() {
        draining.set(false) // 先放开：之后到的事件要么被这一轮取到，要么再排一轮
        var last: Touch? = null
        while (true) {
            val t = touches.poll() ?: break
            if (last != null && !(last.action == MotionEvent.ACTION_MOVE && t.action == MotionEvent.ACTION_MOVE)) {
                write(Msg.TOUCH, last.encode())
            }
            last = t
        }
        last?.let { write(Msg.TOUCH, it.encode()) }
    }

    fun key(keycode: Int) {
        if (!control) return
        send(Msg.KEY, Key(0, keycode).encode())
        send(Msg.KEY, Key(1, keycode).encode())
    }

    fun launch(pkg: String) {
        if (control) send(Msg.LAUNCH, pkg.toByteArray(Charsets.UTF_8))
    }

    private fun startDecoder(c: VideoConfig) {
        decoder?.release()
        waitKeyframe = true // 新解码器要从关键帧开始，之前的 P 帧解出来是花屏
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, c.width, c.height), surface, null, 0)
            start()
            queue(this, c.csd, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
        }
        log("解码器已启动 ${c.width}×${c.height}")
    }

    private fun decode(f: VideoFrame) {
        // 还没收到 VIDEO_CONFIG（比如手机端的强制关键帧被编码器丢了）：要一个，手机会连同 SPS/PPS 一起发
        val d = decoder ?: return requestKeyframe()
        // 丢过帧就等下一个关键帧，期间的 P 帧解出来也是花屏
        if (waitKeyframe && !f.keyframe) return requestKeyframe()
        if (queue(d, f.data, f.ptsUs, 0)) {
            if (f.keyframe) waitKeyframe = false
        } else {
            waitKeyframe = true
            requestKeyframe()
        }
        // 有就立刻渲染，不按时间戳等待
        val info = MediaCodec.BufferInfo()
        while (true) {
            val i = d.dequeueOutputBuffer(info, 0)
            if (i >= 0) d.releaseOutputBuffer(i, true)
            else if (i == MediaCodec.INFO_TRY_AGAIN_LATER) break
        }
    }

    /** 解码器 100ms 内腾不出输入缓冲就丢掉这一帧，返回 false。 */
    private fun queue(d: MediaCodec, data: ByteArray, ptsUs: Long, flags: Int): Boolean {
        val i = d.dequeueInputBuffer(100_000)
        if (i < 0) return false
        d.getInputBuffer(i)!!.run {
            clear()
            put(data)
        }
        d.queueInputBuffer(i, 0, data.size, ptsUs, flags)
        return true
    }

    /** 最多每秒请求一次，手机端收到后立即出一个关键帧。 */
    private fun requestKeyframe() {
        val now = System.nanoTime()
        if (now - lastKeyRequest < 1_000_000_000L) return
        lastKeyRequest = now
        send(Msg.REQUEST_KEYFRAME, ByteArray(0))
    }

    private fun send(type: Int, payload: ByteArray) {
        runCatching { sender.execute { write(type, payload) } } // 关闭后提交会被拒绝，忽略
    }

    /** 只在 sender 线程上调用，保证一帧一次 write、帧之间不交错。 */
    private fun write(type: Int, payload: ByteArray) {
        runCatching { link.write(type, payload) }
    }

    override fun close() {
        sender.shutdownNow()
        runCatching { link.close() }
        runCatching { decoder?.release() }
    }
}
