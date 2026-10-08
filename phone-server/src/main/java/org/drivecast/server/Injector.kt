package org.drivecast.server

import android.hardware.input.InputManager
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import org.drivecast.protocol.Key
import org.drivecast.protocol.Touch

/** 把车机的触摸和按键注入到虚拟屏（shell 身份有 INJECT_EVENTS 权限）。 */
class Injector(private val displayId: Int) {
    private val manager: Class<*> =
        if (Build.VERSION.SDK_INT >= 34) Class.forName("android.hardware.input.InputManagerGlobal")
        else InputManager::class.java
    private val target = manager.getMethod("getInstance").invoke(null)!!
    private val inject = manager.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    private val setDisplayId = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    private var downTime = 0L

    fun touch(t: Touch) {
        val now = SystemClock.uptimeMillis()
        if (t.action == MotionEvent.ACTION_DOWN) downTime = now
        val e = MotionEvent.obtain(downTime, now, t.action, t.x.toFloat(), t.y.toFloat(), 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        send(e)
        e.recycle()
    }

    fun key(k: Key) {
        val now = SystemClock.uptimeMillis()
        send(
            KeyEvent(now, now, k.action, k.keycode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD),
        )
    }

    private fun send(e: InputEvent) {
        setDisplayId.invoke(e, displayId)
        inject.invoke(target, e, INJECT_ASYNC)
    }

    private companion object {
        const val INJECT_ASYNC = 0
    }
}
