package com.devoid.keysync.domain

import android.view.MotionEvent
import com.devoid.keysync.model.EventInjector
import com.devoid.keysync.model.KeyMap
import com.devoid.keysync.model.MultiModeTouchHandler

class TapModeTouchHandler(private val eventInjector: EventInjector) : MultiModeTouchHandler {
    override fun handleTouchEvent(
        action: Int,
        isPressed: Boolean,
        pointerId: Int,
        keyMap: KeyMap
    ): Boolean {
        if (action == MotionEvent.ACTION_DOWN) {
            eventInjector.injectPointer(pointerId, keyMap.position, keyMap.end!!)
            eventInjector.releasePointer(pointerId)
            return !isPressed
        }
        return isPressed
    }
}

class HoldModeTouchHandler(private val eventInjector: EventInjector) : MultiModeTouchHandler {
    override fun handleTouchEvent(
        action: Int,
        isPressed: Boolean,
        pointerId: Int,
        keyMap: KeyMap
    ): Boolean {
        return if (action == MotionEvent.ACTION_DOWN) {
            eventInjector.injectPointer(pointerId, keyMap.position, keyMap.end!!)
            true
        }else if (action == MotionEvent.ACTION_UP){
            eventInjector.releasePointer(pointerId)
            false
        }else
            isPressed
    }
}

class MixedModeTouchHandler(private val eventInjector: EventInjector) : MultiModeTouchHandler {
    private var lastKeyDownMap = hashMapOf<Int, Long>()
    private val minDifference = 200
    override fun handleTouchEvent(
        action: Int,
        isPressed: Boolean,
        pointerId: Int,
        keyMap: KeyMap
    ): Boolean {
        if (action == MotionEvent.ACTION_DOWN) {
            lastKeyDownMap[pointerId] = System.currentTimeMillis()
            if (isPressed) {
                eventInjector.releasePointer(pointerId)
            } else {
                eventInjector.injectPointer(pointerId, keyMap.position, keyMap.end!!)
            }
            return !isPressed
        } else if (action == MotionEvent.ACTION_UP) {
            if (!isPressed)
                return false
            val difference = System.currentTimeMillis() - (lastKeyDownMap[pointerId] ?: 0)
            if (difference > minDifference) {
//               eventInjector.injectPointer(pointerId, keyMap.position, keyMap.end!!)
                eventInjector.releasePointer(pointerId)
                return false
            }
        }
        return isPressed
    }

}
