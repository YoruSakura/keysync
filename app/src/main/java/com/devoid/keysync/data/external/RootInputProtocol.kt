package com.devoid.keysync.data.external

import android.os.Binder

internal object RootInputProtocol {
    const val DESCRIPTOR = "com.devoid.keysync.IRootInputService"
    const val TRANSACTION_PING = Binder.FIRST_CALL_TRANSACTION
    const val TRANSACTION_INJECT = Binder.FIRST_CALL_TRANSACTION + 1
    const val TRANSACTION_CANCEL_CURRENT_TOUCH = Binder.FIRST_CALL_TRANSACTION + 2
    const val TRANSACTION_START_CAPTURE = Binder.FIRST_CALL_TRANSACTION + 3
    const val TRANSACTION_STOP_CAPTURE = Binder.FIRST_CALL_TRANSACTION + 4
    const val TRANSACTION_GET_FOCUSED_PACKAGE = Binder.FIRST_CALL_TRANSACTION + 5

    const val CALLBACK_DESCRIPTOR = "com.devoid.keysync.IRootInputCallback"
    const val CALLBACK_KEY = Binder.FIRST_CALL_TRANSACTION
    const val CALLBACK_MOUSE_BUTTON = Binder.FIRST_CALL_TRANSACTION + 1
    const val CALLBACK_MOUSE_MOVE = Binder.FIRST_CALL_TRANSACTION + 2
    const val CALLBACK_INPUT_RESET = Binder.FIRST_CALL_TRANSACTION + 3
    const val CALLBACK_CAPTURE_STATE = Binder.FIRST_CALL_TRANSACTION + 4
    const val CALLBACK_MOUSE_SCROLL = Binder.FIRST_CALL_TRANSACTION + 5

    const val CAPTURE_STATE_IDLE = 0
    const val CAPTURE_STATE_ACTIVE = 1
    const val CAPTURE_STATE_FAILED = 2

    const val MAX_CAPTURE_DEVICE_IDENTITIES = 64

    const val INJECT_MODE_ASYNC = 0
    const val INJECT_MODE_WAIT_FOR_RESULT = 1
}

internal data class RootInputDeviceIdentity(
    val name: String,
    val vendorId: Int,
    val productId: Int
)
