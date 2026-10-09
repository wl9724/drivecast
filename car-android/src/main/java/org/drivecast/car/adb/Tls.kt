package org.drivecast.car.adb

import org.conscrypt.Conscrypt
import java.io.IOException
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Android 11+ 无线调试（配对和连接）用的 TLS 1.3。车机系统 Android 10 之前没有 TLS 1.3，
 * 公开的 exporter 接口 API 31 才有，所以所有版本都用打包的 Conscrypt，不碰系统的 provider。
 */
object Tls {
    private val provider by lazy {
        try {
            Conscrypt.newProvider()
        } catch (e: LinkageError) { // 没有这个 CPU 架构的 so（只打包了 ARM）
            throw IOException("TLS 库加载失败：${e.message}")
        }
    }

    /**
     * 在已连上的 [raw] 上握手。客户端证书永远是车机 ADB 密钥的自签证书（手机发来的 CA 列表不管）：
     * adbd 只认公钥，没有出示证书会被直接拒绝。[client] 为 false 只给测试里的假手机用。
     */
    fun handshake(raw: Socket, key: AdbKey, client: Boolean = true): SSLSocket {
        // 每次新建：不复用会话，每次都完整握手、出示证书
        val ctx = SSLContext.getInstance("TLSv1.3", provider)
        ctx.init(arrayOf(KeyManager(key)), arrayOf(TrustAll), null)
        var factory = ctx.socketFactory
        // Android 5.0（API 21）上 Conscrypt 给工厂包了一层兼容系统旧接口，包出来的 socket 导不出密钥材料：
        // 取里面真正的工厂。字段名随打包的版本固定，Conscrypt 自带的混淆规则也保留它
        if (!Conscrypt.isConscrypt(factory)) {
            factory = factory.javaClass.superclass!!.getDeclaredField("delegate")
                .apply { isAccessible = true }.get(factory) as SSLSocketFactory
        }
        // 引擎版 socket 只用底层 socket 的流，不靠反射拿文件描述符（新系统禁止）
        Conscrypt.setUseEngineSocket(factory, true)
        val s = factory.createSocket(raw, raw.inetAddress.hostAddress, raw.port, true) as SSLSocket
        try {
            s.useClientMode = client
            if (!client) s.needClientAuth = true
            s.enabledProtocols = arrayOf("TLSv1.3") // adbd 只接受 1.3
            s.startHandshake()
        } catch (e: IOException) {
            s.close()
            throw IOException("TLS 握手失败：${e.message}", e)
        }
        return s
    }

    /** RFC 8446 7.5 的 exporter：标签带结尾的 \0（AOSP 用 sizeof），无 context，64 字节。 */
    fun exportKey(s: SSLSocket): ByteArray = Conscrypt.exportKeyingMaterial(s, "adb-label\u0000", null, 64)

    private class KeyManager(key: AdbKey) : X509ExtendedKeyManager() {
        private val chain = arrayOf(AdbCert.of(key.keyPair))
        private val pk = key.keyPair.private

        override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, s: Socket?) = ALIAS
        override fun chooseEngineClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, e: SSLEngine?) = ALIAS
        override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, s: Socket?) = ALIAS
        override fun chooseEngineServerAlias(type: String?, issuers: Array<out Principal>?, e: SSLEngine?) = ALIAS
        override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getServerAliases(type: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain
        override fun getPrivateKey(alias: String?): PrivateKey = pk
    }

    /**
     * 不校验对方证书：手机的 adbd 每次启动都随机生成一把密钥，配对窗口也是，没有可以校验的东西。
     * 安全由另一头保证：连接时手机校验车机证书（握手签名绑定这次连接，转发不了），配对时靠配对码。
     */
    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private const val ALIAS = "adb"
}
