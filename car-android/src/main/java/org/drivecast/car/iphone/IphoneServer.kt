package org.drivecast.car.iphone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import org.drivecast.car.Prefs
import org.drivecast.protocol.Pairing
import org.drivecast.protocol.hex
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Timer
import kotlin.concurrent.schedule
import kotlin.concurrent.thread

/**
 * iPhone 没有 ADB，由 iPhone 上的 DriveCast 主动连车机：监听 TCP 27420（被占用就随机端口），
 * 用 Bonjour 广播 `_drivecast._tcp`。认证通过的连接交给 [onAuthed]。
 */
class IphoneServer(
    context: Context,
    private val prefs: Prefs,
    private val pairing: PairingMode,
    private val log: (String) -> Unit,
    private val onAuthed: (SecureLink) -> Unit,
) : Closeable {
    private val app = context.applicationContext
    private val carId = loadCarId(File(app.filesDir, "car-id"))
    private val server = try {
        ServerSocket(PORT) // 不指定地址就是双栈、所有网卡（车机自己的热点和连上的 Wi-Fi）
    } catch (_: IOException) {
        ServerSocket(0)
    }
    val port: Int get() = server.localPort
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** Android 13 之前（以及没更新 T 扩展 7 的 13）要自己拿着组播锁才能收发 mDNS。车机不在乎这点电。 */
    private val multicast = (app.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("drivecast").apply {
            setReferenceCounted(false)
            acquire()
        }

    // ponytail: 按地址限，热点里的人凑够 4 个地址（多个 IPv6 地址、换 MAC 拿多个 DHCP 地址）
    //  每 10 秒重连一次仍能占满名额；要防再改成名额满时踢掉最早的未认证握手
    /** 正在握手的对方地址：每个地址同时只握手一条，最多 [MAX_HANDSHAKES] 条，陌生人狂连也只占这么多线程。 */
    private val handshaking = HashSet<InetAddress>()

    /** 握手总时限到了就关 socket：只限每次读的超时的话，慢慢喂字节就能一直占着名额。 */
    private val deadlines = Timer("iphone-deadline", true)

    private val registration = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {}
        override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) =
            log("Bonjour 广播失败（$error），手机上请手动输入车机地址")
        override fun onServiceUnregistered(info: NsdServiceInfo) {}
        override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
    }

    init {
        val info = NsdServiceInfo().apply {
            serviceName = "DriveCast ${Build.MODEL}" // 重名时系统会改名，iPhone 按 TXT 里的 id 认车机
            serviceType = SERVICE_TYPE
            port = server.localPort
            setAttribute("id", carId.hex())
        }
        // 不调 setNetwork：Android 14 起只有"所有网络"的注册才会在车机自己开的热点上广播
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        thread(isDaemon = true, name = "iphone-accept") { acceptLoop() }
    }

    private fun acceptLoop() {
        while (true) {
            val s = try {
                server.accept()
            } catch (_: IOException) {
                return // close()
            }
            val from = s.inetAddress
            if (!synchronized(handshaking) { handshaking.size < MAX_HANDSHAKES && handshaking.add(from) }) {
                s.close()
                continue
            }
            thread(isDaemon = true, name = "iphone-handshake") {
                try {
                    handle(s)
                } finally {
                    synchronized(handshaking) { handshaking.remove(from) }
                }
            }
        }
    }

    private fun handle(s: Socket) {
        try {
            s.tcpNoDelay = true
            s.soTimeout = CarHandshake.READ_TIMEOUT_MS
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
            // 认证总共 10 秒；配对开始（车机显示配对码）后放宽到 90 秒
            var deadline = closeLater(s, CarHandshake.READ_TIMEOUT_MS)
            val setTimeout = { ms: Int ->
                s.soTimeout = ms
                deadline.cancel()
                deadline = closeLater(s, ms)
            }
            val authed = try {
                CarHandshake(carId, prefs::iphoneLtk, prefs::saveIphone, pairing, setTimeout, { s.close() })
                    .run(input, s.getOutputStream())
            } finally {
                deadline.cancel()
            }
            if (authed == null) {
                s.close()
                log("手机已配对，在手机上开始投屏即可")
                return
            }
            // 投屏时视频至少每 100ms 一帧，这么久收不到任何数据就是断了
            s.soTimeout = CarHandshake.READ_TIMEOUT_MS
            onAuthed(SecureLink(s, input, authed.second, authed.first))
        } catch (_: EOFException) {
            s.close() // iPhone 找车机时会探测端口，连上就断，不用提示
        } catch (e: Exception) {
            s.close()
            log("手机连接失败：${e.message ?: e}")
        }
    }

    /** Timer 的任务抛异常会让整个 Timer 停掉，所以 close 的异常要吞掉。 */
    private fun closeLater(s: Socket, ms: Int) = deadlines.schedule(ms.toLong()) { runCatching { s.close() } }

    override fun close() {
        runCatching { nsd.unregisterService(registration) }
        multicast.release()
        server.close()
        deadlines.cancel()
    }

    companion object {
        const val PORT = 27420
        const val SERVICE_TYPE = "_drivecast._tcp"
        private const val MAX_HANDSHAKES = 4

        /** carId：16 字节随机数，生成一次后存在 filesDir，iPhone 按它找 LTK。 */
        private fun loadCarId(f: File): ByteArray {
            if (f.length() == Pairing.ID_LEN.toLong()) return f.readBytes()
            return ByteArray(Pairing.ID_LEN).also { SecureRandom().nextBytes(it); f.writeBytes(it) }
        }

        /** 车机的局域网 IPv4 地址，配对时显示出来给 iPhone 手动输入（Bonjour 不通时用）。蜂窝网的公网地址不显示。 */
        fun addresses(): List<String> = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nif -> nif.inetAddresses.toList().filter { it is Inet4Address && it.isSiteLocalAddress } }
            .mapNotNull { it.hostAddress }
    }
}
