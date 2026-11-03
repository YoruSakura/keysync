package com.devoid.keysync.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.geometry.Offset
import androidx.core.view.WindowInsetsCompat
import androidx.datastore.preferences.core.Preferences
import com.devoid.keysync.data.local.DataStoreManager
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.DraggableItemType
import com.devoid.keysync.model.InputMode
import com.devoid.keysync.BuildConfig
import com.devoid.keysync.R
import com.devoid.keysync.domain.EventHandler
import com.devoid.keysync.data.external.InputBackendRouter
import com.devoid.keysync.data.external.RootInputConnection
import com.devoid.keysync.data.external.RootInputListener
import com.devoid.keysync.data.external.RootMouseScroll
import com.devoid.keysync.domain.touch.MumuActionDispatcher
import com.devoid.keysync.domain.touch.MumuProfile
import com.devoid.keysync.domain.touch.MumuProfileCodec
import com.devoid.keysync.domain.touch.MumuLayoutEditor
import com.devoid.keysync.domain.touch.MumuLayoutTarget
import com.devoid.keysync.domain.touch.TouchPoint
import com.devoid.keysync.domain.touch.TouchCoreEventInjector
import com.devoid.keysync.domain.touch.TouchViewport
import com.devoid.keysync.domain.touch.touchPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

class FloatingWindowStateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStoreManager: DataStoreManager
) {

    val scope = CoroutineScope(Dispatchers.Main +SupervisorJob())

    private val mainHandler = Handler(Looper.getMainLooper())
    private val reportInputInjectionFailure = Runnable {
        stopMapping(MappingFailure.INPUT_INJECTION_FAILED)
    }
    private val inputBackendRouter = InputBackendRouter(context) {
        mainHandler.removeCallbacks(reportInputInjectionFailure)
        mainHandler.post(reportInputInjectionFailure)
    }
    private val eventInjector by lazy {
        inputBackendRouter.getEventInjector {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.displayId
        }
    }
    private val eventHandler: EventHandler by lazy { EventHandler(eventInjector) }
    @Volatile private var mumuDispatcher: MumuActionDispatcher? = null
    private val releaseIdleShootingPointer = Runnable {
        eventHandler.releaseIdleShootingPointer()
    }
    val windowManager: WindowManager by lazy { context.getSystemService(WindowManager::class.java) }

    val pointerSensitivity = MutableStateFlow(0.5f)
    val aimSensitivity = MutableStateFlow(0.5f)
    val overlayOpacity = MutableStateFlow(0.5f)

    private var buttonConfigKey :Preferences.Key<String>? = null
    private var configLoadJob: Job? = null
    private var configLoadGeneration = 0L

    private val _appConfig =MutableStateFlow(AppConfig.Default)
    val keysConfig = _appConfig.asStateFlow()

    private val _isBubbleExpanded = MutableStateFlow(false)
    val isBubbleExpanded = _isBubbleExpanded.asStateFlow()

    private val _rootInputCaptureActive = MutableStateFlow(false)
    val rootInputCaptureActive = _rootInputCaptureActive.asStateFlow()
    private val _overlayInputCaptureAllowed = MutableStateFlow(false)
    val overlayInputCaptureAllowed = _overlayInputCaptureAllowed.asStateFlow()
    private val _mappingFailure = MutableStateFlow<MappingFailure?>(null)
    val mappingFailure = _mappingFailure.asStateFlow()
    private val _mappingTargetActive = MutableStateFlow(false)
    val mappingTargetActive = _mappingTargetActive.asStateFlow()
    private var rootInputCaptureDesired = false
    private val rootInputListener: RootInputListener = object : RootInputListener {
        override fun onKeyEvent(keyCode: Int, pressed: Boolean) {
            if (!BuildConfig.TOUCH_CORE && Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { onKeyEvent(keyCode, pressed) }; return
            }
            if (_isBubbleExpanded.value || !_mappingTargetActive.value) return
            mumuDispatcher?.key(keyCode, pressed, softwareCursor.position.touchPoint()) ?: eventHandler.handleKeyEvent(
                keyCode, if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP)
        }

        override fun onMouseButton(button: Int, pressed: Boolean) {
            if (!BuildConfig.TOUCH_CORE && Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { onMouseButton(button, pressed) }; return
            }
            if (_isBubbleExpanded.value || !_mappingTargetActive.value) return
            handleMouseButton(button, pressed)
        }

        override fun onMouseMove(deltaX: Int, deltaY: Int) {
            if (!BuildConfig.TOUCH_CORE && Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { onMouseMove(deltaX, deltaY) }; return
            }
            if (_isBubbleExpanded.value || !_mappingTargetActive.value) return
            handleMouseMove(Offset(deltaX.toFloat(), deltaY.toFloat()))
        }

        override fun onMouseScroll(verticalDeltaUnits: Int) {
            if (!BuildConfig.TOUCH_CORE && Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { onMouseScroll(verticalDeltaUnits) }; return
            }
            if (_isBubbleExpanded.value || !_mappingTargetActive.value) return
            handleMouseScroll(RootMouseScroll.toNotches(verticalDeltaUnits))
        }

        override fun onInputReset() {
            if (Looper.myLooper() != Looper.getMainLooper()) { mainHandler.post { onInputReset() }; return }
            if (!_isBubbleExpanded.value) onInputCaptureInterrupted()
        }

        override fun onCaptureStateChanged(status: RootInputConnection.CaptureStatus) {
            if (Looper.myLooper() != Looper.getMainLooper()) { mainHandler.post { onCaptureStateChanged(status) }; return }
            when (status) {
                is RootInputConnection.CaptureStatus.Active -> {
                    _rootInputCaptureActive.value = rootInputCaptureDesired
                }
                is RootInputConnection.CaptureStatus.Failed -> {
                    _rootInputCaptureActive.value = false
                    if (rootInputCaptureDesired && keysConfig.value.inputMode == InputMode.ROOT) {
                        stopMapping(MappingFailure.ROOT_CAPTURE_FAILED)
                    }
                }
                RootInputConnection.CaptureStatus.Idle,
                is RootInputConnection.CaptureStatus.Starting -> {
                    _rootInputCaptureActive.value = false
                }
            }
        }
    }

    private var maximumPointerPosition = currentMaximumPointerPosition()
    private var cursorRenderer: ((Offset) -> Unit)? = null
    private val softwareCursor = SoftwareCursorController(
        initialPosition = Offset(
            (maximumPointerPosition.x + 1f) / 2f,
            (maximumPointerPosition.y + 1f) / 2f
        ),
        maximumPosition = maximumPointerPosition,
        scheduleFrame = { render ->
            // Root callbacks run on KeySyncRootInput, never call Choreographer there.
            mainHandler.post { Choreographer.getInstance().postFrameCallback { render() } }
        },
        renderPosition = { cursorRenderer?.invoke(it) }
    )

    fun setCursorRenderer(renderer: (Offset) -> Unit) {
        cursorRenderer = renderer
        renderer(softwareCursor.position)
    }

    private val _containerItems = MutableStateFlow(listOf<DraggableItem>())
    val containerItems = _containerItems.asStateFlow()

    private val _mumuProfile = MutableStateFlow<MumuProfile?>(null)
    val mumuProfile = _mumuProfile.asStateFlow()
    private var mumuIdentity: Pair<String, String>? = null
    private val _mumuLayoutEdits = MutableStateFlow<Map<MumuLayoutTarget, TouchPoint>>(emptyMap())
    val mumuLayoutEdits = _mumuLayoutEdits.asStateFlow()
    private val _mumuLayoutSaving = MutableStateFlow(false)
    val mumuLayoutSaving = _mumuLayoutSaving.asStateFlow()

    private val _isShootingMode = MutableStateFlow(false)
    val isShootingMode = _isShootingMode.asStateFlow()
    private var shootingModeJob: Job? = null
    private var resumeShootingModeAfterCapture = false

    init {
        observeShootingMode()
        eventHandler.setPointerBounds(maximumPointerPosition)
        scope.launch {
            isBubbleExpanded.drop(1).collect{isExpanded->
                if (isExpanded)
                    return@collect
                buttonConfigKey?.let {
                    dataStoreManager.save(DataStoreManager.OVERLAY_OPACITY,overlayOpacity.value)
                    dataStoreManager.save(DataStoreManager.POINTER_SENSITIVITY,pointerSensitivity.value)
                    dataStoreManager.save(DataStoreManager.AIM_SENSITIVITY,aimSensitivity.value)
                    dataStoreManager.save(it,containerItems.value)
                }
                _appConfig.value = dataStoreManager.getKeyConfig(DataStoreManager.KEYS_CONFIG).first()
                applyInputMode(_appConfig.value.inputMode)
                eventHandler.appConfig = _appConfig.value
                eventHandler.setCancelableTouchMode(_appConfig.value.cancellableTouchMode)
            }
        }
        scope.launch {
            _appConfig.value = dataStoreManager.getKeyConfig(DataStoreManager.KEYS_CONFIG).first()
            applyInputMode(_appConfig.value.inputMode)
            eventHandler.setCancelableTouchMode(_appConfig.value.cancellableTouchMode)
            eventHandler.appConfig = _appConfig.value
        }
        scope.launch {
            RootInputConnection.status.collect { status ->
                if (rootInputCaptureDesired && keysConfig.value.inputMode == InputMode.ROOT) {
                    if (status == RootInputConnection.Status.READY) {
                        tryStartRootInputCapture()
                    } else {
                        stopMapping(MappingFailure.ROOT_CONNECTION_LOST)
                    }
                }
            }
        }

    }

    fun loadButtonsConfig(packageName: String) {
        val configKey = DataStoreManager.getButtonsConfigKey(packageName)
        val generation = ++configLoadGeneration
        configLoadJob?.cancel()
        buttonConfigKey = configKey
        _mappingFailure.value = null

        // Disable the previous game's mappings immediately. A system-level
        // cancellation also clears a gesture left behind by a killed process,
        // which the new EventManager cannot otherwise know about.
        resumeShootingModeAfterCapture = false
        cancelIdleShootingPointerRelease()
        eventHandler.resetForGameSwitch()
        mumuDispatcher?.close()
        mumuDispatcher = null
        _mumuProfile.value = null
        mumuIdentity = null
        _mumuLayoutEdits.value = emptyMap()
        observeShootingMode()
        eventHandler.updateKeyMapping(emptyList())
        _containerItems.value = emptyList()

        configLoadJob = scope.launch {
            val buttons = dataStoreManager.getButtons(configKey).first()
            val appConfig = dataStoreManager
                .getKeyConfig(DataStoreManager.KEYS_CONFIG)
                .first()
            val savedOpacity = dataStoreManager
                .getFloat(DataStoreManager.OVERLAY_OPACITY)
                .first()
            val savedSensitivity = dataStoreManager
                .getFloat(DataStoreManager.POINTER_SENSITIVITY)
                .first()
            val savedAimSensitivity = dataStoreManager
                .getFloat(DataStoreManager.AIM_SENSITIVITY)
                .first()
            val savedMumu = dataStoreManager.getMumuLibraries().first()[packageName]?.selectedProfile

            if (generation != configLoadGeneration) return@launch

            _appConfig.value = appConfig
            applyInputMode(appConfig.inputMode)
            eventHandler.appConfig = appConfig
            eventHandler.setCancelableTouchMode(appConfig.cancellableTouchMode)
            _containerItems.value = buttons
            eventHandler.updateKeyMapping(buttons)
            if (savedMumu != null && eventInjector is TouchCoreEventInjector) {
                // Invalid stored profiles stop this launch rather than silently using unrelated mappings.
                val profile = runCatching { MumuProfileCodec.decode(savedMumu.json) }.getOrElse {
                    stopMapping(MappingFailure.INPUT_INJECTION_FAILED)
                    return@launch
                }
                _mumuProfile.value = profile
                mumuIdentity = packageName to savedMumu.id
                mumuDispatcher = MumuActionDispatcher(profile, eventInjector as TouchCoreEventInjector,
                    TouchViewport(maximumPointerPosition.x + 1.0, maximumPointerPosition.y + 1.0))
                observeShootingMode()
            }
            savedOpacity?.let { overlayOpacity.value = it }
            savedSensitivity?.let { pointerSensitivity.value = it }
            aimSensitivity.value = savedAimSensitivity ?: 0.5f
        }
    }

    fun addNewItem(itemType: DraggableItemType) {
        val itemID = containerItems.value.sumOf { it.id } + 1
        val offset = Offset(0f,200f)
        val item = when (itemType) {
            DraggableItemType.KEY -> {
                DraggableItem.VariableKey(itemID, position = offset, size = 0)
            }

            DraggableItemType.WASD_KEY -> {
                DraggableItem.WASDGroup(itemID, offset)
            }

            DraggableItemType.SHOOTING_MODE -> {
                DraggableItem.FixedKey(
                    itemID,
                    offset,
                    size = 0,
                    type = itemType,
                    keyCode = keysConfig.value.shootingModeKeyCode
                )

            }

            DraggableItemType.FIRE -> {
                DraggableItem.FixedKey(
                    itemID,
                    offset,
                    size = 0,
                    type = itemType,
                    keyCode = keysConfig.value.fireKeyCode
                )
            }
            DraggableItemType.BAG_MAP->{
                DraggableItem.CancelableKey(
                    itemID,
                    offset,
                    cancelPosition = offset,
                    size = 0,
                    type = itemType
                )}
            DraggableItemType.SCOPE->{
                DraggableItem.FixedKey(
                    itemID,
                    offset,
                    size = 0,
                    type = itemType,
                    keyCode = keysConfig.value.scopeKeyCode
                    )
            }
        }
        _containerItems.value = containerItems.value.plus(item)
    }

    fun removeItem(id: Int) {
        _containerItems.value = _containerItems.value.filterNot { it.id == id }
    }

    fun onFloatingBubbleClick() {
        if (_mumuLayoutSaving.value) return
        if (_isBubbleExpanded.value && _mumuLayoutEdits.value.isNotEmpty()) {
            val (packageName, id) = mumuIdentity!!
            val profile = MumuLayoutEditor.apply(_mumuProfile.value!!, _mumuLayoutEdits.value)
            _mumuLayoutSaving.value = true
            scope.launch {
                try {
                    dataStoreManager.updateMumuLayout(packageName, id, profile.source.toString())
                    mumuDispatcher?.close()
                    _mumuProfile.value = profile
                    mumuDispatcher = MumuActionDispatcher(profile, eventInjector as TouchCoreEventInjector,
                        TouchViewport(maximumPointerPosition.x + 1.0, maximumPointerPosition.y + 1.0))
                    observeShootingMode()
                    _mumuLayoutEdits.value = emptyMap()
                    _isBubbleExpanded.value = false
                } catch (_: IOException) {
                    Toast.makeText(context, context.getString(R.string.mumu_layout_save_failed), Toast.LENGTH_LONG).show()
                } finally {
                    _mumuLayoutSaving.value = false
                }
            }
            return
        }
        _isBubbleExpanded.value = !_isBubbleExpanded.value
        if (!_isBubbleExpanded.value) {
            eventHandler.updateKeyMapping(containerItems.value)
        }
    }

    fun moveMumuTarget(target: MumuLayoutTarget, point: TouchPoint) {
        _mumuLayoutEdits.value = _mumuLayoutEdits.value + (target to point)
    }

    fun cancelMumuLayout() {
        _mumuLayoutEdits.value = emptyMap()
        onFloatingBubbleClick()
    }

    fun onKeyEvent(keyEvent: KeyEvent): Boolean {
        if (_isBubbleExpanded.value || !_mappingTargetActive.value)
            return false
        if (keyEvent.action != KeyEvent.ACTION_DOWN && keyEvent.action != KeyEvent.ACTION_UP) return false
        return mumuDispatcher?.key(keyEvent.keyCode, keyEvent.action == KeyEvent.ACTION_DOWN, softwareCursor.position.touchPoint())
            ?: eventHandler.handleKeyEvent(keyEvent)
    }

    fun onMouseEvent(motionEvent: MotionEvent): Boolean {
        if (isBubbleExpanded.value || !_mappingTargetActive.value)
            return false
        when (motionEvent.actionMasked) {

            MotionEvent.ACTION_BUTTON_PRESS -> {
                handleMouseButton(motionEvent.actionButton, true)
            }

            MotionEvent.ACTION_BUTTON_RELEASE -> {
                handleMouseButton(motionEvent.actionButton, false)
            }

            MotionEvent.ACTION_MOVE -> {
                val offset = motionEvent.accumulatedRelativeOffset()
                return handleMouseMove(offset)

            }


            MotionEvent.ACTION_SCROLL -> {
                return handleMouseScroll(motionEvent.accumulatedVerticalScroll())
            }
        }
        return true
    }

    fun startInputCapture() {
        if (!_mappingTargetActive.value) return
        if (keysConfig.value.inputMode == InputMode.SHIZUKU) {
            rootInputCaptureDesired = false
            _rootInputCaptureActive.value = false
            return
        }
        rootInputCaptureDesired = true
        tryStartRootInputCapture()
    }

    fun stopInputCapture() {
        rootInputCaptureDesired = false
        RootInputConnection.stopCapture()
        _rootInputCaptureActive.value = false
    }

    /**
     * Enables input only while the package selected from KeySync owns the
     * foreground task. Leaving it is a normal pause, not a backend failure.
     */
    fun setMappingTargetActive(active: Boolean) {
        if (_mappingTargetActive.value == active) return
        _mappingTargetActive.value = active
        _overlayInputCaptureAllowed.value =
            active && keysConfig.value.inputMode == InputMode.SHIZUKU

        if (!active) {
            resumeShootingModeAfterCapture = false
            cancelIdleShootingPointerRelease()
            stopInputCapture()
            eventHandler.resetForGameSwitch()
            mumuDispatcher?.reset()
        }
    }

    fun getFocusedPackageName(): String? = inputBackendRouter.getFocusedPackageName()

    private fun tryStartRootInputCapture() {
        if (
            !rootInputCaptureDesired ||
            !_mappingTargetActive.value ||
            keysConfig.value.inputMode != InputMode.ROOT
        ) return
        if (!RootInputConnection.isReady()) {
            stopMapping(MappingFailure.ROOT_CONNECTION_LOST)
            return
        }
        val captureStatus = RootInputConnection.captureStatus.value
        if (
            captureStatus is RootInputConnection.CaptureStatus.Starting ||
            captureStatus is RootInputConnection.CaptureStatus.Active
        ) return
        RootInputConnection.startCapture(rootInputListener)
    }

    fun clearActivePointers() {
        resumeShootingModeAfterCapture = false
        cancelIdleShootingPointerRelease()
        eventHandler.clear()
        mumuDispatcher?.reset()
    }

    fun onInputCaptureInterrupted() {
        cancelIdleShootingPointerRelease()
        val wasShootingModeActive = mumuDispatcher?.let {
            val aiming = it.shootingModeFlow.value
            it.reset()
            aiming
        } ?: eventHandler.pauseForInputCaptureLoss()
        resumeShootingModeAfterCapture =
            resumeShootingModeAfterCapture || wasShootingModeActive
    }

    fun onInputCaptureRestored() {
        if (!resumeShootingModeAfterCapture) return

        val dispatcher = mumuDispatcher
        if (dispatcher != null) {
            dispatcher.resumeCamera()
            resumeShootingModeAfterCapture = false
        } else resumeShootingModeAfterCapture = !eventHandler.resumeShootingMode()
    }

    private fun applyInputMode(mode: InputMode) {
        inputBackendRouter.setInputMode(mode)
        _overlayInputCaptureAllowed.value =
            mode == InputMode.SHIZUKU && _mappingTargetActive.value
        if (mode == InputMode.SHIZUKU && rootInputCaptureDesired) {
            stopInputCapture()
        }
    }

    private fun stopMapping(failure: MappingFailure) {
        if (_mappingFailure.value != null) return
        rootInputCaptureDesired = false
        _rootInputCaptureActive.value = false
        eventHandler.resetForGameSwitch()
        mumuDispatcher?.reset()
        RootInputConnection.stopCapture(preserveFailure = true)
        _mappingFailure.value = failure
    }

    fun onDestroy(){
        _isBubbleExpanded.value = false
        resumeShootingModeAfterCapture = false
        configLoadJob?.cancel()
        cancelIdleShootingPointerRelease()
        mainHandler.removeCallbacks(reportInputInjectionFailure)
        softwareCursor.cancel()
        cursorRenderer = null
        if (_mappingFailure.value == null) {
            stopInputCapture()
        } else {
            RootInputConnection.stopCapture(preserveFailure = true)
        }
        eventHandler.shutdown()
        mumuDispatcher?.close()
        shootingModeJob?.cancel()
        scope.cancel()
    }

    private fun scheduleIdleShootingPointerRelease() {
        if (eventHandler.usesTouchCore) return
        mainHandler.removeCallbacks(releaseIdleShootingPointer)
        mainHandler.postDelayed(
            releaseIdleShootingPointer,
            AIM_POINTER_IDLE_RELEASE_MILLIS
        )
    }

    private fun cancelIdleShootingPointerRelease() {
        mainHandler.removeCallbacks(releaseIdleShootingPointer)
    }

    /** Keeps injected and rendered coordinates aligned after rotation/resizing. */
    fun updateDisplayBounds() {
        updateDisplayBounds(currentMaximumPointerPosition())
    }

    /** Uses the actual overlay layout when it is more precise than window metrics. */
    fun updateDisplayBounds(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        updateDisplayBounds(
            Offset(
                (width - 1).toFloat(),
                (height - 1).toFloat()
            )
        )
    }

    private fun updateDisplayBounds(updatedMaximum: Offset) {
        if (updatedMaximum == maximumPointerPosition) return

        maximumPointerPosition = updatedMaximum
        eventHandler.setPointerBounds(updatedMaximum)
        mumuDispatcher?.resize(TouchViewport(updatedMaximum.x + 1.0, updatedMaximum.y + 1.0))
        eventHandler.mousePointerPosition =
            softwareCursor.updateMaximumPosition(updatedMaximum)
    }

    private fun currentMaximumPointerPosition(): Offset {
        val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.let { bounds ->
                bounds.width() to bounds.height()
            }
        } else {
            @Suppress("DEPRECATION")
            context.resources.displayMetrics.let { metrics ->
                metrics.widthPixels to metrics.heightPixels
            }
        }
        return Offset(
            (width - 1).coerceAtLeast(0).toFloat(),
            (height - 1).coerceAtLeast(0).toFloat()
        )
    }

    private fun handleMouseButton(button: Int, pressed: Boolean) {
        // The rendered cursor state is frame-coalesced, while clicks must
        // always use the newest logical cursor position.
        if (pressed) eventHandler.mousePointerPosition = softwareCursor.position
        mumuDispatcher?.mouseButton(button, pressed, softwareCursor.position.touchPoint())
            ?: eventHandler.handleMouseButton(button, pressed)
        if (!isShootingMode.value) cancelIdleShootingPointerRelease()
    }

    private fun handleMouseMove(rawOffset: Offset): Boolean {
        if (rawOffset == Offset.Zero) return true
        val aiming = mumuDispatcher?.shootingModeFlow?.value ?: eventHandler.shootingModeFlow.value
        // Cursor speed retains its existing scale. Camera movement uses a separate
        // 1:1 maximum gain, before the imported profile's axis-specific settings.
        val offset = rawOffset * if (aiming) aimSensitivity.value else 10f * pointerSensitivity.value
        val effectiveOffset = if (aiming) {
            offset
        } else {
            // Keep a held cursor-click pointer exactly aligned with the visible
            // cursor. At a screen edge the logical cursor is clamped, so using
            // the original raw delta would let the injected touch drift far
            // outside the display while the image remained at the edge.
            softwareCursor.moveBy(offset)
        }
        if (effectiveOffset == Offset.Zero) {
            cancelIdleShootingPointerRelease()
            return true
        }
        val handled = mumuDispatcher?.let { it.move(effectiveOffset.touchPoint(), softwareCursor.position.touchPoint()); true }
            ?: eventHandler.handlePointerMove(effectiveOffset)
        if (handled && isShootingMode.value) {
            scheduleIdleShootingPointerRelease()
        } else {
            cancelIdleShootingPointerRelease()
        }
        return handled
    }

    private fun handleMouseScroll(verticalDelta: Float): Boolean {
        if (verticalDelta == 0f) return true
        eventHandler.mousePointerPosition = softwareCursor.position
        cancelIdleShootingPointerRelease()
        return mumuDispatcher?.scroll(verticalDelta, softwareCursor.position.touchPoint())
            ?: eventHandler.handleMouseScroll(verticalDelta)
    }

    private fun observeShootingMode() {
        shootingModeJob?.cancel()
        val flow = mumuDispatcher?.shootingModeFlow ?: eventHandler.shootingModeFlow
        _isShootingMode.value = flow.value
        shootingModeJob = scope.launch { flow.collect { _isShootingMode.value = it } }
    }

    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    private fun getStatusBrHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.currentWindowMetrics.windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        } else {
            val resourceId =
                Resources.getSystem().getIdentifier("status_bar_height", "dimen", "android")
            return if (resourceId > 0) Resources.getSystem()
                .getDimensionPixelSize(resourceId) else 0

        }
    }

    private companion object {
        const val AIM_POINTER_IDLE_RELEASE_MILLIS = 100L
    }

    enum class MappingFailure {
        ROOT_CONNECTION_LOST,
        ROOT_CAPTURE_FAILED,
        INPUT_INJECTION_FAILED
    }
}
