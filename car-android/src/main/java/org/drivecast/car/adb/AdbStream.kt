package org.drivecast.car.adb

import org.drivecast.car.adb.AdbMessage.Companion.CLSE
import org.drivecast.car.adb.AdbMessage.Companion.WRTE
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore

/**
 * ADB 上的一条流。流控规则：每发一条 WRTE 都要等对方回 OKAY 才能发下一条，
 * 打开时收到的第一个 OKAY 就是第一次写许可。
 */
class AdbStream internal constructor(private val adb: AdbConnection, val localId: Int) : Closeable {
    @Volatile
    private var remoteId = 0

    @Volatile
    private var closed = false
    private val opened = CountDownLatch(1)
    private val writable = Semaphore(0)
    private val chunks = LinkedBlockingQueue<ByteArray>()

    val input: InputStream = object : InputStream() {
        private var cur = ByteArray(0)
        private var pos = 0

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= cur.size) {
                val next = chunks.take()
                if (next === EOF) {
                    chunks.put(EOF)
                    return -1
                }
                cur = next
                pos = 0
            }
            val n = minOf(len, cur.size - pos)
            System.arraycopy(cur, pos, b, off, n)
            pos += n
            return n
        }
    }

    /** 阻塞直到全部发出；超过 maxData 自动分片。 */
    fun write(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            writable.acquire()
            if (closed) throw IOException("ADB 流已关闭")
            val n = minOf(adb.maxData, data.size - off)
            adb.send(AdbMessage(WRTE, localId, remoteId, data.copyOfRange(off, off + n)))
            off += n
        }
    }

    internal fun awaitOpen() {
        opened.await()
        if (closed) throw IOException("手机拒绝打开该服务")
    }

    internal fun onOkay(remote: Int) {
        if (remoteId == 0) {
            remoteId = remote
            opened.countDown()
        }
        writable.release()
    }

    internal fun onData(payload: ByteArray) = chunks.put(payload)

    internal fun onRemoteClose() {
        if (closed) return
        closed = true
        chunks.put(EOF)
        opened.countDown()
        writable.release()
    }

    override fun close() {
        if (closed) return
        onRemoteClose()
        adb.forget(localId)
        if (remoteId != 0) runCatching { adb.send(AdbMessage(CLSE, localId, remoteId)) }
    }

    private companion object {
        val EOF = ByteArray(0)
    }
}
