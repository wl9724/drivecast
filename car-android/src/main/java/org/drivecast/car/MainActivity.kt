package org.drivecast.car

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.drivecast.car.adb.AdbConnection
import org.drivecast.car.adb.AdbKey
import org.drivecast.car.adb.UsbTransport
import org.drivecast.car.iphone.IphoneServer
import org.drivecast.car.iphone.PairingMode
import org.drivecast.car.iphone.SecureLink
import org.drivecast.car.wireless.PhoneFinder
import org.drivecast.car.wireless.WirelessAdb
import org.drivecast.protocol.Hello
import java.io.Closeable
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 左侧一列按钮，右侧是手机虚拟屏的画面。
 * 画面在的时候后台线程一直尝试连接：插着线且有权限就走 USB，否则开了无线就在局域网里找手机。
 * iPhone 反过来由它主动连车机，认证通过后顶替当前的投屏。
 */
class MainActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var screen: SurfaceView
    private lateinit var status: TextView
    private lateinit var pauseButton: Button
    private lateinit var pairingView: TextView
    private val pairing = PairingMode(onChange = { runOnUiThread(::showPairing) })
    private var iphoneServer: IphoneServer? = null
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
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            APPS.forEach { (label, pkg) -> addView(button(label) { session?.launch(pkg) }) }
            addView(button("返回") { session?.key(KeyEvent.KEYCODE_BACK) })
            addView(button("开启无线") { background { enableWireless() } })
            addView(button("关闭无线") { background { disableWireless() } })
            addView(pauseButton)
            addView(button("添加 iPhone") { addIphone() })
            addView(button("清除 iPhone 配对") { clearIphones() })
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
            log("iPhone 服务启动失败：${e.message ?: e}")
            null
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(permissionReceiver, filter)
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
            listOfNotNull(session, adb, iphone).also { iphone = null }
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
        if (device != null && usb.hasPermission(device)) return cast(me, connectUsb(device), viaUsb = true)

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
            prefs.wireless -> log("没找到手机：确认车机和手机连在同一个 Wi-Fi 或热点上。手机重启后需要插一次线")
            else -> log("用数据线连接手机，并在手机开发者选项里打开 USB 调试")
        }
    }

    private fun connectUsb(device: UsbDevice): AdbConnection {
        val c = AdbConnection(UsbTransport.open(usb, device), key) {
            log("请在手机上允许 USB 调试，并勾选\"一律允许\"")
        }
        try {
            log("已连接 ${model(c.connect())}（有线）")
        } catch (e: Exception) {
            c.close()
            throw e
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
     */
    private fun onIphone(link: SecureLink) {
        val closing: List<Closeable> = synchronized(lock) {
            if (worker == null || paused) listOf(link)
            else listOfNotNull(iphone, session, adb).also { iphone = link }
        }
        closing.forEach { runCatching { it.close() } }
        wake()
    }

    private fun addIphone() {
        if (iphoneServer == null) return log("iPhone 服务没有启动")
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
            pairingView.text = "在 iPhone 的 DriveCast 里添加车机\n\n找不到车机时手动输入：\n" +
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
        log("已清除所有 iPhone 的配对")
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

    private fun togglePause() {
        val s = synchronized(lock) {
            paused = !paused
            if (paused) session else null
        }
        pauseButton.text = if (paused) "连接" else "断开"
        if (paused) {
            s?.let { runCatching { it.close() } }
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

    private fun onScreenTouch(v: View, e: MotionEvent): Boolean {
        val s = session ?: return false
        // ponytail: v1 只传单指（忽略第二根手指的按下/抬起），双指缩放要扩展协议
        if (e.actionMasked !in TOUCH_ACTIONS) return true
        val w = screen.width / 16 * 16
        val h = screen.height / 16 * 16
        val x = (e.getX(0) * w / v.width).toInt().coerceIn(0, w - 1)
        val y = (e.getY(0) * h / v.height).toInt().coerceIn(0, h - 1)
        s.touch(e.actionMasked, x, y)
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
        const val ACTION_USB_PERMISSION = "org.drivecast.car.USB_PERMISSION"
        const val RETRY_MS = 3_000L

        val TOUCH_ACTIONS = setOf(
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL,
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
