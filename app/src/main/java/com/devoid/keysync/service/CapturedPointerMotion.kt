package com.devoid.keysync.service

import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset

/**
 * Pointer-capture coordinates are relative deltas. Android may batch several
 * samples into one MotionEvent, so all historical deltas must be retained.
 */
internal fun MotionEvent.accumulatedRelativeOffset(): Offset {
    var deltaX = 0f
    var deltaY = 0f

    for (historyIndex in 0 until historySize) {
        deltaX += getHistoricalX(historyIndex)
        deltaY += getHistoricalY(historyIndex)
    }

    return Offset(deltaX + x, deltaY + y)
}

/** Retains every batched wheel notch delivered through Pointer Capture. */
internal fun MotionEvent.accumulatedVerticalScroll(): Float {
    var verticalScroll = 0f
    for (historyIndex in 0 until historySize) {
        verticalScroll += getHistoricalAxisValue(MotionEvent.AXIS_VSCROLL, historyIndex)
    }
    return verticalScroll + getAxisValue(MotionEvent.AXIS_VSCROLL)
}
