package com.devoid.keysync.data.external

import android.util.Log

internal object RootVirtualGamepadNative : VirtualGamepadSink {
    init {
        System.loadLibrary("keysync_root")
    }

    external fun nativeCreate(): Int
    external fun nativeDestroy()
    private external fun nativeSetLeftStick(x: Int, y: Int): Boolean
    private external fun nativeSetRightStick(x: Int, y: Int): Boolean
    private external fun nativeSetButton(linuxCode: Int, pressed: Boolean): Boolean
    private external fun nativeReset(): Boolean

    override fun setLeftStick(position: StickPosition) {
        logWriteFailure("left stick", nativeSetLeftStick(position.x, position.y))
    }

    override fun setRightStick(position: StickPosition) {
        logWriteFailure("right stick", nativeSetRightStick(position.x, position.y))
    }

    override fun setButton(button: GamepadButton, pressed: Boolean) {
        logWriteFailure("button ${button.name}", nativeSetButton(button.linuxCode, pressed))
    }

    override fun reset() {
        logWriteFailure("neutral state", nativeReset())
    }

    private fun logWriteFailure(operation: String, succeeded: Boolean) {
        if (!succeeded) Log.w(TAG, "Unable to write virtual gamepad $operation")
    }

    private const val TAG = "RootVirtualGamepad"
}
