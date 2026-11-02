package com.devoid.keysync.domain.touch

import android.annotation.SuppressLint
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.view.MotionEvent

/** Encodes display-logical pixels. Android InputReader rotation is NOT MuMu raw ABS rotation. */
class FrameworkTouchBackend(
    private val inject: (InputEvent, Boolean) -> Boolean,
    private val cancelStream: () -> Boolean
) : TouchBackend {
    @SuppressLint("PrivateApi")
    private val setDisplayId = runCatching {
        MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }.getOrNull()

    override fun submit(frame: TouchFrame): Boolean {
        val action = when (frame.action) {
            TouchAction.DOWN -> MotionEvent.ACTION_DOWN
            TouchAction.POINTER_DOWN -> MotionEvent.ACTION_POINTER_DOWN or
                (frame.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            TouchAction.MOVE -> MotionEvent.ACTION_MOVE
            TouchAction.POINTER_UP -> MotionEvent.ACTION_POINTER_UP or
                (frame.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            TouchAction.UP -> MotionEvent.ACTION_UP
            TouchAction.CANCEL -> MotionEvent.ACTION_CANCEL
        }
        val properties = frame.contacts.map { contact -> MotionEvent.PointerProperties().apply {
            id = contact.pointerId; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coordinates = frame.contacts.map { contact -> MotionEvent.PointerCoords().apply {
            x = contact.position.x.toFloat(); y = contact.position.y.toFloat(); pressure = 1f; size = 1f
        } }.toTypedArray()
        val event = MotionEvent.obtain(frame.downTime, frame.eventTime, action, frame.contacts.size,
            properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        return try {
            if (setDisplayId != null) setDisplayId.invoke(event, frame.displayId)
            else if (frame.displayId != 0) return false // Do not inject onto the wrong screen.
            // Conservative edge acceptance; ASYNC MOVE. Neither means game delivery.
            inject(event, frame.action != TouchAction.MOVE)
        } finally { event.recycle() }
    }

    override fun cancel(): Boolean = cancelStream()

    override fun key(key: SimulatedKey, pressed: Boolean, downTime: Long, eventTime: Long): Boolean =
        inject(KeyEvent(downTime, eventTime, if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            key.code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, key.scanCode, KeyEvent.FLAG_FROM_SYSTEM,
            InputDevice.SOURCE_KEYBOARD), true)
}
