package com.devoid.keysync.data.external

import android.content.Context
import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import android.view.KeyEvent
import com.devoid.keysync.domain.EventHandler
import com.devoid.keysync.domain.EventInjectorImpl
import com.devoid.keysync.BuildConfig
import com.devoid.keysync.domain.touch.FrameworkTouchBackend
import com.devoid.keysync.domain.touch.TouchCoreEventInjector
import com.devoid.keysync.model.EventInjector
import com.devoid.keysync.model.InputMode

/**
 * Keeps one complete Android gesture on one privilege backend.
 *
 * The backend is selected explicitly in the app settings. It never changes
 * while mapping is running: replaying an uncertain Binder transaction through
 * another privilege backend can duplicate or strand pointers in the game.
 */
class InputBackendRouter(
    context: Context,
    inputMode: InputMode = InputMode.ROOT,
    private val shizukuBackend: ShizukuSystemServerAPi = ShizukuSystemServerAPi(context),
    private val onInjectionFailure: () -> Unit = {}
) {
    @Volatile
    private var inputMode = inputMode
    private var gestureBackend: Backend? = null
    private var failedStream = false

    @Synchronized
    fun setInputMode(mode: InputMode) {
        if (inputMode == mode) return
        gestureBackend?.let(::cancelBackend)
        gestureBackend = null
        failedStream = false
        inputMode = mode
    }

    fun getEventInjector(displayId: () -> Int = { 0 }): EventInjector {
        if (BuildConfig.TOUCH_CORE) return TouchCoreEventInjector(
            backend = FrameworkTouchBackend({ event, wait -> injectSafely(event, wait) }, ::cancelCurrentTouch),
            onFailure = onInjectionFailure,
            onReset = ::finishFailureReset,
            displayId = displayId,
            moveWindowMillis = BuildConfig.TOUCH_MOVE_WINDOW_MILLIS
        )
        return object : EventInjectorImpl() {
        override fun inject(event: InputEvent): Boolean {
            val waitForResult = (event as? MotionEvent)?.actionMasked != MotionEvent.ACTION_MOVE
            return injectSafely(event, waitForResult)
        }

        override fun cancelSystemTouchStream(): Boolean = cancelCurrentTouch()

        override fun onLocalInputReset() {
            finishFailureReset()
        }
        }
    }

    fun getEventHandler(displayId: () -> Int = { 0 }): EventHandler = EventHandler(getEventInjector(displayId))

    fun getFocusedPackageName(): String? = when (inputMode) {
        InputMode.ROOT -> RootInputConnection.getFocusedPackageName()
        InputMode.SHIZUKU -> shizukuBackend.getFocusedPackageName()
    }

    @Synchronized
    private fun injectSafely(event: InputEvent, waitForResult: Boolean): Boolean {
        // Held macro keys must still receive UP when a touch injection fails.
        if (failedStream && !(event is KeyEvent && event.action == KeyEvent.ACTION_UP)) return false

        val motionEvent = event as? MotionEvent
        val action = motionEvent?.actionMasked
        val isNewGesture = action == MotionEvent.ACTION_DOWN
        val selectedBackend = if (isNewGesture || gestureBackend == null) {
            configuredBackend()
        } else {
            gestureBackend
        }

        if (selectedBackend == null) {
            failCurrentStream(null, "No authorized input backend is available")
            return false
        }

        val injected = injectWith(selectedBackend, event, waitForResult)
        if (injected) {
            if (isNewGesture) gestureBackend = selectedBackend
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                gestureBackend = null
            }
            return true
        }

        failCurrentStream(selectedBackend, "${selectedBackend.name} input injection failed")
        return false
    }

    @Synchronized
    private fun cancelCurrentTouch(): Boolean {
        val selectedBackend = gestureBackend ?: configuredBackend()
        gestureBackend = null
        if (selectedBackend == null) return false
        return cancelBackend(selectedBackend)
    }

    @Synchronized
    private fun finishFailureReset() {
        failedStream = false
        gestureBackend = null
    }

    private fun failCurrentStream(backend: Backend?, message: String) {
        Log.e(TAG, message)
        failedStream = true
        backend?.let(::cancelBackend)
        gestureBackend = null
        onInjectionFailure()
    }

    private fun configuredBackend(): Backend? = when (inputMode) {
        InputMode.ROOT -> Backend.ROOT.takeIf { RootInputConnection.isReady() }
        InputMode.SHIZUKU -> Backend.SHIZUKU.takeIf { shizukuBackend.isAvailable() }
    }

    private fun injectWith(
        backend: Backend,
        event: InputEvent,
        waitForResult: Boolean
    ): Boolean = when (backend) {
        Backend.ROOT -> RootInputConnection.inject(event, waitForResult)
        Backend.SHIZUKU -> shizukuBackend.inject(event, waitForResult)
    }

    private fun cancelBackend(backend: Backend): Boolean = when (backend) {
        Backend.ROOT -> RootInputConnection.cancelCurrentTouch()
        Backend.SHIZUKU -> shizukuBackend.cancelCurrentTouch()
    }

    private enum class Backend {
        ROOT,
        SHIZUKU
    }

    private companion object {
        const val TAG = "InputBackendRouter"
    }
}
