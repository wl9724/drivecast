package org.drivecast.server

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.view.Surface

/** 以 shell 身份创建一块独立的横屏虚拟屏，应用在上面运行，手机自己的屏幕不受影响。 */
object VirtualDisplays {
    // DisplayManager.VIRTUAL_DISPLAY_FLAG_*，部分是隐藏常量
    private const val PUBLIC = 1
    private const val OWN_CONTENT_ONLY = 1 shl 3
    private const val SUPPORTS_TOUCH = 1 shl 6
    private const val ROTATES_WITH_CONTENT = 1 shl 7
    private const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    private const val TRUSTED = 1 shl 10
    private const val OWN_DISPLAY_GROUP = 1 shl 11
    private const val ALWAYS_UNLOCKED = 1 shl 12

    private const val BASE = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or
        ROTATES_WITH_CONTENT or DESTROY_CONTENT_ON_REMOVAL

    fun create(width: Int, height: Int, dpi: Int, surface: Surface): VirtualDisplay {
        val dm = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(ShellContext)
        // 可信 + 常驻解锁：手机锁屏时车机上仍能显示。权限不够就退回普通虚拟屏（手机需保持解锁）
        val extra = if (Build.VERSION.SDK_INT >= 33) TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED else 0
        return try {
            dm.createVirtualDisplay("drivecast", width, height, dpi, surface, BASE or extra)
        } catch (e: SecurityException) {
            println("可信虚拟屏不可用，退回普通虚拟屏：$e")
            dm.createVirtualDisplay("drivecast", width, height, dpi, surface, BASE)
        }
    }

    /** 冒充 com.android.shell（uid 2000）的最小 Context，DisplayManager 只用到包名和归属信息。 */
    private object ShellContext : ContextWrapper(null) {
        private const val SHELL_PACKAGE = "com.android.shell"
        private const val SHELL_UID = 2000

        override fun getPackageName() = SHELL_PACKAGE
        override fun getOpPackageName() = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
        override fun getDeviceId() = 0
    }
}
