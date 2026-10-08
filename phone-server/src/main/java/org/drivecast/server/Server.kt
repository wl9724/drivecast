package org.drivecast.server

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Looper
import org.drivecast.protocol.Frame
import org.drivecast.protocol.Hello
import org.drivecast.protocol.HelloAck
import org.drivecast.protocol.Key
import org.drivecast.protocol.MAGIC
import org.drivecast.protocol.Msg
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
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * 手机端投屏服务。车机通过 ADB 执行：
 * `exec:CLASSPATH=/data/local/tmp/drivecast-server.apk app_process / org.drivecast.server.Server`
 * stdin/stdout 就是协议通道，所以任何杂散输出都会破坏数据流。
 */
object Server {
    private const val LOG = "/data/local/tmp/drivecast-server.log"
    private val stdout = BufferedOutputStream(FileOutputStream(FileDescriptor.out), 1 shl 16)

    @JvmStatic
    fun main(args: Array<String>) {
        PrintStream(FileOutputStream(LOG), true).let { System.setOut(it); System.setErr(it) }
        try {
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
            when (f.type) {
                Msg.TOUCH -> injector.touch(Touch.decode(f.payload))
                Msg.KEY -> injector.key(Key.decode(f.payload))
                Msg.LAUNCH -> launch(displayId, String(f.payload, Charsets.UTF_8))
                else -> println("忽略消息 ${f.type}")
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
