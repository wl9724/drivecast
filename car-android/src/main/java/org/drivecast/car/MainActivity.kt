package org.drivecast.car

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.drivecast.car.adb.AdbConnection
import org.drivecast.car.adb.AdbKey
import org.drivecast.car.adb.UsbTransport
import org.drivecast.car.iphone.IphoneServer
import org.drivecast.car.iphone.PairingMode
import org.drivecast.car.iphone.SecureLink
import org.drivecast.car.wireless.AdbMdns
import org.drivecast.car.wireless.PhoneFinder
import org.drivecast.car.wireless.WirelessAdb
import org.drivecast.protocol.Hello
import org.drivecast.protocol.Msg
import org.drivecast.protocol.Pointer
import org.drivecast.protocol.Touch

/**
 * 左侧一列按钮，右侧是手机虚拟屏的画面。
 * 画面在的时候后台线程一直尝试连接：插着线且有权限就走 USB，否则开了无线就在局域网里找手机。
 * iPhone 反过来由它主动连车机，认证通过后顶替当前的投屏。
 */
class MainActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var screen: SurfaceView
    private lateinit var status: TextView
    private lateinit var pauseButton: Button
    private lateinit var btButton: Button
    private lateinit var pairingView: TextView
    private val pairing = PairingMode(onChange = { runOnUiThread(::showPairing) })
    private var iphoneServer: IphoneServer? = null

    /** 一直在找手机"无线调试"的连接服务；只连 GUID 是配对过的那些。 */
    private var tlsPhones: AdbMdns? = null

    /** 配对框里配对码留空时输入的无线调试地址（收不到 mDNS 时用）。端口每次打开无线调试都变，不存盘。 */
    @Volatile
    private var typedTls: AdbMdns.Service? = null

    private val usb by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val prefs by lazy { Prefs(this) }
    private val key by lazy { AdbKey.loadOrCreate(filesDir, "drivecast@${Build.MODEL}") }
    private val serverApk by lazy { assets.open("drivecast-server.apk").use { it.readBytes() } }

    private class Target(val surface: Surface, val hello: Hello)

    @Volatile
    private var target: Target? = null

    @Volatile
    private var session: CastSession? = null

    @Volatile
    private var adb: AdbConnection? = null

    /** 正在握手的 USB 连接：可能一直等手机上点"允许 USB 调试"，iPhone 顶替时要能关掉它。 */
    @Volatile
    private var connecting: AdbConnection? = null

    @Volatile
    private var viaUsb = false

    @Volatile
    private var paused = false

    @Volatile
    private var worker: Thread? = null

    /** 认证通过、等连接线程接手的 iPhone。 */
    @Volatile
    private var iphone: SecureLink? = null

    /** 同一台设备只主动弹一次权限框；点"断开"再点"连接"会清掉，重新弹。 */
    @Volatile
    private var askedPermissionFor: String? = null

    /** 权限框已经有结果（允许或拒绝）。没结果前一直提示"请允许"，不提示"没有权限"。 */
    @Volatile
    private var permissionAnswered = false

    /** worker / session / adb / iphone 的发布和检查放在一把锁里：旧线程不能覆盖新会话的状态。 */
    private val lock = Any()

    /**
     * 唤醒等待中的连接线程。不用 interrupt：中断标记会留到之后的阻塞调用里，
     * 把刚建好的连接打断。interrupt 只用来停掉线程。
     */
    private val wakeup = Semaphore(0)

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            permissionAnswered = true
            wake()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        status = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12f }
        pauseButton = button("断开") { togglePause() }
        btButton = button(btLabel()) { toggleBtAutoOpen() }
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            APPS.forEach { (label, pkg) -> addView(button(label) { session?.launch(pkg) }) }
            addView(button("返回") { session?.key(KeyEvent.KEYCODE_BACK) })
            addView(button("开启无线") { background { enableWireless() } })
            addView(button("关闭无线") { background { disableWireless() } })
            addView(button("无线配对") { pairDialog() })
            addView(pauseButton)
            addView(button("添加 iPhone/鸿蒙") { addIphone() })
            addView(button("清除 iPhone/鸿蒙 配对") { clearIphones() })
            addView(btButton)
            addView(status)
        }
        screen = SurfaceView(this).apply {
            setOnTouchListener(::onScreenTouch)
            holder.addCallback(this@MainActivity)
        }
        pairingView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xE6000000.toInt())
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener { pairing.close() }
        }
        setContentView(LinearLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(ScrollView(context).apply { addView(side) }, LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.MATCH_PARENT))
            addView(FrameLayout(context).apply {
                addView(screen)
                addView(pairingView)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        })
        iphoneServer = try {
            IphoneServer(this, prefs, pairing, ::log, ::onIphone)
        } catch (e: Exception) {
            log("iPhone/鸿蒙 服务启动失败：${e.message ?: e}")
            null
        }
        tlsPhones = runCatching { AdbMdns(this, AdbMdns.CONNECT) }.getOrNull()
        if (prefs.btAutoOpen) {
            askBluetoothPermission()
            if (!prefs.overlayAsked) {
                prefs.overlayAsked = true
                askOverlayPermission()
            }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(permissionReceiver, filter)
    }

    /** 开着但缺权限时标出来，免得看起来开着、实际不起作用；点一下关掉，再点打开会重新申请。 */
    private fun btLabel() = when {
        !prefs.btAutoOpen -> "蓝牙自动打开：关"
        Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
            Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(this) -> "蓝牙自动打开：开（未授权）"
        else -> "蓝牙自动打开：开"
    }

    private fun toggleBtAutoOpen() {
        prefs.btAutoOpen = !prefs.btAutoOpen
        btButton.text = btLabel()
        if (prefs.btAutoOpen) {
            askBluetoothPermission()
            askOverlayPermission()
        }
    }

    /** Android 12+：没有"附近的设备"权限收不到蓝牙连接广播。 */
    private fun askBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BLUETOOTH)
        }
    }

    /** Android 10+：后台的 App 不能自己打开界面，除非允许"显示在其他应用上层"。只在用户打开开关时引导一次。 */
    private fun askOverlayPermission() {
        if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(this)) {
            tell("请允许 DriveCast \"显示在其他应用上层\"，否则蓝牙连上时打不开")
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        }
    }

    override fun onResume() {
        super.onResume()
        btButton.text = btLabel() // 从权限框或设置页回来时刷新
    }

    /** 插上手机时系统通过 USB_DEVICE_ATTACHED 拉起本页面（singleTask），并已授予 USB 权限。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        wake()
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        iphoneServer?.close()
        tlsPhones?.close()
        stopWorker()
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // 编码器要求宽高是 16 的倍数；车机按比例缩放显示，误差不到 16 像素
        val hello = Hello(width / 16 * 16, height / 16 * 16, resources.displayMetrics.densityDpi, 30, 4_000_000)
        target = Target(holder.surface, hello)
        // 尺寸变了要按新尺寸重建虚拟屏
        stopWorker()
        // 先登记再启动：否则新线程可能在 worker 赋值前检查 worker === me，直接退出
        val t = thread(start = false, name = "connect") { runLoop() }
        synchronized(lock) { worker = t }
        t.start()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        target = null
        stopWorker() // 同步释放解码器，Surface 销毁后不能再往上渲染
    }

    private fun stopWorker() {
        val closing = synchronized(lock) {
            worker?.interrupt()
            worker = null
            listOfNotNull(session, adb, connecting, iphone).also { iphone = null }
        }
        closing.forEach { runCatching { it.close() } }
    }

    /** 等待中的连接线程立刻重试。投屏中的不受影响：无线切换时 USB 会重新枚举、再触发一次插入。 */
    private fun wake() = wakeup.release()

    private fun runLoop() {
        val me = Thread.currentThread()
        while (worker === me) {
            try {
                val phone = synchronized(lock) { iphone.also { iphone = null } }
                if (phone != null) play(me, phone, null)
                else if (!paused) connectOnce(me)
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                log("已断开：${e.message ?: e}")
            }
            if (worker !== me) break
            try {
                wakeup.tryAcquire(RETRY_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
            wakeup.drainPermits() // 多次唤醒只算一次；先等后清，等待前到的唤醒不会丢
        }
    }

    private fun connectOnce(me: Thread) {
        if (target == null) return
        val device = usb.deviceList.values.firstOrNull { UsbTransport.findAdbInterface(it) != null }
        if (device != null && usb.hasPermission(device)) return cast(me, connectUsb(me, device), viaUsb = true)

        var finder: PhoneFinder? = null
        if (prefs.wireless) {
            log("正在无线连接手机…")
            finder = PhoneFinder(this, key, ::log)
            val found = finder.find(prefs.phoneIps)
            if (found != null) {
                prefs.phoneIps = listOf(found.host) + prefs.phoneIps
                log("已无线连接 ${model(found.banner)}")
                return cast(me, found.adb, viaUsb = false)
            }
            // 配对过的手机开着"无线调试"：直接经 TLS 投屏，地址不进 phoneIps。插着线时先走下面的 USB 授权
            val paired = prefs.pairedPhones
            val tls = if (device == null) tlsPhones?.services?.values?.firstOrNull { it.name in paired } ?: typedTls else null
            if (tls != null) {
                val phone = try {
                    finder.connectTls(tls.host, tls.port)
                } catch (e: Exception) {
                    return log("通过无线调试连接失败：${e.message ?: e}")
                }
                log("已通过无线调试连接 ${model(phone.banner)}")
                return cast(me, phone.adb, viaUsb = false)
            }
        }

        when {
            device != null && askedPermissionFor != device.deviceName -> {
                askedPermissionFor = device.deviceName
                permissionAnswered = false
                requestPermission(device)
                log("请在车机上允许 DriveCast 访问手机")
            }
            device != null && !permissionAnswered -> log("请在车机上允许 DriveCast 访问手机")
            device != null -> log("没有 USB 权限：点\"断开\"再点\"连接\"重新申请，或重新插拔数据线")
            finder?.unauthorized == true -> log("手机不再信任这台车机（7 天没连或没勾\"一律允许\"）：请插线连接一次")
            prefs.wireless -> log("没找到手机：确认车机和手机连在同一个 Wi-Fi 或热点上。手机重启后要插一次线，无线配对过的打开\"无线调试\"即可")
            else -> log("用数据线连接手机，并在手机开发者选项里打开 USB 调试")
        }
    }

    private fun connectUsb(me: Thread, device: UsbDevice): AdbConnection {
        val c = AdbConnection(UsbTransport.open(usb, device), key) {
            log("请在手机上允许 USB 调试，并勾选\"一律允许\"")
        }
        try {
            // 先登记再握手：没人点"允许"时 connect() 一直阻塞，iPhone 顶替 / stopWorker 关掉它才能返回
            synchronized(lock) {
                if (!mayCast(me)) throw IOException("已取消")
                connecting = c
            }
            log("已连接 ${model(c.connect())}（有线）")
        } catch (e: Exception) {
            c.close()
            throw e
        } finally {
            synchronized(lock) { if (connecting === c) connecting = null }
        }
        return c
    }

    private fun cast(me: Thread, c: AdbConnection, viaUsb: Boolean) {
        try {
            synchronized(lock) {
                if (!mayCast(me)) return
                this.viaUsb = viaUsb
                adb = c
            }
            play(me, AdbLink(c, serverApk, ::log), APPS.first().second)
        } finally {
            synchronized(lock) { if (adb === c) adb = null }
            c.close()
        }
    }

    /** 在当前画面上跑一次投屏，直到断开。 */
    private fun play(me: Thread, link: FrameLink, firstApp: String?) {
        val t = target ?: return link.close()
        val s = CastSession(link, t.surface, t.hello, ::log)
        try {
            // 检查和发布在同一把锁里：要么 stopWorker / 断开 / iPhone 顶替 看到这个会话并关掉它，要么这里看到标记退出
            synchronized(lock) {
                if (!mayCast(me)) return
                session = s
            }
            s.run(firstApp)
        } finally {
            runCatching { s.close() }
            synchronized(lock) { if (session === s) session = null }
        }
    }

    /** 在 lock 里调用。有 iPhone 在等就让路：它已经通过认证，优先。 */
    private fun mayCast(me: Thread) = worker === me && !paused && iphone == null

    /**
     * 认证通过的 iPhone 顶替当前投屏：关掉当前会话，让连接线程接手。
     * 陌生人过不了认证，挤不掉正在用的投屏。没有画面（App 在后台）或点了"断开"时不接。
     * 另一台 iPhone 正在投（或等着接手）时也不接：两台都在直播的话互相顶替，谁都看不成。
     * 同一台 iPhone 重连（如 Wi-Fi 闪断）照常顶替。
     */
    private fun onIphone(link: SecureLink) {
        var busy = false
        val closing: List<Closeable> = synchronized(lock) {
            busy = listOfNotNull(iphone, session?.link).any { it is SecureLink && !it.phoneId.contentEquals(link.phoneId) }
            if (worker == null || paused || busy) listOf(link)
            else listOfNotNull(iphone, session, adb, connecting).also { iphone = link }
        }
        // 只有本线程用过这条新连接，加密计数器不会乱。iPhone 收到后退避重试，等那台停了再连上
        if (busy) runCatching { link.write(Msg.BYE, "车机正在显示另一台手机".toByteArray(Charsets.UTF_8)) }
        closing.forEach { runCatching { it.close() } }
        if (paused) log("手机想要投屏：先点\"连接\"")
        wake()
    }

    private fun addIphone() {
        if (iphoneServer == null) return log("iPhone/鸿蒙 服务没有启动")
        pairing.open()
        pairingView.postDelayed(::showPairing, PairingMode.WINDOW_MS + 500) // 到期后收起
        log("配对模式已打开 2 分钟。车机地址：${addresses().joinToString(" ")}")
    }

    /** 配对模式下盖在画面上：等 iPhone 时显示车机地址（Bonjour 不通时手动输入），开始配对后显示配对码。 */
    private fun showPairing() {
        val code = pairing.code
        pairingView.visibility = if (code != null || pairing.active) View.VISIBLE else View.GONE
        if (code != null) {
            pairingView.textSize = 64f
            pairingView.text = "配对码\n%06d".format(code)
        } else if (pairing.active) {
            pairingView.textSize = 24f
            pairingView.text = "在 iPhone / 鸿蒙手机的 DriveCast 里添加车机\n\n找不到车机时手动输入：\n" +
                addresses().joinToString("\n") + "\n\n（点这里取消）"
        }
    }

    private fun addresses() = IphoneServer.addresses().map { "$it:${iphoneServer?.port}" }

    private fun clearIphones() {
        prefs.clearIphones()
        // 正在投屏的 iPhone 也不再信任
        val closing = synchronized(lock) {
            listOfNotNull(iphone, session?.takeIf { it.link is SecureLink }).also { iphone = null }
        }
        closing.forEach { runCatching { it.close() } }
        log("已清除所有 iPhone / 鸿蒙手机的配对")
    }

    private fun enableWireless() {
        val c = adb
        when {
            c == null -> log("请先用数据线连接手机")
            !viaUsb -> log("已经是无线连接")
            else -> {
                log("正在开启无线…")
                val ips = WirelessAdb.enable(c)
                prefs.phoneIps = ips + prefs.phoneIps
                prefs.wireless = true
                log("已开启无线：车机和手机连到同一个热点后就可以拔线了")
            }
        }
    }

    private fun disableWireless() {
        prefs.wireless = false
        val c = adb
        if (c != null) {
            WirelessAdb.disable(c)
            log("已关闭手机的无线调试")
        } else {
            log("已停止无线连接。手机上的无线调试要插线后再点一次，或重启手机才会关闭")
        }
    }

    /**
     * Android 11+ 不插线：手机"开发者选项 → 无线调试 → 使用配对码配对"，车机上选中（或输入）手机显示的地址、输入配对码。
     * 配对码留空 = 已经配对过，直接连手机"无线调试"页面上的地址（车机收不到 mDNS 时用）。
     */
    private fun pairDialog() {
        val addr = EditText(this).apply {
            hint = "IP 地址:端口"
            setSingleLine()
        }
        val code = EditText(this).apply {
            hint = "6 位配对码"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val found = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val mdns = runCatching {
            AdbMdns(this, AdbMdns.PAIRING) { list ->
                runOnUiThread {
                    found.removeAllViews()
                    list.forEach { s -> found.addView(button("${s.host}:${s.port}") { addr.setText("${s.host}:${s.port}") }) }
                    if (list.size == 1 && addr.text.isEmpty()) addr.setText("${list[0].host}:${list[0].port}")
                }
            }
        }.getOrNull()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(TextView(context).apply {
                text = "手机连车机的热点（或和车机连同一个 Wi-Fi），打开 开发者选项 → 无线调试 → 使用配对码配对，" +
                    "配对窗口不要关。下面会列出找到的手机，点一下填入地址；没有就手动输入手机上显示的 IP 地址和端口。"
            })
            addView(found)
            addView(addr)
            addView(code)
        }
        AlertDialog.Builder(this)
            .setTitle("无线配对（Android 11 及以上）")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("配对") { _, _ -> pair(addr.text.toString(), code.text.toString()) }
            .setNegativeButton("取消", null)
            .setOnDismissListener { mdns?.close() }
            .show()
    }

    private fun pair(address: String, code: String) {
        val host = address.substringBeforeLast(':').trim()
        val port = address.substringAfterLast(':', "").trim().toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) return tell("地址的格式是 IP:端口，如 192.168.43.20:37145")
        if (code.isNotEmpty() && !(code.length == 6 && code.all { it in '0'..'9' })) return tell("配对码是 6 位数字")
        // 网络操作不在界面线程；不碰投屏会话，不用拿 lock
        thread {
            tell(
                try {
                    pairAndEnable(host, port, code)
                } catch (e: Exception) {
                    "失败：${e.message ?: e}"
                },
            )
        }
    }

    /** 状态栏几秒后就会被连接线程的提示盖掉：配对的结果另弹 Toast。 */
    private fun tell(msg: String) {
        log(msg)
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    /** 配对（配对码不为空时）后交给连接线程经 TLS 连接，返回给用户看的结果。 */
    private fun pairAndEnable(host: String, port: Int, code: String): String {
        var result = "正在通过无线调试连接…"
        if (code.isEmpty()) {
            typedTls = AdbMdns.Service("", host, port)
        } else {
            log("正在配对…")
            val guid = PhoneFinder(this, key, ::log).pair(host, port, code)
            prefs.pairedPhones = listOf(guid) + prefs.pairedPhones
            log("配对成功，正在找手机的无线调试端口…")
            if (connectService(guid) == null) {
                result = "配对成功，但没找到手机的无线调试端口：把手机\"无线调试\"页面上的 IP 地址和端口填进来、配对码留空再试"
            }
        }
        prefs.wireless = true
        wake()
        return result
    }

    /** 连接端口和配对端口不同，只能从 mDNS 找（实例名就是 GUID），最多等 10 秒。 */
    private fun connectService(guid: String): AdbMdns.Service? {
        repeat(20) {
            tlsPhones?.services?.get(guid)?.let { return it }
            Thread.sleep(500)
        }
        return null
    }

    private fun togglePause() {
        val closing: List<Closeable> = synchronized(lock) {
            paused = !paused
            // 连同正在建立的连接一起关：否则推送、启动手机端服务会跑完才停
            if (paused) listOfNotNull(session, adb, connecting) else emptyList()
        }
        pauseButton.text = if (paused) "连接" else "断开"
        if (paused) {
            closing.forEach { runCatching { it.close() } }
            log("已断开")
        } else {
            askedPermissionFor = null
            wake()
        }
    }

    private fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(
            device,
            PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags),
        )
    }

    /** 每个事件带上所有按着的手指，换算成视频像素坐标（双指缩放地图等）。 */
    private fun onScreenTouch(v: View, e: MotionEvent): Boolean {
        val s = session ?: return false
        if (e.actionMasked !in TOUCH_ACTIONS) return true
        val w = screen.width / 16 * 16
        val h = screen.height / 16 * 16
        // 所有手指都发：只截掉一部分会让手机看到没按下过的手指，Android 16 会因此拒绝之后的全部触摸
        val pointers = List(e.pointerCount) { i ->
            Pointer(
                e.getPointerId(i),
                (e.getX(i) * w / v.width).toInt().coerceIn(0, w - 1),
                (e.getY(i) * h / v.height).toInt().coerceIn(0, h - 1),
            )
        }
        s.touch(Touch(e.actionMasked, e.getPointerId(e.actionIndex), pointers))
        return true
    }

    private fun background(block: () -> Unit) = thread {
        try {
            block()
        } catch (e: Exception) {
            log("失败：${e.message ?: e}")
        }
    }

    private fun model(banner: String) = banner.substringAfter("ro.product.model=").substringBefore(';')

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun log(line: String) = runOnUiThread { status.text = line }

    private companion object {
        const val REQ_BLUETOOTH = 1
        const val ACTION_USB_PERMISSION = "org.drivecast.car.USB_PERMISSION"
        const val RETRY_MS = 3_000L

        val TOUCH_ACTIONS = setOf(
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP,
        )

        /** 连接后先打开第一个。 */
        val APPS = listOf(
            "高德地图" to "com.autonavi.minimap",
            "百度地图" to "com.baidu.BaiduMap",
            "QQ音乐" to "com.tencent.qqmusic",
            "网易云音乐" to "com.netease.cloudmusic",
        )
    }
}
