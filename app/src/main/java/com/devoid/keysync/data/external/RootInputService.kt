package com.devoid.keysync.data.external

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log
import android.view.MotionEvent
import android.view.KeyEvent
import android.os.SystemClock
import com.topjohnwu.superuser.ipc.RootService
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.Executors

/** Runs input injection in a short-lived, app-bound root process. */
class RootInputService : RootService() {
    private lateinit var inputManagerApi: SystemInputManagerApi
    private lateinit var activityTaskManagerApi: SystemActivityTaskManagerApi
    private val captureExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "KeySyncRootEvdev")
    }
    @Volatile
    private var captureCallback: IBinder? = null
    private var captureDevices: List<RootInputDeviceIdentity> = emptyList()
    private var captureRequested = false
    private var captureRunning = false
    private val injectedKeys = mutableMapOf<Int, KeyEvent>()
    private val callbackDeathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "Input capture client died; releasing grabbed devices")
        stopCapture()
    }

    override fun onCreate() {
        inputManagerApi = SystemInputManagerApi(TAG) {
            SystemServiceHelper.getSystemService("input")
        }
        activityTaskManagerApi = SystemActivityTaskManagerApi(TAG) {
            SystemServiceHelper.getSystemService("activity_task")
        }
    }

    override fun onBind(intent: Intent): IBinder = object : Binder() {
        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int
        ): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(RootInputProtocol.DESCRIPTOR)
                return true
            }
            if (!isTrustedCaller()) {
                Log.e(TAG, "Rejected root input request from uid=${Binder.getCallingUid()}")
                return false
            }

            data.enforceInterface(RootInputProtocol.DESCRIPTOR)
            return when (code) {
                RootInputProtocol.TRANSACTION_PING -> {
                    val ready = withServiceIdentity { inputManagerApi.isAvailable() }
                    reply?.writeNoException()
                    reply?.writeInt(Process.myUid())
                    reply?.writeInt(if (ready) 1 else 0)
                    true
                }

                RootInputProtocol.TRANSACTION_INJECT -> {
                    val injectionMode = data.readInt()
                    val event = if (data.readInt() != 0) {
                        MotionEvent.CREATOR.createFromParcel(data)
                    } else {
                        null
                    }
                    val injected = event?.let {
                        try {
                            withServiceIdentity {
                                inputManagerApi.inject(
                                    it,
                                    waitForResult = injectionMode ==
                                        RootInputProtocol.INJECT_MODE_WAIT_FOR_RESULT
                                )
                            }
                        } finally {
                            it.recycle()
                        }
                    } ?: false
                    reply?.writeNoException()
                    reply?.writeInt(if (injected) 1 else 0)
                    true
                }

                RootInputProtocol.TRANSACTION_CANCEL_CURRENT_TOUCH -> {
                    val cancelled = withServiceIdentity {
                        inputManagerApi.cancelCurrentTouch()
                    }
                    reply?.writeNoException()
                    reply?.writeInt(if (cancelled) 1 else 0)
                    true
                }

                RootInputProtocol.TRANSACTION_INJECT_KEY -> {
                    val event = KeyEvent.CREATOR.createFromParcel(data)
                    val injected = withServiceIdentity { injectKey(event) }
                    reply?.writeNoException()
                    reply?.writeInt(if (injected) 1 else 0)
                    true
                }

                RootInputProtocol.TRANSACTION_START_CAPTURE -> {
                    val callback = data.readStrongBinder()
                    val devices = readCaptureDeviceIdentities(data)
                    val accepted = devices?.let { startCapture(callback, it) } ?: false
                    reply?.writeNoException()
                    reply?.writeInt(if (accepted) 1 else 0)
                    true
                }

                RootInputProtocol.TRANSACTION_STOP_CAPTURE -> {
                    stopCapture()
                    reply?.writeNoException()
                    reply?.writeInt(1)
                    true
                }

                RootInputProtocol.TRANSACTION_GET_FOCUSED_PACKAGE -> {
                    val packageName = withServiceIdentity {
                        activityTaskManagerApi.getFocusedPackageName()
                    }
                    reply?.writeNoException()
                    reply?.writeString(packageName)
                    true
                }

                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    override fun onUnbind(intent: Intent): Boolean {
        stopCapture()
        return false
    }

    override fun onDestroy() {
        stopCapture()
        captureExecutor.shutdownNow()
    }

    @Synchronized
    private fun startCapture(
        callback: IBinder?,
        devices: List<RootInputDeviceIdentity>
    ): Boolean {
        if (callback == null || !callback.isBinderAlive || devices.isEmpty()) return false
        if (captureCallback !== callback) {
            runCatching { captureCallback?.unlinkToDeath(callbackDeathRecipient, 0) }
            captureCallback = callback
            runCatching { callback.linkToDeath(callbackDeathRecipient, 0) }
        }
        captureDevices = devices
        captureRequested = true
        val nativeReady = runCatching { RootEvdevNative.nativePrepareCapture() }
            .onFailure { Log.e(TAG, "Unable to prepare native input capture", it) }
            .isSuccess
        if (!nativeReady) {
            captureRequested = false
            releaseCaptureCallback()
            return false
        }
        launchCaptureIfNeeded()
        return true
    }

    @Synchronized
    private fun stopCapture() {
        captureRequested = false
        releaseInjectedKeys()
        runCatching { RootEvdevNative.nativeStopCapture() }
            .onFailure { Log.w(TAG, "Unable to stop native input capture", it) }
        if (!captureRunning) releaseCaptureCallback()
    }

    @Synchronized
    private fun injectKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !captureRequested) return false
        if (event.action == KeyEvent.ACTION_DOWN) injectedKeys[event.keyCode] = event
        val accepted = inputManagerApi.inject(event)
        if (accepted && event.action == KeyEvent.ACTION_UP) injectedKeys.remove(event.keyCode)
        return accepted
    }

    private fun releaseInjectedKeys() {
        val time = SystemClock.uptimeMillis()
        withServiceIdentity {
            injectedKeys.values.forEach { down ->
                inputManagerApi.inject(KeyEvent(down.downTime, time, KeyEvent.ACTION_UP,
                    down.keyCode, 0, 0, down.deviceId, down.scanCode, down.flags, down.source))
            }
        }
        injectedKeys.clear()
    }

    @Synchronized
    private fun launchCaptureIfNeeded() {
        if (!captureRequested || captureRunning) return
        val devices = captureDevices.toList()
        if (devices.isEmpty()) return
        captureRunning = true
        captureExecutor.execute {
            val result = runCatching {
                RootEvdevNative.nativeRunCapture(
                    callback = this,
                    names = devices.map(RootInputDeviceIdentity::name).toTypedArray(),
                    vendorIds = devices.map(RootInputDeviceIdentity::vendorId).toIntArray(),
                    productIds = devices.map(RootInputDeviceIdentity::productId).toIntArray()
                )
            }
                .onFailure { Log.e(TAG, "Native input capture crashed", it) }
                .getOrDefault(-1)
            if (result < 0) Log.w(TAG, "Native input capture ended with code $result")
            onCaptureLoopFinished(result)
        }
    }

    @Synchronized
    private fun onCaptureLoopFinished(result: Int) {
        val stoppedIntentionally = !captureRequested
        captureRunning = false
        if (result < 0) {
            captureRequested = false
            releaseInjectedKeys()
            if (!stoppedIntentionally) {
                sendCallback(
                    RootInputProtocol.CALLBACK_CAPTURE_STATE,
                    RootInputProtocol.CAPTURE_STATE_FAILED,
                    0,
                    result
                )
            }
        } else if (stoppedIntentionally) {
            sendCallback(
                RootInputProtocol.CALLBACK_CAPTURE_STATE,
                RootInputProtocol.CAPTURE_STATE_IDLE,
                0,
                0
            )
        }
        if (captureRequested) {
            launchCaptureIfNeeded()
        } else {
            releaseCaptureCallback()
        }
    }

    private fun releaseCaptureCallback() {
        runCatching { captureCallback?.unlinkToDeath(callbackDeathRecipient, 0) }
        captureCallback = null
        captureDevices = emptyList()
    }

    private fun readCaptureDeviceIdentities(
        data: Parcel
    ): List<RootInputDeviceIdentity>? {
        val count = data.readInt()
        if (count !in 1..RootInputProtocol.MAX_CAPTURE_DEVICE_IDENTITIES) {
            Log.w(TAG, "Rejected invalid capture device count: $count")
            return null
        }
        val devices = ArrayList<RootInputDeviceIdentity>(count)
        repeat(count) {
            val name = data.readString()?.take(MAX_DEVICE_NAME_LENGTH) ?: return null
            devices += RootInputDeviceIdentity(
                name = name,
                vendorId = data.readInt(),
                productId = data.readInt()
            )
        }
        return devices
    }

    @Suppress("unused") // Called from RootEvdevCapture.cpp.
    fun onNativeKey(scanCode: Int, value: Int) {
        val androidKeyCode = LinuxKeyCodeMapper.toAndroidKeyCode(scanCode) ?: return
        sendCallback(RootInputProtocol.CALLBACK_KEY, androidKeyCode, value)
    }

    @Suppress("unused") // Called from RootEvdevCapture.cpp.
    fun onNativeMouseButton(linuxButton: Int, value: Int) {
        val androidButton = when (linuxButton) {
            LINUX_BUTTON_LEFT -> MotionEvent.BUTTON_PRIMARY
            LINUX_BUTTON_RIGHT -> MotionEvent.BUTTON_SECONDARY
            LINUX_BUTTON_MIDDLE -> MotionEvent.BUTTON_TERTIARY
            else -> return
        }
        sendCallback(RootInputProtocol.CALLBACK_MOUSE_BUTTON, androidButton, value)
    }

    @Suppress("unused") // Called from RootEvdevCapture.cpp.
    fun onNativeMouseMove(deltaX: Int, deltaY: Int) {
        sendCallback(RootInputProtocol.CALLBACK_MOUSE_MOVE, deltaX, deltaY)
    }

    @Suppress("unused") // Called from RootEvdevCapture.cpp.
    fun onNativeMouseScroll(verticalDelta: Int) {
        sendCallback(RootInputProtocol.CALLBACK_MOUSE_SCROLL, verticalDelta)
    }

    @Suppress("unused") // Called from RootEvdevCapture.cpp.
    fun onNativeInputReset() {
        sendCallback(RootInputProtocol.CALLBACK_INPUT_RESET)
    }

    @Suppress("unused") // Called from RootEvdevCapture.cpp.
    fun onNativeCaptureState(deviceCount: Int) {
        // A zero count is followed by the native loop's precise errno. Ignore
        // it here so the app never mistakes a failed hand-off for an idle stop.
        if (deviceCount > 0) {
            sendCallback(
                RootInputProtocol.CALLBACK_CAPTURE_STATE,
                RootInputProtocol.CAPTURE_STATE_ACTIVE,
                deviceCount,
                0
            )
        }
    }

    private fun sendCallback(code: Int, vararg values: Int) {
        val callback = captureCallback?.takeIf(IBinder::isBinderAlive) ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(RootInputProtocol.CALLBACK_DESCRIPTOR)
            values.forEach(data::writeInt)
            callback.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to deliver native input event", error)
            stopCapture()
        } finally {
            data.recycle()
        }
    }

    private fun isTrustedCaller(): Boolean = Binder.getCallingUid() == applicationInfo.uid

    private inline fun <T> withServiceIdentity(block: () -> T): T {
        val identity = Binder.clearCallingIdentity()
        return try {
            block()
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private companion object {
        const val TAG = "RootInputService"
        const val MAX_DEVICE_NAME_LENGTH = 255
        const val LINUX_BUTTON_LEFT = 0x110
        const val LINUX_BUTTON_RIGHT = 0x111
        const val LINUX_BUTTON_MIDDLE = 0x112
    }
}
