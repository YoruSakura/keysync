package com.devoid.keysync.model

interface MultiModeTouchHandler {
    //return if pointer is pressed
    fun handleTouchEvent(
        action: Int,
        isPressed: Boolean,
        pointerId: Int,
        keyMap: KeyMap
    ): Boolean
}
