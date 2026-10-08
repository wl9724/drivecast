package org.drivecast.server

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
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
import org.drivecast.protocol.Touch
import org.drivecast.protocol.VideoConfig
import org.drivecast.protocol.VideoFrame
import org.drivecast.protocol.encodeFrame
import org.drivecast.protocol.readFrame
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * 手机端投屏服务。车机通过 ADB 执行：
 * `exec:CLASSPATH=/data/local/tmp/drivecast-server.apk app_process / org.drivecast.server.Server 2>/dev/null`
 * stdin/stdout 就是协议通道（adbd 给 exec: 分配的是 raw 模式 PTY，stderr 也会混进来），
 * 所以任何杂散输出都会破坏数据流：Java 层的输出全部改写到日志文件。
 */
object Server {
    private const val LOG = "/data/local/tmp/drivecast-server.log"
    private const val PID = "/data/local/tmp/drivecast-server.pid"
    private val stdout = BufferedOutputStream(FileOutputStream(FileDescriptor.out), 1 shl 16)

    @Volatile
    private var lastHeard = SystemClock.uptimeMillis()

    @JvmStatic
    fun main(args: Array<String>) {
        PrintStream(FileOutputStream(LOG), true).let { System.setOut(it); System.setErr(it) }
        try {
            replacePrevious()
            send(MAGIC)
            val input = DataInputStream(BufferedInputStream(FileInputStream(FileDescriptor.`in`)))
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
            // 建虚拟屏期间车机的 PING 还堆在 stdin 里没读，看门狗从这里开始计时
            lastHeard = SystemClock.uptimeMillis()
            startWatchdog()
            control(input, Injector(displayId), displayId)
        } catch (e: Throwable) {
            e.printStackTrace()
            runCatching { send(encodeFrame(Msg.BYE, e.toString().toByteArray())) }
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
            e.printStackTrace() // 车机断开时 stdout 写失败
            exitProcess(0)
        }
    }

    private fun control(input: DataInputStream, injector: Injector, displayId: Int) {
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
                Msg.TOUCH -> injector.touch(Touch.decode(f.payload))
                Msg.KEY -> injector.key(Key.decode(f.payload))
                Msg.LAUNCH -> launch(displayId, String(f.payload, Charsets.UTF_8))
                else -> println("忽略消息 ${f.type}")
            }
        }
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

    /** adbd 不做 TCP 保活：车机突然断电时 stdin 不会结束，只能靠心跳超时自己退出。 */
    private fun startWatchdog() = thread(isDaemon = true, name = "watchdog") {
        while (true) {
            Thread.sleep(1_000)
            if (SystemClock.uptimeMillis() - lastHeard > PHONE_WATCHDOG_MS) {
                println("车机超过 ${PHONE_WATCHDOG_MS}ms 没有消息，退出")
                // halt 而不是 exit：编码线程可能卡在写 stdout 上
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
        stdout.write(bytes)
        stdout.flush()
    }
}
