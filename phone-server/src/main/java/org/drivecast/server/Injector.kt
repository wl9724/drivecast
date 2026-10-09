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

    private val props = Array(Touch.MAX_POINTERS) { MotionEvent.PointerProperties() }
    private val coords = Array(Touch.MAX_POINTERS) { MotionEvent.PointerCoords() }

    /** 车机的一次触摸 → 一个多指 MotionEvent。第二根手指按下/抬起要把它在数组里的下标编进 action。 */
    fun touch(t: Touch) {
        val now = SystemClock.uptimeMillis()
        if (t.action == MotionEvent.ACTION_DOWN) downTime = now
        var action = t.action
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            val index = t.pointers.indexOfFirst { it.id == t.actionId }
            if (index < 0) return
            action = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        t.pointers.forEachIndexed { i, p ->
            props[i].clear()
            props[i].id = p.id
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER
            coords[i].clear()
            coords[i].x = p.x.toFloat()
            coords[i].y = p.y.toFloat()
            coords[i].pressure = 1f
            coords[i].size = 1f
        }
        val e = MotionEvent.obtain(
            downTime, now, action, t.pointers.size, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
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
