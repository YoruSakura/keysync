package com.devoid.keysync.data.external

interface RootInputListener {
    fun onKeyEvent(keyCode: Int, pressed: Boolean)
    fun onMouseButton(button: Int, pressed: Boolean)
    fun onMouseMove(deltaX: Int, deltaY: Int)
    fun onMouseScroll(verticalDeltaUnits: Int)
    fun onInputReset()
    fun onCaptureStateChanged(status: RootInputConnection.CaptureStatus)
}
