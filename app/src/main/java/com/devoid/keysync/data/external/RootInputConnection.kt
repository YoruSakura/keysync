package com.devoid.keysync.data.external

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Binder
import android.os.IBinder
import android.os.HandlerThread
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-side connection to the privileged input service. */
object RootInputConnection {
    enum class Status {
        DISCONNECTED,
        CONNECTING,
        READY
    }

    sealed interface CaptureStatus {
        data object Idle : CaptureStatus
        data class Starting(val candidateDeviceCount: Int) : CaptureStatus
        data class Active(val deviceCount: Int) : CaptureStatus
        data class Failed(val reason: CaptureFailure, val nativeError: Int = 0) : CaptureStatus
    }

    enum class CaptureFailure {
        NO_ANDROID_INPUT_DEVICES,
        ROOT_SERVICE_LOST,
        SERVICE_REJECTED,
        NO_MATCHED_INPUT_NODES,
        NO_KEYBOARD_NODE,
        NO_MOUSE_MOTION_NODE,
        NO_MOUSE_BUTTON_NODE,
        NO_COMPLETE_KEYBOARD_MOUSE_PAIR,
        UINPUT_UNAVAILABLE,
        DEVICE_PERMISSION_DENIED,
        CAPTURE_BUSY,
        NATIVE_ERROR
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val inputDispatchThread = HandlerThread("KeySyncRootInput").apply { start() }
    private val inputHandler = Handler(inputDispatchThread.looper)
    private val _status = MutableStateFlow(Status.DISCONNECTED)
    val status = _status.asStateFlow()
    private val _captureStatus = MutableStateFlow<CaptureStatus>(CaptureStatus.Idle)
    val captureStatus = _captureStatus.asStateFlow()

    @Volatile
    private var serviceBinder: IBinder? = null
    @Volatile
    private var inputListener: RootInputListener? = null
    private var applicationContext: Context? = null
    private var binding = false
    private var retriesRemaining = MAX_RETRIES
    private val inputQueueLock = Any()
    private val inputQueue = ArrayDeque<PendingInput>()
    private var inputDrainScheduled = false
    private val drainInputQueue = Runnable { drainInputQueue() }
    private val bindTimeout = Runnable { handleBindTimeout() }

    private val callbackBinder = object : Binder() {
        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int
        ): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(RootInputProtocol.CALLBACK_DESCRIPTOR)
                return true
            }
            if (Binder.getCallingUid() != ROOT_UID) return false
            data.enforceInterface(RootInputProtocol.CALLBACK_DESCRIPTOR)
            when (code) {
                RootInputProtocol.CALLBACK_KEY -> {
                    val keyCode = data.readInt()
                    val pressed = data.readInt() != 0
                    enqueueInput(PendingInput.Key(keyCode, pressed))
                }

                RootInputProtocol.CALLBACK_MOUSE_BUTTON -> {
                    val button = data.readInt()
                    val pressed = data.readInt() != 0
                    enqueueInput(PendingInput.MouseButton(button, pressed))
                }

                RootInputProtocol.CALLBACK_MOUSE_MOVE -> {
                    val deltaX = data.readInt()
                    val deltaY = data.readInt()
                    enqueueInput(PendingInput.MouseMove(deltaX, deltaY))
                }

                RootInputProtocol.CALLBACK_MOUSE_SCROLL -> {
                    enqueueInput(PendingInput.MouseScroll(data.readInt()))
                }

                RootInputProtocol.CALLBACK_INPUT_RESET -> enqueueInput(PendingInput.Reset)

                RootInputProtocol.CALLBACK_CAPTURE_STATE -> {
                    val state = data.readInt()
                    val deviceCount = data.readInt()
                    val errorCode = data.readInt()
                    val captureStatus = when (state) {
                        RootInputProtocol.CAPTURE_STATE_IDLE -> CaptureStatus.Idle
                        RootInputProtocol.CAPTURE_STATE_ACTIVE -> {
                            CaptureStatus.Active(deviceCount)
                        }
                        RootInputProtocol.CAPTURE_STATE_FAILED -> {
                            captureFailure(errorCode)
                        }
                        else -> CaptureStatus.Failed(CaptureFailure.NATIVE_ERROR, errorCode)
                    }
                    updateCaptureStatus(captureStatus)
                }

                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }
    }

    private val deathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "Root input service binder died")
        mainHandler.removeCallbacks(bindTimeout)
        binding = false
        serviceBinder = null
        _status.value = Status.DISCONNECTED
        notifyCaptureLost()
        scheduleReconnect()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            mainHandler.removeCallbacks(bindTimeout)
            binding = false
            serviceBinder = service
            runCatching { service.linkToDeath(deathRecipient, 0) }
            if (ping(service)) {
                retriesRemaining = MAX_RETRIES
                _status.value = Status.READY
                Log.i(TAG, "Root input backend is ready")
            } else {
                Log.e(TAG, "Root service connected without a usable root InputManager")
                serviceBinder = null
                _status.value = Status.DISCONNECTED
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            mainHandler.removeCallbacks(bindTimeout)
            binding = false
            serviceBinder = null
            _status.value = Status.DISCONNECTED
            notifyCaptureLost()
            scheduleReconnect()
        }

        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)

        override fun onNullBinding(name: ComponentName) {
            mainHandler.removeCallbacks(bindTimeout)
            binding = false
            serviceBinder = null
            _status.value = Status.DISCONNECTED
            notifyCaptureLost()
        }
    }

    @Synchronized
    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        if (binding || isReady()) return

        binding = true
        _status.value = Status.CONNECTING
        mainHandler.removeCallbacks(bindTimeout)
        mainHandler.postDelayed(bindTimeout, BIND_TIMEOUT_MILLIS)
        val intent = Intent(context, RootInputService::class.java)
        runCatching { RootService.bind(intent, connection) }
            .onFailure {
                mainHandler.removeCallbacks(bindTimeout)
                binding = false
                _status.value = Status.DISCONNECTED
                Log.w(TAG, "Unable to bind root input service", it)
            }
    }

    @Synchronized
    fun retry(context: Context) {
        mainHandler.removeCallbacksAndMessages(RECONNECT_TOKEN)
        mainHandler.removeCallbacks(bindTimeout)
        retriesRemaining = MAX_RETRIES
        binding = false
        if (isReady()) return
        serviceBinder = null
        initialize(context)
    }

    fun isReady(): Boolean {
        val binder = serviceBinder
        return _status.value == Status.READY && binder?.isBinderAlive == true
    }

    fun inject(event: InputEvent, waitForResult: Boolean = true): Boolean {
        if (event is android.view.KeyEvent) {
            val binder = serviceBinder?.takeIf { isReady() } ?: return false
            return transactBoolean(binder, RootInputProtocol.TRANSACTION_INJECT_KEY) { parcel ->
                event.writeToParcel(parcel, 0)
            }
        }
        val motionEvent = event as? MotionEvent ?: return false
        val binder = serviceBinder?.takeIf { isReady() } ?: return false
        return transactBoolean(binder, RootInputProtocol.TRANSACTION_INJECT) { parcel ->
            parcel.writeInt(
                if (waitForResult) {
                    RootInputProtocol.INJECT_MODE_WAIT_FOR_RESULT
                } else {
                    RootInputProtocol.INJECT_MODE_ASYNC
                }
            )
            parcel.writeInt(1)
            motionEvent.writeToParcel(parcel, 0)
        }
    }

    fun cancelCurrentTouch(): Boolean {
        val binder = serviceBinder?.takeIf { isReady() } ?: return false
        return transactBoolean(
            binder,
            RootInputProtocol.TRANSACTION_CANCEL_CURRENT_TOUCH
        )
    }

    fun getFocusedPackageName(): String? {
        val binder = serviceBinder?.takeIf { isReady() } ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(RootInputProtocol.DESCRIPTOR)
            if (!binder.transact(
                    RootInputProtocol.TRANSACTION_GET_FOCUSED_PACKAGE,
                    data,
                    reply,
                    0
                )
            ) return null
            reply.readException()
            reply.readString()
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to query the focused package", error)
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun startCapture(listener: RootInputListener): Boolean {
        if (inputListener !== listener) clearPendingInput()
        inputListener = listener
        val binder = serviceBinder?.takeIf { isReady() } ?: run {
            updateCaptureStatus(
                CaptureStatus.Failed(CaptureFailure.ROOT_SERVICE_LOST)
            )
            return false
        }
        val devices = getExternalInputDeviceIdentities()
        if (devices.isEmpty()) {
            updateCaptureStatus(
                CaptureStatus.Failed(CaptureFailure.NO_ANDROID_INPUT_DEVICES)
            )
            return false
        }
        updateCaptureStatus(CaptureStatus.Starting(devices.size))
        val accepted = transactBoolean(binder, RootInputProtocol.TRANSACTION_START_CAPTURE) { parcel ->
            parcel.writeStrongBinder(callbackBinder)
            parcel.writeInt(devices.size)
            devices.forEach { device ->
                parcel.writeString(device.name)
                parcel.writeInt(device.vendorId)
                parcel.writeInt(device.productId)
            }
        }
        if (!accepted && _captureStatus.value is CaptureStatus.Starting) {
            updateCaptureStatus(CaptureStatus.Failed(CaptureFailure.SERVICE_REJECTED))
        }
        return accepted
    }

    fun stopCapture(preserveFailure: Boolean = false) {
        val binder = serviceBinder?.takeIf { isReady() }
        if (binder != null) {
            transactBoolean(binder, RootInputProtocol.TRANSACTION_STOP_CAPTURE)
        }
        val listener = inputListener
        inputListener = null
        clearPendingInput()
        if (!preserveFailure || _captureStatus.value !is CaptureStatus.Failed) {
            _captureStatus.value = CaptureStatus.Idle
            listener?.onCaptureStateChanged(CaptureStatus.Idle)
        }
    }

    private fun ping(binder: IBinder): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(RootInputProtocol.DESCRIPTOR)
            if (!binder.transact(RootInputProtocol.TRANSACTION_PING, data, reply, 0)) {
                return false
            }
            reply.readException()
            val uid = reply.readInt()
            val inputManagerReady = reply.readInt() != 0
            uid == ROOT_UID && inputManagerReady
        } catch (error: Throwable) {
            Log.w(TAG, "Root input service health check failed", error)
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactBoolean(
        binder: IBinder,
        code: Int,
        writePayload: (Parcel) -> Unit = {}
    ): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(RootInputProtocol.DESCRIPTOR)
            writePayload(data)
            if (!binder.transact(code, data, reply, 0)) return false
            reply.readException()
            reply.readInt() != 0
        } catch (error: Throwable) {
            Log.w(TAG, "Root input transaction failed", error)
            serviceBinder = null
            _status.value = Status.DISCONNECTED
            notifyCaptureLost()
            scheduleReconnect()
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun enqueueInput(input: PendingInput) {
        val shouldSchedule = synchronized(inputQueueLock) {
            val previous = inputQueue.lastOrNull()
            if (input is PendingInput.MouseMove && previous is PendingInput.MouseMove) {
                inputQueue.removeLast()
                inputQueue.addLast(
                    PendingInput.MouseMove(
                        deltaX = previous.deltaX + input.deltaX,
                        deltaY = previous.deltaY + input.deltaY
                    )
                )
            } else if (input is PendingInput.MouseScroll && previous is PendingInput.MouseScroll) {
                inputQueue.removeLast()
                inputQueue.addLast(
                    PendingInput.MouseScroll(previous.verticalDelta + input.verticalDelta)
                )
            } else {
                inputQueue.addLast(input)
            }
            if (inputDrainScheduled) {
                false
            } else {
                inputDrainScheduled = true
                true
            }
        }
        if (shouldSchedule) inputHandler.post(drainInputQueue)
    }

    private fun drainInputQueue() {
        var processed = 0
        while (processed < MAX_INPUTS_PER_DRAIN) {
            val input = synchronized(inputQueueLock) {
                inputQueue.removeFirstOrNull()
                    ?: run {
                        inputDrainScheduled = false
                        return
                    }
            }
            inputListener?.let(input::dispatch)
            processed++
        }
        inputHandler.post(drainInputQueue)
    }

    private fun clearPendingInput() {
        synchronized(inputQueueLock) {
            inputQueue.clear()
            inputDrainScheduled = false
        }
        inputHandler.removeCallbacks(drainInputQueue)
    }

    private fun updateCaptureStatus(status: CaptureStatus) {
        _captureStatus.value = status
        if (inputListener != null) enqueueInput(PendingInput.CaptureState(status))
    }

    private fun notifyCaptureLost() {
        clearPendingInput()
        if (_captureStatus.value !is CaptureStatus.Idle) {
            _captureStatus.value = CaptureStatus.Failed(CaptureFailure.ROOT_SERVICE_LOST)
        }
        if (inputListener != null) {
            enqueueInput(PendingInput.Reset)
            enqueueInput(
                PendingInput.CaptureState(
                    CaptureStatus.Failed(CaptureFailure.ROOT_SERVICE_LOST)
                )
            )
        }
    }

    @Synchronized
    private fun handleBindTimeout() {
        if (!binding || _status.value != Status.CONNECTING) return
        binding = false
        serviceBinder = null
        _status.value = Status.DISCONNECTED
        Log.w(TAG, "Root service connection timed out; Root may be denied")
    }

    private fun captureFailure(nativeError: Int): CaptureStatus.Failed {
        val errno = -nativeError
        val reason = when (errno) {
            NATIVE_ERROR_NO_MATCHED_NODES -> CaptureFailure.NO_MATCHED_INPUT_NODES
            NATIVE_ERROR_NO_KEYBOARD_NODE -> CaptureFailure.NO_KEYBOARD_NODE
            NATIVE_ERROR_NO_MOUSE_MOTION_NODE -> CaptureFailure.NO_MOUSE_MOTION_NODE
            NATIVE_ERROR_NO_MOUSE_BUTTON_NODE -> CaptureFailure.NO_MOUSE_BUTTON_NODE
            NATIVE_ERROR_UINPUT_UNAVAILABLE -> CaptureFailure.UINPUT_UNAVAILABLE
            OsConstants.ENODEV -> CaptureFailure.NO_COMPLETE_KEYBOARD_MOUSE_PAIR
            OsConstants.EACCES, OsConstants.EPERM -> CaptureFailure.DEVICE_PERMISSION_DENIED
            OsConstants.EBUSY -> CaptureFailure.CAPTURE_BUSY
            else -> CaptureFailure.NATIVE_ERROR
        }
        return CaptureStatus.Failed(reason, nativeError)
    }

    private fun getExternalInputDeviceIdentities(): List<RootInputDeviceIdentity> {
        val context = applicationContext ?: return emptyList()
        val inputManager = context.getSystemService(InputManager::class.java)
            ?: return emptyList()
        return inputManager.inputDeviceIds
            .asSequence()
            .mapNotNull(inputManager::getInputDevice)
            .filter { device ->
                val keyboardSource = device.supportsSource(InputDevice.SOURCE_KEYBOARD)
                val privateKeyboardName =
                    RootInputDeviceClassifier.isXiaomiPrivateKeyboard(device.name)
                val keyboardCandidate = keyboardSource && (
                    device.isExternal ||
                        (!device.isVirtual &&
                            device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) ||
                        privateKeyboardName
                    )
                val mouseCandidate =
                    device.supportsSource(InputDevice.SOURCE_MOUSE) &&
                        device.isExternal
                keyboardCandidate || mouseCandidate
            }
            .map { device ->
                RootInputDeviceIdentity(
                    name = device.name.take(MAX_DEVICE_NAME_LENGTH),
                    vendorId = device.vendorId,
                    productId = device.productId
                )
            }
            .distinct()
            .take(RootInputProtocol.MAX_CAPTURE_DEVICE_IDENTITIES)
            .toList()
    }

    private fun scheduleReconnect() {
        val context = applicationContext ?: return
        if (retriesRemaining <= 0) return
        retriesRemaining--
        mainHandler.removeCallbacksAndMessages(RECONNECT_TOKEN)
        mainHandler.postAtTime(
            { initialize(context) },
            RECONNECT_TOKEN,
            SystemClock.uptimeMillis() + RECONNECT_DELAY_MILLIS
        )
    }

    private const val TAG = "RootInputConnection"
    private const val ROOT_UID = 0
    private const val MAX_RETRIES = 3
    private const val MAX_INPUTS_PER_DRAIN = 64
    private const val RECONNECT_DELAY_MILLIS = 1500L
    private const val BIND_TIMEOUT_MILLIS = 10_000L
    private const val MAX_DEVICE_NAME_LENGTH = 255
    private const val NATIVE_ERROR_NO_MATCHED_NODES = 1001
    private const val NATIVE_ERROR_NO_KEYBOARD_NODE = 1002
    private const val NATIVE_ERROR_NO_MOUSE_MOTION_NODE = 1003
        private const val NATIVE_ERROR_NO_MOUSE_BUTTON_NODE = 1004
        private const val NATIVE_ERROR_UINPUT_UNAVAILABLE = 1005
    private val RECONNECT_TOKEN = Any()

    private sealed interface PendingInput {
        fun dispatch(listener: RootInputListener)

        data class Key(val keyCode: Int, val pressed: Boolean) : PendingInput {
            override fun dispatch(listener: RootInputListener) {
                listener.onKeyEvent(keyCode, pressed)
            }
        }

        data class MouseButton(val button: Int, val pressed: Boolean) : PendingInput {
            override fun dispatch(listener: RootInputListener) {
                listener.onMouseButton(button, pressed)
            }
        }

        data class MouseMove(val deltaX: Int, val deltaY: Int) : PendingInput {
            override fun dispatch(listener: RootInputListener) {
                listener.onMouseMove(deltaX, deltaY)
            }
        }

        data class MouseScroll(val verticalDelta: Int) : PendingInput {
            override fun dispatch(listener: RootInputListener) {
                listener.onMouseScroll(verticalDelta)
            }
        }

        data object Reset : PendingInput {
            override fun dispatch(listener: RootInputListener) {
                listener.onInputReset()
            }
        }

        data class CaptureState(val status: CaptureStatus) : PendingInput {
            override fun dispatch(listener: RootInputListener) {
                listener.onCaptureStateChanged(status)
            }
        }
    }
}
