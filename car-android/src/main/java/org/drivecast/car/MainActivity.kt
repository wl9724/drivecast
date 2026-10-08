package org.drivecast.car

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.drivecast.car.adb.AdbConnection
import org.drivecast.car.adb.AdbKey
import org.drivecast.car.adb.UsbTransport

/** P0 技术验证：通过 USB 与手机完成 ADB 握手，执行 shell:echo hello。 */
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private val usb by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect()
            else log("USB 权限被拒绝")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; setPadding(32, 32, 32, 32) }
        val button = Button(this).apply { text = "连接手机并执行 echo hello"; setOnClickListener { connect() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(button)
            addView(ScrollView(context).apply { addView(logView) })
        })

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(permissionReceiver, filter)
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        super.onDestroy()
    }

    private fun connect() {
        val device = usb.deviceList.values.firstOrNull { UsbTransport.findAdbInterface(it) != null }
        if (device == null) {
            log("没找到开启了 USB 调试的手机：用能传数据的线连接，并在手机开发者选项里打开 USB 调试")
            return
        }
        if (!usb.hasPermission(device)) {
            log("申请 USB 权限：${device.productName ?: device.deviceName}")
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usb.requestPermission(
                device,
                PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags),
            )
            return
        }
        Thread { runAdb(device) }.start()
    }

    private fun runAdb(device: UsbDevice) {
        try {
            val key = AdbKey.loadOrCreate(filesDir, "drivecast@${Build.MODEL}")
            AdbConnection(UsbTransport.open(usb, device), key) {
                log("请在手机上允许 USB 调试，建议勾选\"一律允许使用这台计算机进行调试\"")
            }.use { adb ->
                log("已连接：${adb.connect()}")
                log("echo hello → ${adb.shell("echo hello").trim()}")
                log("手机型号 → ${adb.shell("getprop ro.product.model").trim()}")
                log("Android 版本 → ${adb.shell("getprop ro.build.version.release").trim()}")
            }
        } catch (e: Exception) {
            log("失败：$e")
        }
    }

    private fun log(line: String) = runOnUiThread { logView.append("$line\n") }

    companion object {
        private const val ACTION_USB_PERMISSION = "org.drivecast.car.USB_PERMISSION"
    }
}
