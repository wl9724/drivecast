package org.drivecast.car.wireless

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.io.Closeable
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap

/**
 * 找 Android 11+ 手机"无线调试"的 mDNS 服务：[PAIRING]（配对窗口开着时才有）、[CONNECT]（端口每次打开都变）。
 * 实例名是手机的 GUID。只是系统 NsdManager 收发 mDNS，不扫网段；连不连由调用方按配对过的 GUID 决定。
 */
class AdbMdns(context: Context, type: String, private val onChange: (List<Service>) -> Unit = {}) : Closeable {
    data class Service(val name: String, val host: String, val port: Int)

    /** 实例名 → 解析出的 IPv4 地址和端口。 */
    val services = ConcurrentHashMap<String, Service>()

    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** Android 13 之前要自己拿着组播锁才收得到 mDNS。 */
    private val multicast = (app.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("drivecast-adb").apply { setReferenceCounted(false) }

    /** Android 14 之前同时只能解析一个服务（否则 FAILURE_ALREADY_ACTIVE），排队一个个来。 */
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) = synchronized(pending) {
            pending.addLast(info)
            if (!resolving) resolveNext()
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            if (services.remove(info.serviceName) != null) onChange(services.values.toList())
        }

        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    init {
        multicast.acquire()
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (e: Exception) {
            multicast.release()
            throw e
        }
    }

    /** 在 pending 锁里调用。 */
    @Suppress("DEPRECATION") // resolveService：API 34 起有新接口，老系统只有这个
    private fun resolveNext() {
        val info = pending.removeFirstOrNull()
        resolving = info != null
        if (info == null) return
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(s: NsdServiceInfo) {
                val ip = ipv4(s)
                if (ip != null) {
                    services[s.serviceName] = Service(s.serviceName, ip, s.port)
                    onChange(services.values.toList())
                }
                synchronized(pending) { resolveNext() }
            }

            override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) = synchronized(pending) { resolveNext() }
        }
        try {
            nsd.resolveService(info, listener)
        } catch (_: Exception) {
            resolveNext()
        }
    }

    @Suppress("DEPRECATION") // host：API 34 起换成 hostAddresses
    private fun ipv4(s: NsdServiceInfo): String? {
        val all = if (Build.VERSION.SDK_INT >= 34) s.hostAddresses else listOfNotNull(s.host)
        return all.firstOrNull { it is Inet4Address }?.hostAddress
    }

    override fun close() {
        runCatching { nsd.stopServiceDiscovery(discovery) }
        multicast.release()
    }

    companion object {
        const val PAIRING = "_adb-tls-pairing._tcp"
        const val CONNECT = "_adb-tls-connect._tcp"
    }
}
