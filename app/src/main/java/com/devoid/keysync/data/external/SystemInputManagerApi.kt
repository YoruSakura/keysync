package com.devoid.keysync.data.external

import android.annotation.SuppressLint
import android.os.IBinder
import android.util.Log
import android.view.InputEvent
import java.lang.reflect.Method

/**
 * Small reconnecting wrapper around Android's hidden IInputManager API.
 *
 * The binder source determines the privilege boundary: the regular process
 * supplies a Shizuku wrapped binder, while RootInputService supplies the raw
 * system binder after clearing the app caller identity.
 */
@SuppressLint("PrivateApi")
internal class SystemInputManagerApi(
    private val tag: String,
    private val binderProvider: () -> IBinder
) {
    private var inputManager: Any? = null
    private var injectInputEvent: Method? = null
    private var cancelCurrentTouch: Method? = null

    @Synchronized
    fun isAvailable(): Boolean = ensureConnected()

    @Synchronized
    fun inject(event: InputEvent, waitForResult: Boolean = true): Boolean {
        if (!ensureConnected()) return false

        val mode = if (waitForResult) {
            INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
        } else {
            INJECT_INPUT_EVENT_MODE_ASYNC
        }
        val firstAttempt = runCatching { invokeInputEvent(event, mode) }
        if (firstAttempt.getOrDefault(false)) return true

        // Both Binder exceptions AND false can be uncertain: InputManagerService
        // returns false for TIMED_OUT as well as FAILED. Never replay this event.
        if (firstAttempt.isFailure) {
            Log.e(tag, "Input injection failed with an uncertain result", firstAttempt.exceptionOrNull())
            disconnect()
            return false
        }

        Log.w(tag, "InputManager rejected or timed out; ending the input stream without replay")
        disconnect()
        return false
    }

    @Synchronized
    fun cancelCurrentTouch(): Boolean {
        if (!ensureConnected()) return false

        val firstAttempt = runCatching { invokeCancelCurrentTouch() }
        if (firstAttempt.isSuccess) return true

        Log.w(tag, "Touch cancellation failed; reconnecting to InputManager", firstAttempt.exceptionOrNull())
        disconnect()
        if (!ensureConnected()) return false

        return runCatching { invokeCancelCurrentTouch() }
            .onFailure { Log.e(tag, "Touch cancellation retry failed", it) }
            .isSuccess
    }

    private fun ensureConnected(): Boolean {
        if (inputManager != null && injectInputEvent != null) return true

        return runCatching {
            val binder = binderProvider()
            val inputManagerStub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val inputManagerClass = Class.forName("android.hardware.input.IInputManager")
            inputManager = inputManagerStub
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
            injectInputEvent = inputManagerClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Integer.TYPE
            )
            cancelCurrentTouch = runCatching {
                inputManagerClass.getMethod("cancelCurrentTouch")
            }.getOrNull()
            check(inputManager != null) { "InputManager service is unavailable" }
        }.onFailure {
            disconnect()
            Log.w(tag, "Unable to connect to InputManager", it)
        }.isSuccess
    }

    private fun invokeInputEvent(event: InputEvent, mode: Int): Boolean {
        val function = injectInputEvent
            ?: throw IllegalStateException("Input injection function is unavailable")
        val manager = inputManager
            ?: throw IllegalStateException("InputManager service is unavailable")
        return function.invoke(manager, event, mode) as? Boolean
            ?: false
    }

    private fun invokeCancelCurrentTouch() {
        val function = cancelCurrentTouch
            ?: throw NoSuchMethodException("IInputManager.cancelCurrentTouch is unavailable")
        val manager = inputManager
            ?: throw IllegalStateException("InputManager service is unavailable")
        function.invoke(manager)
    }

    private fun disconnect() {
        inputManager = null
        injectInputEvent = null
        cancelCurrentTouch = null
    }

    private companion object {
        const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1
    }
}
