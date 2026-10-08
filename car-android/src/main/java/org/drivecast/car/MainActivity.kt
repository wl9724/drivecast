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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.drivecast.car.adb.AdbConnection
import org.drivecast.car.adb.AdbKey
import org.drivecast.car.adb.UsbTransport
import org.drivecast.car.wireless.PhoneFinder
import org.drivecast.car.wireless.WirelessAdb
import org.drivecast.protocol.Hello
import kotlin.concurrent.thread

/**
 * 左侧一列按钮，右侧是手机虚拟屏的画面。
 * 画面在的时候后台线程一直尝试连接：插着线且有权限就走 USB，否则开了无线就在局域网里找手机。
 */
class MainActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var screen: SurfaceView
    private lateinit var status: TextView
    private lateinit var pauseButton: Button
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

    /** 只在 worker 线程上读写：同一台设备只弹一次权限框。 */
    private var askedPermissionFor: String? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = wake()
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
            addView(status)
        }
        screen = SurfaceView(this).apply {
            setOnTouchListener(::onScreenTouch)
            holder.addCallback(this@MainActivity)
        }
        setContentView(LinearLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(ScrollView(context).apply { addView(side) }, LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.MATCH_PARENT))
            addView(screen, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        })

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
        worker = thread(name = "connect") { runLoop() }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        target = null
        stopWorker() // 同步释放解码器，Surface 销毁后不能再往上渲染
    }

    private fun stopWorker() {
        val t = worker
        worker = null
        t?.interrupt()
        session?.let { runCatching { it.close() } }
        adb?.let { runCatching { it.close() } }
    }

    /** 等待中的连接线程立刻重试。投屏中不打断：无线切换时 USB 会重新枚举、再触发一次插入。 */
    private fun wake() {
        if (session == null) worker?.interrupt()
    }

    private fun runLoop() {
        val me = Thread.currentThread()
        while (worker === me) {
            if (!paused) {
                try {
                    connectOnce(me)
                } catch (_: InterruptedException) {
                } catch (e: Exception) {
                    log("已断开：${e.message ?: e}")
                }
            }
            if (worker !== me) break
            try {
                Thread.sleep(RETRY_MS)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun connectOnce(me: Thread) {
        val t = target ?: return
        val device = usb.deviceList.values.firstOrNull { UsbTransport.findAdbInterface(it) != null }
        if (device != null && usb.hasPermission(device)) return cast(me, t, connectUsb(device), viaUsb = true)

        if (prefs.wireless) {
            log("正在无线连接手机…")
            val found = PhoneFinder(this, key, ::log).find(prefs.phoneIps)
            if (found != null) {
                prefs.phoneIps = listOf(found.host) + prefs.phoneIps
                log("已无线连接 ${model(found.banner)}")
                return cast(me, t, found.adb, viaUsb = false)
            }
        }

        when {
            device != null -> {
                if (askedPermissionFor != device.deviceName) {
                    askedPermissionFor = device.deviceName
                    requestPermission(device)
                }
                log("请在车机上允许 DriveCast 访问手机")
            }
            prefs.wireless -> log("没找到手机：确认车机和手机连在同一个热点上。手机重启后需要插一次线")
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

    private fun cast(me: Thread, t: Target, c: AdbConnection, viaUsb: Boolean) {
        var s: CastSession? = null
        try {
            if (worker !== me) return
            this.viaUsb = viaUsb
            adb = c
            s = CastSession(c, t.surface, t.hello, ::log)
            session = s
            s.run(serverApk, APPS.first().second)
        } finally {
            s?.let { runCatching { it.close() } }
            if (session === s) session = null
            if (adb === c) adb = null
            c.close()
        }
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
        paused = !paused
        pauseButton.text = if (paused) "连接" else "断开"
        if (paused) {
            session?.let { runCatching { it.close() } }
            log("已断开")
        } else {
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
