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
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.drivecast.car.adb.AdbConnection
import org.drivecast.car.adb.AdbKey
import org.drivecast.car.adb.UsbTransport
import org.drivecast.protocol.Hello
import kotlin.concurrent.thread

/** 左侧一列按钮，右侧是手机虚拟屏的画面。 */
class MainActivity : Activity() {

    private lateinit var screen: SurfaceView
    private lateinit var status: TextView
    private val usb by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }

    @Volatile
    private var session: CastSession? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect()
            else log("USB 权限被拒绝")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        status = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12f }
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            addView(button("连接手机") { connect() })
            APPS.forEach { (label, pkg) -> addView(button(label) { session?.launch(pkg) }) }
            addView(button("返回") { session?.key(KeyEvent.KEYCODE_BACK) })
            addView(button("断开") { disconnect() })
            addView(status)
        }
        screen = SurfaceView(this).apply { setOnTouchListener(::onScreenTouch) }
        setContentView(LinearLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(side, LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.MATCH_PARENT))
            addView(screen, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        })

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(permissionReceiver, filter)
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        disconnect()
        super.onDestroy()
    }

    private fun connect() {
        if (session != null) return log("已在投屏中")
        val device = usb.deviceList.values.firstOrNull { UsbTransport.findAdbInterface(it) != null }
            ?: return log("没找到开启了 USB 调试的手机：用能传数据的线连接，并在手机开发者选项里打开 USB 调试")
        if (!usb.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usb.requestPermission(
                device,
                PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags),
            )
            return
        }
        // 编码器要求宽高是 16 的倍数；车机按比例缩放显示，误差不到 16 像素
        val hello = Hello(screen.width / 16 * 16, screen.height / 16 * 16, resources.displayMetrics.densityDpi, 30, 4_000_000)
        val surface = screen.holder.surface
        thread(name = "cast") { cast(device, hello, surface) }
    }

    private fun cast(device: UsbDevice, hello: Hello, surface: Surface) {
        try {
            val key = AdbKey.loadOrCreate(filesDir, "drivecast@${Build.MODEL}")
            val adb = AdbConnection(UsbTransport.open(usb, device), key) {
                log("请在手机上允许 USB 调试，并勾选\"一律允许\"")
            }
            log("已连接 ${adb.connect().substringAfter("ro.product.model=").substringBefore(';')}")
            val s = CastSession(adb, surface, hello, ::log)
            session = s
            s.run(assets.open("drivecast-server.apk").use { it.readBytes() }, APPS.first().second)
        } catch (e: Exception) {
            log("已断开：${e.message ?: e}")
        } finally {
            disconnect()
        }
    }

    private fun disconnect() {
        session?.let { runCatching { it.close() } }
        session = null
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

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun log(line: String) = runOnUiThread { status.text = line }

    private companion object {
        const val ACTION_USB_PERMISSION = "org.drivecast.car.USB_PERMISSION"

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
