package org.drivecast.server

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.LocalServerSocket
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import org.drivecast.protocol.Frame
import org.drivecast.protocol.Hello
import org.drivecast.protocol.HelloAck
import org.drivecast.protocol.Key
import org.drivecast.protocol.MAGIC
import org.drivecast.protocol.Msg
import org.drivecast.protocol.PHONE_WATCHDOG_MS
import org.drivecast.protocol.SOCKET_NAME
import org.drivecast.protocol.Touch
import org.drivecast.protocol.VideoConfig
import org.drivecast.protocol.VideoFrame
import org.drivecast.protocol.encodeFrame
import org.drivecast.protocol.readFrame
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * 手机端投屏服务。车机通过 ADB 执行：
 * `exec:CLASSPATH=/data/local/tmp/drivecast-server.apk app_process / org.drivecast.server.Server 2>/dev/null`
 * 然后连 `localabstract:drivecast`，协议跑在这个 socket 上。
 *
 * 不直接用 exec: 的 stdin/stdout：adbd 给它分配的是 PTY，每次往返只能搬约 4KB，
 * Wi-Fi 下撑不住视频码率；stderr 也会混进 PTY。exec: 那条流只用来报"已就绪"和维持进程。
 */
object Server {
    private const val LOG = "/data/local/tmp/drivecast-server.log"
    private const val PID = "/data/local/tmp/drivecast-server.pid"

    private lateinit var out: OutputStream

    /** 车机还没连上 socket 时也要有期限，否则车机掉线后这个进程会一直等下去。 */
    @Volatile
    private var lastHeard = SystemClock.uptimeMillis() + CONNECT_GRACE_MS

    @Volatile
    private var injectFailureReported = false

    @JvmStatic
    fun main(args: Array<String>) {
        PrintStream(FileOutputStream(LOG), true).let { System.setOut(it); System.setErr(it) }
        try {
            replacePrevious()
            startWatchdog()
            exitWhenExecStreamCloses()
            val server = LocalServerSocket(SOCKET_NAME)
            FileOutputStream(FileDescriptor.out).run { write(MAGIC); flush() } // 告诉车机可以连了
            val socket = server.accept()
            server.close() // 只服务一个车机
            out = BufferedOutputStream(socket.outputStream, 1 shl 16)
            send(MAGIC)

            val input = DataInputStream(BufferedInputStream(socket.inputStream, 1 shl 16))
            val first = input.readFrame()
            if (first.type != Msg.HELLO) error("第一帧应为 HELLO，收到 ${first.type}")
            val hello = Hello.decode(first.payload)
            println("HELLO $hello")

            Looper.prepareMainLooper()
            val encoder = createEncoder(hello)
            val display = VirtualDisplays.create(hello.width, hello.height, hello.dpi, encoder.createInputSurface())
            encoder.start()
            val displayId = display.display.displayId
            send(encodeFrame(Msg.HELLO_ACK, HelloAck(displayId).encode()))
            println("虚拟屏 $displayId 已创建")

            thread(name = "encoder") { pump(encoder, hello) }
            lastHeard = SystemClock.uptimeMillis()
            control(input, Injector(displayId), displayId, encoder)
        } catch (e: Throwable) {
            e.printStackTrace()
            if (::out.isInitialized) runCatching { send(encodeFrame(Msg.BYE, e.toString().toByteArray())) }
        }
        exitProcess(0)
    }

    private fun createEncoder(h: Hello): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, h.width, h.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, h.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, h.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            // 画面静止时也定期出帧，车机解码器不会卡在旧画面
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    private fun pump(encoder: MediaCodec, h: Hello) {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val i = encoder.dequeueOutputBuffer(info, -1)
                if (i < 0) continue
                val buf = encoder.getOutputBuffer(i)!!
                val data = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(data)
                encoder.releaseOutputBuffer(i, false)
                val frame = if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    encodeFrame(Msg.VIDEO_CONFIG, VideoConfig(h.width, h.height, data).encode())
                } else {
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    encodeFrame(Msg.VIDEO_FRAME, VideoFrame(info.presentationTimeUs, key, data).encode())
                }
                send(frame)
            }
        } catch (e: Throwable) {
            e.printStackTrace() // 车机断开时 socket 写失败
            exitProcess(0)
        }
    }

    private fun control(input: DataInputStream, injector: Injector, displayId: Int, encoder: MediaCodec) {
        while (true) {
            val f: Frame = try {
                input.readFrame()
            } catch (e: EOFException) {
                println("车机已断开")
                return
            }
            lastHeard = SystemClock.uptimeMillis()
            when (f.type) {
                Msg.PING -> Unit
                Msg.REQUEST_KEYFRAME -> encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                Msg.TOUCH -> inject { injector.touch(Touch.decode(f.payload)) }
                Msg.KEY -> inject { injector.key(Key.decode(f.payload)) }
                Msg.LAUNCH -> launch(displayId, String(f.payload, Charsets.UTF_8))
                else -> println("忽略消息 ${f.type}")
            }
        }
    }

    /**
     * 注入失败不能让整个服务退出：小米没开"USB 调试（安全设置）"时每次注入都会抛 SecurityException，
     * 退出的话车机每点一下就断开重连一次。只报告一次，视频照常。
     */
    private inline fun inject(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            println("注入失败：$cause")
            if (!injectFailureReported) {
                injectFailureReported = true
                val hint = if (cause is SecurityException) {
                    "手机不允许模拟点击：小米/红米请在开发者选项打开\"USB 调试（安全设置）\"后重启手机"
                } else {
                    "手机拒绝了点击操作：$cause"
                }
                runCatching { send(encodeFrame(Msg.NOTICE, hint.toByteArray())) }
            }
        }
    }

    /** exec: 那条流被车机关掉（或断开）时 PTY 挂断，读 stdin 会结束：跟着退出。 */
    private fun exitWhenExecStreamCloses() = thread(isDaemon = true, name = "stdin") {
        runCatching { while (System.`in`.read() >= 0) Unit }
        println("exec 流已关闭，退出")
        Runtime.getRuntime().halt(0)
    }

    /** 无线断开时旧实例可能还在跑（还占着虚拟屏和编码器），新实例启动时直接结束它。 */
    private fun replacePrevious() {
        val pidFile = File(PID)
        runCatching {
            val old = pidFile.readText().trim().toInt()
            if (old != Process.myPid() && File("/proc/$old/cmdline").readText().contains("org.drivecast.server")) {
                Process.killProcess(old)
                println("已结束上一个实例 $old")
            }
        }
        pidFile.writeText(Process.myPid().toString())
    }

    /** adbd 不做 TCP 保活：车机突然断电时连接不会结束，只能靠心跳超时自己退出。 */
    private fun startWatchdog() = thread(isDaemon = true, name = "watchdog") {
        while (true) {
            Thread.sleep(1_000)
            if (SystemClock.uptimeMillis() - lastHeard > PHONE_WATCHDOG_MS) {
                println("车机超过 ${PHONE_WATCHDOG_MS}ms 没有消息，退出")
                // halt 而不是 exit：编码线程可能卡在写 socket 上
                Runtime.getRuntime().halt(0)
            }
        }
    }

    private val PACKAGE = Regex("[A-Za-z0-9_.]+")

    private fun launch(displayId: Int, pkg: String) {
        if (!pkg.matches(PACKAGE)) return println("非法包名：$pkg")
        val out = ProcessBuilder(
            "am", "start", "--display", displayId.toString(),
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", pkg,
        ).redirectErrorStream(true).start().inputStream.readBytes()
        println("启动 $pkg：${String(out).trim()}")
    }

    @Synchronized
    private fun send(bytes: ByteArray) {
        out.write(bytes)
        out.flush()
    }

    private const val CONNECT_GRACE_MS = 10_000L
}
