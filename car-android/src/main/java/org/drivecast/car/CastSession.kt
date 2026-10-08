package org.drivecast.car

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import org.drivecast.car.adb.AdbConnection
import org.drivecast.car.adb.AdbStream
import org.drivecast.protocol.HEARTBEAT_MS
import org.drivecast.protocol.Hello
import org.drivecast.protocol.HelloAck
import org.drivecast.protocol.Key
import org.drivecast.protocol.Msg
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

/** 一次投屏：推送并启动手机端服务，收视频解码到 [surface]，把触摸和按键发回手机。 */
class CastSession(
    private val adb: AdbConnection,
    private val surface: Surface,
    private val hello: Hello,
    private val log: (String) -> Unit,
) : Closeable {
    private lateinit var stream: AdbStream
    private val sender = Executors.newSingleThreadScheduledExecutor()
    private var decoder: MediaCodec? = null

    /** 阻塞运行直到断开，在后台线程调用。 */
    fun run(serverApk: ByteArray, firstApp: String) {
        push(serverApk)
        // 2>/dev/null：exec: 的 stderr 和 stdout 混在同一个 PTY 里，会破坏二进制帧
        stream = adb.open("exec:CLASSPATH=$REMOTE_PATH app_process / org.drivecast.server.Server 2>/dev/null")
        send(Msg.HELLO, hello.encode())
        launch(firstApp)
        sender.scheduleWithFixedDelay({ write(Msg.PING, ByteArray(0)) }, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS)

        val input = DataInputStream(BufferedInputStream(stream.input, 1 shl 16))
        input.skipToMagic()
        log("手机端已启动")
        while (true) {
            val f = input.readFrame()
            when (f.type) {
                Msg.HELLO_ACK -> log("虚拟屏 ${HelloAck.decode(f.payload).displayId} 已创建")
                Msg.VIDEO_CONFIG -> startDecoder(VideoConfig.decode(f.payload))
                Msg.VIDEO_FRAME -> decode(VideoFrame.decode(f.payload))
                Msg.BYE -> throw IOException("手机端退出：${String(f.payload, Charsets.UTF_8)}")
            }
        }
    }

    fun touch(action: Int, x: Int, y: Int) = send(Msg.TOUCH, Touch(action, x, y).encode())

    fun key(keycode: Int) {
        send(Msg.KEY, Key(0, keycode).encode())
        send(Msg.KEY, Key(1, keycode).encode())
    }

    fun launch(pkg: String) = send(Msg.LAUNCH, pkg.toByteArray(Charsets.UTF_8))

    /** 手机端是 shell 身份运行的 APK，每次连接都推一遍，保证和车机端版本一致。 */
    private fun push(apk: ByteArray) {
        val reply = adb.open("exec:head -c ${apk.size} > $REMOTE_PATH").use {
            it.write(apk)
            // head 读满后退出，手机关闭这条流；这期间收到的任何输出都是错误信息
            String(it.input.readBytes(), Charsets.UTF_8).trim()
        }
        if (reply.isNotEmpty()) throw IOException("推送手机端服务失败：$reply")
        log("已推送手机端服务（${apk.size / 1024} KB）")
    }

    private fun startDecoder(c: VideoConfig) {
        decoder?.release()
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, c.width, c.height), surface, null, 0)
            start()
            queue(this, c.csd, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
        }
        log("解码器已启动 ${c.width}×${c.height}")
    }

    private fun decode(f: VideoFrame) {
        val d = decoder ?: return
        queue(d, f.data, f.ptsUs, 0)
        // 有就立刻渲染，不按时间戳等待
        val info = MediaCodec.BufferInfo()
        while (true) {
            val i = d.dequeueOutputBuffer(info, 0)
            if (i >= 0) d.releaseOutputBuffer(i, true)
            else if (i == MediaCodec.INFO_TRY_AGAIN_LATER) break
        }
    }

    private fun queue(d: MediaCodec, data: ByteArray, ptsUs: Long, flags: Int) {
        // ponytail: 解码器 100ms 内腾不出输入缓冲就丢这一帧，弱车机花屏到下一个关键帧；要更稳再加按关键帧丢帧
        val i = d.dequeueInputBuffer(100_000)
        if (i < 0) return
        d.getInputBuffer(i)!!.run {
            clear()
            put(data)
        }
        d.queueInputBuffer(i, 0, data.size, ptsUs, flags)
    }

    private fun send(type: Int, payload: ByteArray) {
        if (!::stream.isInitialized) return
        runCatching { sender.execute { write(type, payload) } } // 关闭后提交会被拒绝，忽略
    }

    /** 只在 sender 线程上调用，保证一帧一次 write、帧之间不交错。 */
    private fun write(type: Int, payload: ByteArray) {
        runCatching { stream.write(encodeFrame(type, payload)) }
    }

    override fun close() {
        sender.shutdownNow()
        if (::stream.isInitialized) stream.close()
        runCatching { decoder?.release() }
        adb.close()
    }

    private companion object {
        const val REMOTE_PATH = "/data/local/tmp/drivecast-server.apk"
    }
}
