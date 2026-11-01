package com.devoid.keysync.service

import android.content.Context
import android.widget.FrameLayout

/** Reports pointer-capture transitions immediately instead of relying on polling. */
internal class PointerCaptureFrameLayout(context: Context) : FrameLayout(context) {
    var onPointerCaptureChanged: ((Boolean) -> Unit)? = null

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        onPointerCaptureChanged?.invoke(hasCapture)
    }
}
