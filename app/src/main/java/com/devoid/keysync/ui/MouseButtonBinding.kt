package com.devoid.keysync.ui

import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.motionEventSpy
import com.devoid.keysync.domain.mouseButtonToKeyCode

@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.captureMouseButtonBinding(
    enabled: Boolean,
    onKeyCode: (Int) -> Unit
): Modifier = motionEventSpy { event ->
    if (!enabled || !event.isFromSource(InputDevice.SOURCE_MOUSE)) return@motionEventSpy

    val button = when (event.actionMasked) {
        MotionEvent.ACTION_BUTTON_PRESS -> event.actionButton
        MotionEvent.ACTION_DOWN -> when {
            event.buttonState and MotionEvent.BUTTON_SECONDARY != 0 ->
                MotionEvent.BUTTON_SECONDARY
            event.buttonState and MotionEvent.BUTTON_TERTIARY != 0 ->
                MotionEvent.BUTTON_TERTIARY
            event.buttonState and MotionEvent.BUTTON_PRIMARY != 0 ->
                MotionEvent.BUTTON_PRIMARY
            else -> 0
        }
        else -> 0
    }

    mouseButtonToKeyCode(button)?.let(onKeyCode)
}
