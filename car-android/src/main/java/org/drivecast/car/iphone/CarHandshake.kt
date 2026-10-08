package org.drivecast.car.iphone

import org.drivecast.car.FrameLink
import org.drivecast.protocol.MAGIC
import org.drivecast.protocol.Msg
import org.drivecast.protocol.Pairing
import org.drivecast.protocol.SecureChannel
import org.drivecast.protocol.encodeFrame
import org.drivecast.protocol.readFrame
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * "添加 iPhone" 打开后的配对窗口：2 分钟内有效、一次只配一台、每次尝试都换新码、失败 3 次就关闭。
 * [onChange] 在配对码出现或消失、窗口开关时回调，用来刷新车机屏幕上的提示。
 */
class PairingMode(private val onChange: () -> Unit = {}, private val random: SecureRandom = SecureRandom()) {
    private var until = CLOSED
    private var failures = 0

    /** 断开正在配对的那条连接；为 null 说明没有在配对，或这次配对已在车机上取消。 */
    private var abort: (() -> Unit)? = null

    /** 正在进行的那次配对的配对码，没有为 null。 */
    @get:Synchronized
    var code: Int? = null
        private set

    val active: Boolean
        @Synchronized get() = now() < until

    fun open() = update {
        until = now() + WINDOW_MS
        failures = 0
    }

    /** 关闭配对模式，正在进行的配对也断开：连接一断 [end] 就清掉配对码，画面上的提示随之收起。 */
    fun close() {
        val a = synchronized(this) {
            until = CLOSED
            abort.also { abort = null }
        }
        a?.invoke()
        onChange()
    }

    /** 开始一次配对，返回新的配对码；不在配对模式或已有一台在配对时返回 null。[abort] 断开这条连接。 */
    fun begin(abort: () -> Unit): Int? {
        val c = synchronized(this) {
            if (code != null || now() >= until) null
            else random.nextInt(1_000_000).also { code = it; this.abort = abort }
        }
        return c?.also { onChange() }
    }

    /** 这次配对没被取消才 [save]。在锁里做：车机上点了取消，就不会再存下 LTK。 */
    fun commit(save: () -> Unit) = synchronized(this) {
        if (abort == null) throw IOException("配对已在车机上取消")
        save()
    }

    /** 不管成败，这个配对码都作废。一次只有一台在配对，配对码只由这次配对自己清掉。 */
    fun end(ok: Boolean) = update {
        code = null
        abort = null
        if (ok || ++failures >= MAX_FAILURES) until = CLOSED
    }

    private fun update(block: () -> Unit) {
        synchronized(this, block)
        onChange()
    }

    private fun now() = System.nanoTime() / 1_000_000

    companion object {
        const val WINDOW_MS = 120_000L
        const val MAX_FAILURES = 3
        private const val CLOSED = Long.MIN_VALUE
    }
}

/**
 * 车机这边一条 iPhone 连接的握手：已配对的认证，或在配对模式下配对。
 * 只用字节流和协议原语，单元测试直接在 JVM 上跑。
 */
class CarHandshake(
    private val carId: ByteArray,
    private val ltkOf: (phoneId: ByteArray) -> ByteArray?,
    private val save: (phoneId: ByteArray, ltk: ByteArray, name: String) -> Unit,
    private val pairing: PairingMode,
    /** 配对时要等用户输入配对码：每次读的超时和握手总时限都放宽到这么多毫秒。 */
    private val setTimeout: (Int) -> Unit = {},
    /** 断开这条连接（关 socket），车机上取消配对时用。 */
    private val abort: () -> Unit = {},
    private val random: SecureRandom = SecureRandom(),
) {
    private lateinit var out: OutputStream

    /** 认证通过返回 (phoneId, 加密通道)；配对成功返回 null（双方随即断开）。失败时发 BYE 并抛 IOException。 */
    fun run(input: DataInputStream, output: OutputStream): Pair<ByteArray, SecureChannel>? {
        out = output
        val magic = ByteArray(MAGIC.size).also(input::readFully)
        if (!magic.contentEquals(MAGIC)) throw IOException("不是 DriveCast 连接")
        val nonceC = bytes(Pairing.NONCE_LEN)
        send(Msg.AUTH_CHALLENGE, carId + nonceC)
        val f = input.readFrame(MAX_HANDSHAKE)
        return when (f.type) {
            Msg.AUTH_RESPONSE -> {
                if (f.payload.size != AUTH_LEN) bye("AUTH_RESPONSE 格式不对")
                val phoneId = f.payload.copyOf(Pairing.ID_LEN)
                val ltk = ltkOf(phoneId) ?: bye("这台 iPhone 没有和车机配对，请在 iPhone 的 DriveCast 里重新添加车机")
                phoneId to verify(ltk, phoneId, nonceC, f.payload)
            }
            Msg.PAIR_START -> {
                pair(input, f.payload, nonceC)
                null
            }
            Msg.BYE -> throw IOException("iPhone 断开：${String(f.payload, Charsets.UTF_8)}")
            else -> bye("意外的消息 ${f.type}")
        }
    }

    private fun pair(input: DataInputStream, start: ByteArray, nonceC: ByteArray) {
        val keyEnd = Pairing.ID_LEN + Pairing.PK_LEN
        if (start.size !in keyEnd..keyEnd + Pairing.MAX_NAME) bye("PAIR_START 格式不对")
        val phoneId = start.copyOf(Pairing.ID_LEN)
        val pkP = start.copyOfRange(Pairing.ID_LEN, keyEnd)
        val name = String(start, keyEnd, start.size - keyEnd, Charsets.UTF_8)
        try {
            Pairing.decode(pkP)
        } catch (e: IOException) {
            bye(e.message!!)
        }
        // 只在用户点了"添加 iPhone"之后才配对：否则陌生人可以随时往司机的屏幕上弹配对码
        val code = pairing.begin(abort) ?: bye("车机不在配对模式，或正在和另一台 iPhone 配对：请先在车机上点\"添加 iPhone\"")
        var ok = false
        try {
            setTimeout(PAIRING_TIMEOUT_MS)
            val kp = Pairing.keyPair()
            val pkC = Pairing.encode(kp.public)
            send(Msg.PAIR_KEY, pkC)
            // 逐位承诺（蓝牙 Passkey Entry）：旁听者拿不到 LTK，中间人每一位都要赌
            for (i in 0 until Pairing.CODE_BITS) {
                val bit = code shr i and 1
                val commitP = read(input, Msg.PAIR_COMMIT, 32)
                val nC = bytes(Pairing.NONCE_LEN)
                send(Msg.PAIR_COMMIT, Pairing.commit("DCv1 C", pkC, pkP, nC, bit))
                val nP = read(input, Msg.PAIR_REVEAL, Pairing.NONCE_LEN)
                if (!MessageDigest.isEqual(commitP, Pairing.commit("DCv1 P", pkP, pkC, nP, bit))) {
                    bye("配对码不对，请重新配对，车机会显示新的配对码")
                }
                send(Msg.PAIR_REVEAL, nC)
            }
            val ltk = Pairing.ltk(Pairing.ecdh(kp.private, pkP), carId, phoneId, pkP, pkC)
            val r = read(input, Msg.AUTH_RESPONSE, AUTH_LEN)
            if (!r.copyOf(Pairing.ID_LEN).contentEquals(phoneId)) bye("配对校验失败")
            val channel = verify(ltk, phoneId, nonceC, r)
            pairing.commit { save(phoneId, ltk, name) }
            ok = true
            // 手机能解开这条才保存 LTK
            out.write(channel.seal(Msg.NOTICE, "paired".toByteArray(Charsets.UTF_8)))
            out.flush()
        } finally {
            pairing.end(ok)
        }
    }

    /** 校验 AUTH_RESPONSE（phoneId · nonceP · tag）的 tag，通过就派生这条连接的会话密钥。 */
    private fun verify(ltk: ByteArray, phoneId: ByteArray, nonceC: ByteArray, r: ByteArray): SecureChannel {
        val nonceP = r.copyOfRange(Pairing.ID_LEN, Pairing.ID_LEN + Pairing.NONCE_LEN)
        val tag = r.copyOfRange(Pairing.ID_LEN + Pairing.NONCE_LEN, AUTH_LEN)
        if (!MessageDigest.isEqual(tag, Pairing.authTag(ltk, carId, phoneId, nonceC, nonceP))) bye("认证失败")
        val (p2c, c2p) = Pairing.sessionKeys(ltk, carId, phoneId, nonceC, nonceP)
        return SecureChannel(txKey = c2p, rxKey = p2c)
    }

    private fun read(input: DataInputStream, type: Int, len: Int): ByteArray {
        val f = input.readFrame(MAX_HANDSHAKE)
        if (f.type == Msg.BYE) throw IOException("iPhone 断开：${String(f.payload, Charsets.UTF_8)}")
        if (f.type != type || f.payload.size != len) bye("意外的消息 ${f.type}")
        return f.payload
    }

    private fun send(type: Int, payload: ByteArray) {
        out.write(encodeFrame(type, payload))
        out.flush()
    }

    /** 明文 BYE 告诉对方原因，然后断开。 */
    private fun bye(reason: String): Nothing {
        runCatching { send(Msg.BYE, reason.toByteArray(Charsets.UTF_8)) }
        throw IOException(reason)
    }

    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)

    companion object {
        const val READ_TIMEOUT_MS = 10_000
        const val PAIRING_TIMEOUT_MS = 90_000
        private const val AUTH_LEN = Pairing.ID_LEN + Pairing.NONCE_LEN + 32
        private const val MAX_HANDSHAKE = 256
    }
}

/** 认证后的 iPhone 连接：每一帧都加密。[input] 必须是握手用过的那个（缓冲里可能已有数据）。 */
class SecureLink(
    private val socket: Socket,
    private val input: DataInputStream,
    private val channel: SecureChannel,
    val phoneId: ByteArray,
) : FrameLink {
    private val output = socket.getOutputStream()

    override fun read() = channel.open(input)

    override fun write(type: Int, payload: ByteArray) = output.write(channel.seal(type, payload))

    override fun close() = socket.close()
}
