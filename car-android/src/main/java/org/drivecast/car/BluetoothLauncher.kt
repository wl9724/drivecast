package org.drivecast.car

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * 手机的蓝牙连上车机时自动打开 DriveCast：上车后手机一般先连上蓝牙，打开后连接线程立即开始找手机。
 * 只认手机类设备；同一次开机 30 分钟内只拉起一次，免得行车中蓝牙重连把正在用的导航挤到后台。
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
        // 用开机后的单调时钟、只记在内存里：车机校时不影响；熄火重启后进程是新的，上车总能打开
        val now = SystemClock.elapsedRealtime()
        if (openedAt != 0L && now - openedAt < DEBOUNCE_MS) return
        openedAt = now
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 30 * 60 * 1000L

        // ponytail: 进程被杀会清零，行车中可能多弹一次；真遇到再按开机次数持久化
        @Volatile
        var openedAt = 0L
    }
}
