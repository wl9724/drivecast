package org.drivecast.car

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 手机的蓝牙连上车机时自动打开 DriveCast：上车后手机一般先连上蓝牙，打开后连接线程立即开始找手机。
 * 只认手机类设备；30 分钟内只拉起一次，免得行车中蓝牙重连把正在用的导航挤到后台。
 * 车机 Android 12+ 要授予"附近的设备"权限才收得到这个广播，Android 10+ 要允许"显示在其他应用上层"才能从后台打开。
 */
class BluetoothLauncher : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val prefs = Prefs(context)
        if (!prefs.btAutoOpen) return
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val phone = runCatching { device?.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }
            .getOrDefault(false)
        if (!phone) return
        val now = System.currentTimeMillis()
        if (now - prefs.btOpenedAt in 0 until DEBOUNCE_MS) return
        prefs.btOpenedAt = now
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 30 * 60 * 1000L
    }
}
