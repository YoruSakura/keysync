package com.devoid.keysync.domain

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.DraggableItemType
import com.devoid.keysync.model.EventInjector
import com.devoid.keysync.model.KeyMap
import com.devoid.keysync.model.KeymapType
import com.devoid.keysync.model.MultiModeTouchHandler
import com.devoid.keysync.model.TouchMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow


//class EventHandler(
//    private val eventInjector: EventInjector
//) {
//    private val TAG = "EventHandler"
//    private var items: Map<Set<Int>, KeyMap> = mapOf()//contains key mappings for keycodes
//
//    private var multiModeTouchHandler: MultiModeTouchHandler
//
//    private val pressedKeys =
//        mutableMapOf<Int, Boolean>()
//    private val pointerIds =
//        mutableMapOf<Int, Int?>()//contains bindings for keycodes
//    private val pressedKeysGroup =
//        mutableMapOf<Int, Boolean>()//separate map because groups(like "wasd" ,cancellable) are registered under a const in $pressedKeys
//    private val _shootingModeState =
//        MutableStateFlow<ShootingModeState>(ShootingModeState.Disabled(false))
//    val isShootingMode = _shootingModeState.map { it is ShootingModeState.Enabled }.stateIn(
//        scope = CoroutineScope(Dispatchers.Main),
//        started = SharingStarted.Eagerly,
//        initialValue = false
//    )
//
//
//    var mousePointerPosition = Offset.Zero
//
//    var appConfig = AppConfig.Default
//
//    init {
//        multiModeTouchHandler = TapModeTouchHandler(eventInjector)
//    }
//
//    fun setTouchMode(touchMode: TouchMode) {
//        multiModeTouchHandler = when (touchMode) {
//            TouchMode.TAP -> {
//                TapModeTouchHandler(eventInjector)
//            }
//
//            TouchMode.HOLD -> {
//                HoldModeTouchHandler(eventInjector)
//            }
//
//            TouchMode.MIXED -> {
//                MixedModeTouchHandler(eventInjector)
//            }
//        }
//    }
//
//    fun handleKeyEvent(keyEvent: KeyEvent): Boolean {
//        Log.i(TAG,"key event: $keyEvent")
//        val keyCode = if (isWASD(keyEvent)) KEYCODE_WASD else keyEvent.keyCode
//        if (keyCode == appConfig.shootingModeKeyCode){
//            if (keyEvent.action != MotionEvent.ACTION_DOWN) return true
//            toggleShootingMode(false)
//        }
//        if (pointerIds[keyCode] == null)//no keybinding for $keycode
//            return false
//        when (keyEvent.action) {
//            MotionEvent.ACTION_DOWN -> {
//                val pointerId = pointerIds[keyCode]!!
//                if (keyCode == KEYCODE_WASD) {
//                    if (pressedKeysGroup[keyEvent.keyCode] != true) {
//                        pressedKeys[keyCode] = true
//                        processWasdEvent(keyEvent, pointerId)
//                        pressedKeysGroup[keyEvent.keyCode] = true
//                    }
//                    return true
//                }
//                if (pressedKeys[keyEvent.keyCode] != true) {
//                    pressedKeys[keyCode] = true
//                    processKeyEvent(keyEvent, pointerId)
//                }
//                return true
//            }
//
//            MotionEvent.ACTION_UP -> {
//                if (isWASD(keyEvent)) {
//                    val pointerId = pointerIds[KEYCODE_WASD]!!
//                    pressedKeysGroup[keyEvent.keyCode] = false
//                    processWasdEvent(keyEvent, pointerId)
//                    pressedKeys[KEYCODE_WASD] = false
//                    return true
//                }
//                val pointerId = pointerIds[keyEvent.keyCode]!!
//                processKeyEvent(keyEvent, pointerId)
//                pressedKeys[keyEvent.keyCode] = false
//                return true
//            }
//
//            else -> {
//                return false
//            }
//        }
//    }
//
//
//    private fun processKeyEvent(keyEvent: KeyEvent, pointerId: Int) {
//        if (keyEvent.action == MotionEvent.ACTION_DOWN) {
//            val itemKey = setOf(keyEvent.keyCode)
//            items[itemKey]?.let {
//                if (it.type == KeymapType.CANCELABLE) {
//                    val isPressed = pressedKeysGroup[keyEvent.keyCode] ?: false
//                    val keyMap =
//                        if (isPressed) items[setOf(keyEvent.keyCode, KEYCODE_CANCEL)]!! else it
//                    multiModeTouchHandler.handleTouchEvent(
//                        keyEvent,
//                        isPressed,
//                        pointerId,
//                        keyMap
//                    ).run {
//                        pressedKeysGroup[keyEvent.keyCode] = this
//                    }
//                    toggleShootingMode()
//                    return
//                }
//                eventInjector.injectPointer(pointerId, it.position, it.end!!)
//            }
//        } else if (keyEvent.action == MotionEvent.ACTION_UP) {
//            val itemKey = setOf(keyEvent.keyCode, KEYCODE_CANCEL)
//            items[itemKey]?.let {
//                val isPressed = pressedKeysGroup[keyEvent.keyCode] ?: false
//                multiModeTouchHandler.handleTouchEvent(
//                    keyEvent,
//                    isPressed,
//                    pointerId,
//                    it
//                ).let { shouldToggleShootingMode ->
//                    if (isShootingMode.value == shouldToggleShootingMode) {
//                        pressedKeysGroup[keyEvent.keyCode] = shouldToggleShootingMode
//                        toggleShootingMode()
//                    }
//                }
//            } ?: eventInjector.releasePointer(pointerId)
//        }
//    }
//
//    private fun toggleShootingMode(temp:Boolean=true) {
//        when (_shootingModeState.value) {
//            ShootingModeState.Disabled(temp) -> {
//                _shootingModeState.value = ShootingModeState.Enabled
//                val key = setOf(appConfig.shootingModeKeyCode)
//                val pointerId = pointerIds[appConfig.shootingModeKeyCode]
//                items[key]?.let {
//                    eventInjector.injectPointer(pointerId!!, it.position)
//                }
//            }
//
//            ShootingModeState.Enabled -> {
//                _shootingModeState.value = ShootingModeState.Disabled(temp)
//                val pointerId = pointerIds[appConfig.shootingModeKeyCode]
//                eventInjector.releasePointer(pointerId!!)
//            }
//
//            else -> {}
//        }
//    }
//
//    private fun processWasdEvent(keyEvent: KeyEvent, pointerId: Int) {
//        if (keyEvent.action == MotionEvent.ACTION_DOWN) {
//            val itemKey =
//                getPressedWasdKey()?.let { setOf(it, keyEvent.keyCode) }
//                    ?: setOf(keyEvent.keyCode)
//            items[itemKey]?.let {
//                if (itemKey.size > 1) {
//                    eventInjector.transFormGesture(pointerId, it.position)
//                    return
//                }
//                it.center?.let { center ->
//                    eventInjector.injectGesture(
//                        pointerId,
//                        center,
//                        it.position
//                    )
//                }
//            }
//        } else if (keyEvent.action == MotionEvent.ACTION_UP) {
//            val pressedWASDKey = getPressedWasdKey()
//            pressedWASDKey?.let { keyCode ->//check if other keys(W,A,S,D) are pressed
//                items[setOf(keyCode)]?.let {
//                    eventInjector.transFormGesture(pointerId, it.position)
//                }
//            } ?: eventInjector.releaseGesture(pointerId)
//        }
//    }
//
//    private fun getPressedWasdKey(): Int? {
//        val wasdKeys = listOf(Key.W, Key.A, Key.S, Key.D)
//
//        wasdKeys.forEach { iKey ->
//            if (pressedKeysGroup[iKey.nativeKeyCode] == true) {
//                return iKey.nativeKeyCode
//            }
//        }
//        return null
//    }
//
//    private fun isWASD(keyEvent: KeyEvent): Boolean {
//        return when (keyEvent.keyCode) {
//            Key.W.nativeKeyCode, Key.A.nativeKeyCode, Key.S.nativeKeyCode, Key.D.nativeKeyCode -> {
//                true
//            }
//
//            else -> false
//        }
//    }
//
//    fun handleMouseButton(mouseButton: Int, pressed: Boolean):Boolean {
//        val keyCode = when (mouseButton) {
//            MotionEvent.BUTTON_PRIMARY -> KEYCODE_LMC
//
//            MotionEvent.BUTTON_SECONDARY -> KEYCODE_RMC
//
//            MotionEvent.BUTTON_TERTIARY -> KEYCODE_MMC
//            else -> return false
//        }
//       return when (keyCode) {
//            KEYCODE_LMC, appConfig.fireKeyCode -> {
//                if (!isShootingMode.value) {
//                    simulateNativeClick(KEYCODE_LMC, pressed)
//                    return true
//                }
//                if (KEYCODE_LMC!= appConfig.fireKeyCode) return false
//                val pointerId = pointerIds[KEYCODE_LMC] ?: return false
//                if (pressed) {
//                    val itemKey = setOf(KEYCODE_LMC)
//                    items[itemKey]?.let {
//                        eventInjector.injectPointer(pointerId, it.position, it.end!!)
//                    }
//                } else {
//                    eventInjector.releasePointer(pointerId)
//                }
//                true
//            }
//
//            appConfig.shootingModeKeyCode -> {
//                if (!pressed)
//                    return true
//                toggleShootingMode(false)
//
////                val itemKey = setOf(appConfig.shootingModeKeyCode)
////                if (items[itemKey] == null)
////                    return false
////                val pointerId = pointerIds[appConfig.shootingModeKeyCode]?:return false
////                if (isShootingMode.value) {
////                    eventInjector.releasePointer(pointerId)
////                    _shootingModeState.value = ShootingModeState.Disabled(false)
////                } else {
////                    items[itemKey]?.let {
////                        eventInjector.injectPointer(pointerId, it.position, it.end!!)
////                    }
////                    _shootingModeState.value = ShootingModeState.Enabled
////                }
////                true
//                true
//            }
//
//            KEYCODE_RMC,appConfig.scopeKeyCode -> {
//                if (isShootingMode.value) {
//                    val pointerId = pointerIds[KEYCODE_RMC] ?: return false
//                    if (pressed) {
//                        val itemKey = setOf(KEYCODE_RMC)
//                        items[itemKey]?.let {
//                            eventInjector.injectPointer(pointerId, it.position, it.end!!)
//                        }
//                    } else {
//                        eventInjector.releasePointer(pointerId)
//                    }
//                }
//                true
//            }
//
//           else -> false
//       }
//    }
//
//
//    fun handlePointerMove(position: Offset): Boolean {
//        if (!isShootingMode.value) {
//            val pointerId = pointerIds[KEYCODE_LMC]!!
//            eventInjector.updatePointerPosition(
//                pointerID = pointerId,
//                position
//            )//update the position of visible mouse pressed pointer
//            return true
//        }
//        val pointerId = pointerIds[appConfig.shootingModeKeyCode]
//        pointerId?.let {
//            eventInjector.updatePointerPosition(it, position)
//            return true
//        } ?: return false
//    }
//
//    private fun simulateNativeClick(keyCode: Int, isPressed: Boolean) {
//        val pointerId = pointerIds[keyCode]!!
//        if (isPressed) {
//            eventInjector.injectPointer(pointerId, mousePointerPosition)
//        } else {
//            eventInjector.releasePointer(pointerId)
//        }
//    }
//
//    fun clear() {
//        eventInjector.clear()
//        pressedKeys.clear()
//        pressedKeysGroup.clear()
//        _shootingModeState.value = ShootingModeState.Disabled(false)
//    }
//
//    fun updateKeyMapping(items: List<DraggableItem>) {
//        val map = hashMapOf<Set<Int>, KeyMap>()
//        pointerIds.clear()
//        items.forEach {
//            Log.i(TAG,"adding keymaping for: $it")
//            when (it) {
//                is DraggableItem.WASDGroup -> {
//                    map[setOf(Key.W.nativeKeyCode)] = KeyMap(position = it.w, center = it.center)
//                    map[setOf(Key.A.nativeKeyCode)] = KeyMap(position = it.a, center = it.center)
//                    map[setOf(Key.S.nativeKeyCode)] = KeyMap(position = it.s, center = it.center)
//                    map[setOf(Key.D.nativeKeyCode)] = KeyMap(position = it.d, center = it.center)
//                    val wd = Offset(
//                        x = it.w.x + ((it.d.x - it.w.x) / 2),
//                        y = it.w.y + ((it.d.y - it.w.y) / 2)
//                    )
//                    val wa = Offset(
//                        x = it.w.x - ((it.w.x - it.a.x) / 2),
//                        y = it.w.y + ((it.a.y - it.w.y) / 2)
//                    )
//                    val sd = Offset(
//                        x = it.s.x + ((it.d.x - it.s.x) / 2),
//                        y = it.s.y + ((it.d.y - it.s.y) / 2)
//                    )
//                    val sa = Offset(
//                        x = it.a.x + ((it.s.x - it.a.x) / 2),
//                        y = it.a.y + ((it.s.y - it.a.y) / 2)
//                    )
//                    map[setOf(Key.W.nativeKeyCode, Key.A.nativeKeyCode)] =
//                        KeyMap(position = wa, center = it.center)
//                    map[setOf(Key.W.nativeKeyCode, Key.D.nativeKeyCode)] =
//                        KeyMap(position = wd, center = it.center)
//                    map[setOf(Key.S.nativeKeyCode, Key.D.nativeKeyCode)] =
//                        KeyMap(position = sd, center = it.center)
//                    map[setOf(Key.S.nativeKeyCode, Key.A.nativeKeyCode)] =
//                        KeyMap(position = sa, center = it.center)
//
//                    if (!pointerIds.containsKey(KEYCODE_WASD)) {
//                        pointerIds[KEYCODE_WASD] = pointerIds.size
//                    }
//                }
//
//                is DraggableItem.VariableKey -> {
//                    it.keyCode?.let { keyCode ->
//                        var position =
//                            it.position
//                        val shrinkSize =
//                            it.size / 3f  //shrink the size so that artificial taps can be accurate on circular buttons
//                        val end =
//                            position + Offset(x = it.size - shrinkSize, y = it.size - shrinkSize)
//                        position = Offset(position.x + shrinkSize, position.y + shrinkSize)
//
//                        map[setOf(keyCode)] =
//                            KeyMap(position = position, end = end)
//                        if (!pointerIds.containsKey(keyCode)) {
//                            pointerIds[keyCode] = pointerIds.size
//                        }
//                    }
//                }
//
//                is DraggableItem.FixedKey -> {
//                   val keyCode= when(it.type){
//                        DraggableItemType.SHOOTING_MODE -> appConfig.shootingModeKeyCode
//                        DraggableItemType.FIRE -> appConfig.fireKeyCode
//                        DraggableItemType.SCOPE -> appConfig.scopeKeyCode
//                        else->it.keyCode
//                    }
//                    it.apply {
//                        val shrinkSize =
//                            size / 3f                                     //shrink the size so that artificial taps can be accurate on circular buttons
//                        val end = position + Offset(x = size - shrinkSize, y = size - shrinkSize)
//                        position = Offset(position.x + shrinkSize, position.y + shrinkSize)
//                        map[setOf(keyCode)] =
//                            KeyMap(position = position, end = end)
//                        if (!pointerIds.containsKey(keyCode)) {
//                            pointerIds[keyCode] = pointerIds.size
//                        }
//                    }
//
//                }
//
//                is DraggableItem.CancelableKey -> {
//                    if (it.keyCode == null)
//                        return@forEach
//                    var position =
//                        it.position
//                    var cancelPosition =
//                        it.cancelPosition
//                    val shrinkSize =
//                        it.size / 3f                                     //shrink the size so that artificial taps can be accurate on circular buttons
//                    val end = position + Offset(x = it.size - shrinkSize, y = it.size - shrinkSize)
//                    val cancelEnd =
//                        cancelPosition + Offset(x = it.size - shrinkSize, y = it.size - shrinkSize)
//                    position = Offset(position.x + shrinkSize, position.y + shrinkSize)
//                    cancelPosition =
//                        Offset(cancelPosition.x + shrinkSize, cancelPosition.y + shrinkSize)
//                    map[setOf(it.keyCode!!)] =
//                        KeyMap(type = KeymapType.CANCELABLE, position = position, end = end)
//
//                    map[setOf(it.keyCode!!, KEYCODE_CANCEL)] =
//                        KeyMap(
//                            type = KeymapType.CANCELABLE,
//                            position = cancelPosition,
//                            end = cancelEnd
//                        )
//                    pointerIds[it.keyCode!!] = pointerIds.size
//                }
//            }
//        }
//        if (!pointerIds.containsKey(KEYCODE_LMC)) {
//            pointerIds[KEYCODE_LMC] = pointerIds.size
//        }
//        this.items = map
//    }
//
//}
private const val MASK_W = 1 shl 0
private const val MASK_A = 1 shl 1
private const val MASK_S = 1 shl 2
private const val MASK_D = 1 shl 3

// Synthetic mouse codes must never overlap Android KeyEvent codes. Earlier
// versions used 64/128/256, which are real EXPLORER/MEDIA_CLOSE/TV_CONTENTS_MENU
// key codes and could make a physical key corrupt mouse mapping state.
const val KEYCODE_LMC = -1002//mouse left click
const val KEYCODE_RMC = -1003//mouse right click
const val KEYCODE_MMC = -1004//mouse middle click

internal fun migrateLegacyMouseKeyCode(keyCode: Int): Int = when (keyCode) {
    LEGACY_BITMASK_LMC, LEGACY_ORIGINAL_LMC -> KEYCODE_LMC
    LEGACY_BITMASK_RMC, LEGACY_ORIGINAL_RMC -> KEYCODE_RMC
    LEGACY_BITMASK_MMC, LEGACY_ORIGINAL_MMC -> KEYCODE_MMC
    else -> keyCode
}

internal fun AppConfig.migrateLegacyMouseKeyCodes(): AppConfig = copy(
    shootingModeKeyCode = migrateLegacyMouseKeyCode(shootingModeKeyCode),
    fireKeyCode = migrateLegacyMouseKeyCode(fireKeyCode),
    scopeKeyCode = migrateLegacyMouseKeyCode(scopeKeyCode)
)

internal fun DraggableItem.migrateLegacyMouseKeyCode(): DraggableItem = when (this) {
    is DraggableItem.VariableKey -> copy(
        keyCode = keyCode?.let(::migrateLegacyMouseKeyCode)
    )
    is DraggableItem.FixedKey -> copy(
        keyCode = migrateLegacyMouseKeyCode(keyCode)
    )
    is DraggableItem.CancelableKey -> copy(
        keyCode = keyCode?.let(::migrateLegacyMouseKeyCode)
    )
    is DraggableItem.WASDGroup -> this
}

private const val LEGACY_BITMASK_LMC = 1 shl 6
private const val LEGACY_BITMASK_RMC = 1 shl 7
private const val LEGACY_BITMASK_MMC = 1 shl 8
private const val LEGACY_ORIGINAL_LMC = 1002
private const val LEGACY_ORIGINAL_RMC = 1003
private const val LEGACY_ORIGINAL_MMC = 1004

fun mouseButtonToKeyCode(button: Int): Int? = when (button) {
    MotionEvent.BUTTON_PRIMARY -> KEYCODE_LMC
    MotionEvent.BUTTON_SECONDARY -> KEYCODE_RMC
    MotionEvent.BUTTON_TERTIARY -> KEYCODE_MMC
    else -> null
}

fun isMouseButtonKeyCode(keyCode: Int): Boolean =
    keyCode == KEYCODE_LMC || keyCode == KEYCODE_RMC || keyCode == KEYCODE_MMC


private fun keyToWasdMask(keyCode: Int): Int = when (keyCode) {
    Key.W.nativeKeyCode -> MASK_W
    Key.A.nativeKeyCode -> MASK_A
    Key.S.nativeKeyCode -> MASK_S
    Key.D.nativeKeyCode -> MASK_D
    else -> 0
}

class EventHandler(
    private val eventInjector: EventInjector
) {
    val usesTouchCore = eventInjector is com.devoid.keysync.domain.touch.TouchCoreEventInjector
    private var cameraController: com.devoid.keysync.domain.touch.CameraGestureController? = null

    /* ---------- state ---------- */

    private val keyMap = HashMap<Int, KeyMap>(64)
    private val cancelKeyMap = HashMap<Int, KeyMap>(16)
    private val wasdKeyMap = HashMap<Int, KeyMap>(8)
    private val explicitlyMappedMouseKeys = HashSet<Int>(3)
    private val aimingOnlyMouseKeys = HashSet<Int>(2)

    private val physicalKeysDown = HashSet<Int>(16)
    private val normalKeyStates = HashMap<Int, Boolean>(64)
    private val cancelToggle = HashMap<Int, Boolean>(16)
    private val pointerIds = HashMap<Int, Int>(64)
    private var wasdPointerId: Int? = null

    private var wasdMask = 0
    private var lastWasdMask = 0
    private val nativePointersDown = HashSet<Int>(3)
    private var shootingPointerActive = false
    private var shootingPointerPosition: Offset? = null
    private var maximumPointerPosition = Offset(Float.MAX_VALUE, Float.MAX_VALUE)

    @Volatile
    private var shootingMode = false
    private val _shootingModeFlow = MutableStateFlow(false)
    val shootingModeFlow = _shootingModeFlow.asStateFlow()


    var mousePointerPosition = Offset.Zero
    var appConfig = AppConfig.Default

    @Synchronized fun setPointerBounds(maximumPosition: Offset) {
        if (
            !maximumPosition.x.isFinite() || !maximumPosition.y.isFinite() ||
            maximumPosition.x < 0f || maximumPosition.y < 0f
        ) return
        if (maximumPointerPosition == maximumPosition) return
        maximumPointerPosition = maximumPosition
        // A rotation or window-size change invalidates the coordinate space of
        // an active camera finger. End it now; the next relative mouse event
        // starts a clean gesture at the newly clamped aiming anchor.
        releaseShootingPointer()
        if (usesTouchCore && shootingMode) enterCoreCamera()
    }

    private var cancelableTouchHandler: MultiModeTouchHandler = MixedModeTouchHandler(eventInjector)
    // Regular mappings always mirror the lifetime of the physical key press.
    // A quick down/up is a tap; keeping the key down is a long press.
    private val normalTouchHandler: MultiModeTouchHandler = HoldModeTouchHandler(eventInjector)


    /* ---------- touch mode ---------- */

    @Synchronized fun setCancelableTouchMode(mode: TouchMode) {
        cancelableTouchHandler = when (mode) {
            TouchMode.TAP -> CancelableTapModeTouchHandler(eventInjector)
            TouchMode.HOLD -> CancelableHoldModeTouchHandler(eventInjector)
            TouchMode.MIXED -> CancelableMixedModeTouchHandler(eventInjector)
        }
    }
    private fun setShootingMode(enabled: Boolean) {
        if (shootingMode == enabled) return

        shootingMode = enabled
        _shootingModeFlow.value = enabled
    }

    private fun changeShootingMode(enabled: Boolean): Boolean {
        if (shootingMode == enabled) return true

        val key = appConfig.shootingModeKeyCode
        val pointerId = pointerIds[key]

        if (enabled) {
            if (keyMap[key] == null) return false
            if (pointerId == null) return false
            setShootingMode(true)
            if (usesTouchCore) enterCoreCamera() else rebuildTouchStreamForShooting()
        } else {
            setShootingMode(false)
            releaseShootingPointer()
        }
        return true
    }

    private fun enterCoreCamera() {
        val injector = eventInjector as? com.devoid.keysync.domain.touch.TouchCoreEventInjector ?: return
        val key = appConfig.shootingModeKeyCode
        val id = pointerIds[key] ?: return
        val mapping = keyMap[key] ?: return
        val maxX = maximumPointerPosition.x.toDouble().coerceAtLeast(2.0)
        val maxY = maximumPointerPosition.y.toDouble().coerceAtLeast(2.0)
        val anchor = com.devoid.keysync.domain.touch.TouchPoint(
            mapping.position.x.toDouble().coerceIn(1.0, maxX - 1.0),
            mapping.position.y.toDouble().coerceIn(1.0, maxY - 1.0))
        cameraController = com.devoid.keysync.domain.touch.CameraGestureController(
            com.devoid.keysync.domain.touch.ContactId(id), injector.pipeline::submit).also {
            it.enter(com.devoid.keysync.domain.touch.CameraConfig(anchor,
                com.devoid.keysync.domain.touch.TouchRect(0.0, 0.0, maxX, maxY)))
        }
    }

    /**
     * All synthetic fingers share one Android multi-touch stream. If the game
     * drops only part of that stream, adding another camera pointer cannot
     * reliably repair it. Start a fresh stream and restore inputs that are
     * still physically held. The camera pointer is created lazily on the next
     * mouse movement so it never has to survive a menu or dialogue transition.
     */
    private fun rebuildTouchStreamForShooting() {
        eventInjector.clear()
        nativePointersDown.clear()
        shootingPointerActive = false
        shootingPointerPosition = null

        val effectiveWasdMask = effectiveWasdMask()
        val activeWasdMapping = wasdKeyMap[effectiveWasdMask]
        val activeWasdPointerId = wasdPointerId
        if (
            effectiveWasdMask != 0 &&
            activeWasdMapping != null &&
            activeWasdPointerId != null &&
            activeWasdMapping.center != null
        ) {
            eventInjector.injectGesture(
                activeWasdPointerId,
                activeWasdMapping.center,
                activeWasdMapping.position
            )
            lastWasdMask = effectiveWasdMask
        } else {
            lastWasdMask = 0
        }

        physicalKeysDown.forEach { keyCode ->
            if (
                keyCode == appConfig.shootingModeKeyCode ||
                keyToWasdMask(keyCode) != 0 ||
                normalKeyStates[keyCode] != true
            ) return@forEach

            val mapping = keyMap[keyCode] ?: return@forEach
            val pointerId = pointerIds[keyCode] ?: return@forEach
            val end = mapping.end ?: return@forEach
            eventInjector.injectPointer(pointerId, mapping.position, end)
        }

    }


    /* ---------- key events ---------- */

    fun handleKeyEvent(event: KeyEvent): Boolean =
        handleKeyEvent(event.keyCode, event.action)

    @Synchronized internal fun handleKeyEvent(keyCode: Int, action: Int): Boolean {
        val wasdBit = keyToWasdMask(keyCode)
        val pointerId = if (wasdBit != 0) wasdPointerId else pointerIds[keyCode]
        if (pointerId == null) return false

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (!physicalKeysDown.add(keyCode)) return true

                if (wasdBit != 0) {
                    wasdMask = wasdMask or wasdBit
                    handleWasd(pointerId)
                } else {
                    handleKeyDown(keyCode, pointerId)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                physicalKeysDown.remove(keyCode)

                if (wasdBit != 0) {
                    wasdMask = wasdMask and wasdBit.inv()
                    handleWasd(pointerId)
                } else {
                    handleKeyUp(keyCode, pointerId)
                }
                return true
            }
        }
        return false
    }

    /* ---------- WASD ---------- */

    private fun handleWasd(pointerId: Int) {
        val effectiveMask = effectiveWasdMask()
        if (effectiveMask == 0) {
            if (usesTouchCore && wasdMask != 0) {
                val center = wasdKeyMap.values.firstOrNull()?.center ?: return
                if (lastWasdMask == 0) eventInjector.injectGesture(pointerId, center, center)
                else eventInjector.transFormGesture(pointerId, center)
                lastWasdMask = 16 // Held opposite directions: centre, not a released contact.
                return
            }
            eventInjector.releaseGesture(pointerId)
            lastWasdMask = 0
            return
        }

        val mapping = wasdKeyMap[effectiveMask] ?: return
        if (lastWasdMask != 0) {
            eventInjector.transFormGesture(pointerId, mapping.position)
        } else {
            val center = mapping.center ?: return
            eventInjector.injectGesture(pointerId, center, mapping.position)
        }
        lastWasdMask = effectiveMask
    }

    private fun effectiveWasdMask(): Int {
        var effectiveMask = wasdMask
        if (effectiveMask and MASK_W != 0 && effectiveMask and MASK_S != 0) {
            effectiveMask = effectiveMask and (MASK_W or MASK_S).inv()
        }
        if (effectiveMask and MASK_A != 0 && effectiveMask and MASK_D != 0) {
            effectiveMask = effectiveMask and (MASK_A or MASK_D).inv()
        }
        return effectiveMask
    }


    /* ---------- normal keys ---------- */

    private fun handleKeyDown(keyCode: Int, pointerId: Int) {
        keyMap[keyCode]?.let {
            if (keyCode == appConfig.shootingModeKeyCode) {
                toggleShootingMode()
                return
            }
            if (it.type == KeymapType.CANCELABLE) {
                val isToggled = cancelToggle[keyCode] ?: false
                val mapping = if (isToggled) cancelKeyMap[keyCode] ?: return else it
                cancelToggle[keyCode] = cancelableTouchHandler.handleTouchEvent(
                    MotionEvent.ACTION_DOWN,
                    isToggled,
                    pointerId,
                    mapping
                )
            } else {
                val isPressed = normalKeyStates[keyCode] ?: false
                normalKeyStates[keyCode] = normalTouchHandler.handleTouchEvent(
                    MotionEvent.ACTION_DOWN,
                    isPressed,
                    pointerId,
                    it
                )
            }
        }
    }

    private fun handleKeyUp(keyCode: Int, pointerId: Int) {
        keyMap[keyCode]?.let {
            if (keyCode == appConfig.shootingModeKeyCode) return
            if (it.type == KeymapType.CANCELABLE){
                val isToggled = cancelToggle[keyCode] ?: false
                val mapping = if (isToggled) cancelKeyMap[keyCode] ?: return else it
                cancelToggle[keyCode] = cancelableTouchHandler.handleTouchEvent(
                    MotionEvent.ACTION_UP,
                    isToggled,
                    pointerId,
                    mapping
                )
                return
            }
            val isPressed = normalKeyStates[keyCode] ?: false
            normalKeyStates[keyCode] = normalTouchHandler.handleTouchEvent(
                MotionEvent.ACTION_UP,
                isPressed,
                pointerId,
                it
            )
        } ?: eventInjector.releasePointer(pointerId)
    }

    /* ---------- mouse ---------- */

    @Synchronized fun handleMouseButton(button: Int, pressed: Boolean): Boolean {
        val keyCode = mouseButtonToKeyCode(button) ?: return false
        if (keyCode == appConfig.shootingModeKeyCode) {
            if (!pressed) return true
            return toggleShootingMode()
        }
        val pointerId = pointerIds[keyCode] ?: return false
        // A click commonly confirms dialogue or dismisses a reward screen.
        // End the old camera drag first so a game-cancelled touch target cannot
        // leak across that UI boundary. Movement while the button is held can
        // immediately establish a new camera pointer.
        if (pressed && shootingMode && !usesTouchCore) releaseShootingPointer()

        // Fire/scope are contextual mouse bindings: while aiming they press
        // their configured game controls, but cursor mode must use the visible
        // software-cursor position so menus remain clickable. User-created
        // variable/cancelable mouse mappings remain explicit in both modes.
        val useCursorClick = !shootingMode && (
            keyCode !in explicitlyMappedMouseKeys || keyCode in aimingOnlyMouseKeys
            )
        if (useCursorClick) {
            if (pressed) {
                // A game can discard a synthetic multi-touch stream while it
                // opens a menu without notifying us. Starting a cursor click
                // as another POINTER_DOWN on that stale stream makes many UIs
                // ignore it. Make every new cursor press an explicit gesture
                // boundary so its first injected event is always ACTION_DOWN.
                if (!usesTouchCore) {
                    eventInjector.clear()
                    nativePointersDown.clear()
                }
                nativePointersDown.add(pointerId)
            } else {
                nativePointersDown.remove(pointerId)
            }
            simulateNativeClick(pointerId = pointerId, pressed = pressed)
            return true
        }

        if (keyCode in explicitlyMappedMouseKeys) {
            if (pressed) {
                if (!physicalKeysDown.add(keyCode)) return true
                handleKeyDown(keyCode, pointerId)
            } else {
                physicalKeysDown.remove(keyCode)
                handleKeyUp(keyCode, pointerId)
            }
            return true
        }

        keyMap[keyCode]?.let {
            if (pressed)
                eventInjector.injectPointer(pointerId, it.position, it.end!!)
            else
                eventInjector.releasePointer(pointerId)
        }
        return true
    }

    @Synchronized fun handlePointerMove(position: Offset): Boolean {
        if (!position.x.isFinite() || !position.y.isFinite()) return false
        if (!shootingMode) {
            nativePointersDown.forEach { pointerId ->
                eventInjector.updatePointerPosition(pointerId, position)
            }
            return true
        }

        if (usesTouchCore) {
            cameraController?.move(com.devoid.keysync.domain.touch.TouchPoint(position.x.toDouble(), position.y.toDouble()))
            return true
        }
        val shootingKey = appConfig.shootingModeKeyCode
        val pointerId = pointerIds[shootingKey] ?: return false
        val mapping = keyMap[shootingKey] ?: return false
        val anchor = clampPointerPosition(mapping.position)
        if (!shootingPointerActive) {
            eventInjector.injectPointer(pointerId, anchor)
            shootingPointerActive = true
            shootingPointerPosition = anchor
        }
        var currentPosition = shootingPointerPosition ?: anchor
        var targetPosition = currentPosition + position
        if (!isWithinPointerBounds(targetPosition) && currentPosition != anchor) {
            eventInjector.rebasePointer(pointerId, anchor)
            currentPosition = anchor
            targetPosition = anchor + position
        }
        targetPosition = clampPointerPosition(targetPosition)
        val effectiveOffset = targetPosition - currentPosition
        if (effectiveOffset != Offset.Zero) {
            eventInjector.updatePointerPosition(pointerId, effectiveOffset)
        }
        shootingPointerPosition = targetPosition
        return true
    }

    /** Converts a wheel notch into a vertical finger swipe at the cursor. */
    @Synchronized fun handleMouseScroll(verticalDelta: Float): Boolean {
        if (shootingMode || !verticalDelta.isFinite() || verticalDelta == 0f) return false

        val start = clampPointerPosition(mousePointerPosition)
        val notchCount = kotlin.math.abs(verticalDelta).coerceIn(1f, MAX_SCROLL_NOTCHES)
        val baseDistance = ((maximumPointerPosition.y + 1f) * SCROLL_HEIGHT_FRACTION)
            .coerceIn(MIN_SCROLL_DISTANCE, MAX_SCROLL_DISTANCE)
        val direction = if (verticalDelta > 0f) -1f else 1f
        val end = clampPointerPosition(
            start + Offset(0f, direction * baseDistance * notchCount)
        )
        if (end == start) return false

        // Like cursor clicks, scrolling must not join a stale game gesture.
        // It is also a natural boundary for held synthetic pointers: hardware
        // events received after the swipe establish their own clean stream.
        if (!usesTouchCore) {
            eventInjector.clear()
            resetTransientInputState()
        }
        eventInjector.injectSwipe(if (usesTouchCore) -10_001 else SCROLL_POINTER_ID, start, end)
        return true
    }

    private fun isWithinPointerBounds(position: Offset): Boolean =
        position.x in 0f..maximumPointerPosition.x &&
            position.y in 0f..maximumPointerPosition.y

    private fun clampPointerPosition(position: Offset): Offset = Offset(
        position.x.coerceIn(0f, maximumPointerPosition.x),
        position.y.coerceIn(0f, maximumPointerPosition.y)
    )

    /**
     * Ends only the physical camera drag while keeping logical aiming enabled.
     * Games frequently cancel a touch target while opening dialogue or reward
     * UI. A later mouse movement can then start a clean camera gesture instead
     * of joining that stale multi-touch stream.
     */
    @Synchronized fun releaseIdleShootingPointer() {
        if (usesTouchCore) return
        if (!shootingMode) return
        releaseShootingPointer()
    }

    private fun releaseShootingPointer() {
        cameraController?.exit()
        cameraController = null
        shootingPointerPosition = null
        if (!shootingPointerActive) return

        shootingPointerActive = false
        pointerIds[appConfig.shootingModeKeyCode]
            ?.let(eventInjector::releasePointer)
    }

    /* ---------- shooting mode ---------- */

    private fun toggleShootingMode(): Boolean = changeShootingMode(!shootingMode)

    @Synchronized internal fun pauseForInputCaptureLoss(): Boolean {
        val shouldResumeShootingMode = shootingMode
        eventInjector.clear()
        resetTransientInputState()
        setShootingMode(false)
        return shouldResumeShootingMode
    }

    @Synchronized internal fun resumeShootingMode(): Boolean = changeShootingMode(true)


    private fun simulateNativeClick(
        pointerId: Int,
        position: Offset = mousePointerPosition,
        pressed: Boolean
    ) {
        if (pressed)
            eventInjector.injectPointer(pointerId, position)
        else
            eventInjector.releasePointer(pointerId)
    }

    /* ---------- mapping ---------- */

    @Synchronized fun updateKeyMapping(items: List<DraggableItem>) {
        if (usesTouchCore) clear()
        keyMap.clear()
        cancelKeyMap.clear()
        wasdKeyMap.clear()
        explicitlyMappedMouseKeys.clear()
        aimingOnlyMouseKeys.clear()
        pointerIds.clear()
        physicalKeysDown.clear()
        normalKeyStates.clear()
        cancelToggle.clear()
        nativePointersDown.clear()
        wasdPointerId = null
        wasdMask = 0
        lastWasdMask = 0
        var nextPointer = 0

        fun alloc(key: Int) {
            pointerIds.getOrPut(key) { nextPointer++ }
        }

        items.forEach { item ->
            when (item) {
                is DraggableItem.WASDGroup -> {
                    wasdKeyMap[MASK_W] = KeyMap(position = item.w, center = item.center)
                    wasdKeyMap[MASK_A] = KeyMap(position = item.a, center = item.center)
                    wasdKeyMap[MASK_S] = KeyMap(position = item.s, center = item.center)
                    wasdKeyMap[MASK_D] = KeyMap(position = item.d, center = item.center)
                    val wa = Offset(
                        x = item.w.x - ((item.w.x - item.a.x) / 2),
                        y = item.w.y + ((item.a.y - item.w.y) / 2)
                    )
                    val wd = Offset(
                        x = item.w.x + ((item.d.x - item.w.x) / 2),
                        y = item.w.y + ((item.d.y - item.w.y) / 2)
                    )
                    val sa = Offset(
                        x = item.a.x + ((item.s.x - item.a.x) / 2),
                        y = item.a.y + ((item.s.y - item.a.y) / 2)
                    )
                    val sd = Offset(
                        x = item.s.x + ((item.d.x - item.s.x) / 2),
                        y = item.s.y + ((item.d.y - item.s.y) / 2)
                    )
                    fun diagonal(midpoint: Offset): Offset = if (usesTouchCore)
                        item.center + (midpoint - item.center) * kotlin.math.sqrt(2f) else midpoint
                    wasdKeyMap[MASK_W or MASK_A] = KeyMap(position = diagonal(wa), center = item.center)
                    wasdKeyMap[MASK_W or MASK_D] = KeyMap(position = diagonal(wd), center = item.center)
                    wasdKeyMap[MASK_S or MASK_A] = KeyMap(position = diagonal(sa), center = item.center)
                    wasdKeyMap[MASK_S or MASK_D] = KeyMap(position = diagonal(sd), center = item.center)

                    if (wasdPointerId == null) wasdPointerId = nextPointer++
                }

                is DraggableItem.VariableKey -> {
                    val k = item.keyCode ?: return@forEach
                    if (isMouseButtonKeyCode(k)) explicitlyMappedMouseKeys.add(k)
                    val (position, end) = touchBounds(item.position, item.size)

                    keyMap[k] = KeyMap(position = position, end = end)
                    alloc(k)
                }

                is DraggableItem.FixedKey -> {
                    val k = when (item.type) {
                        DraggableItemType.SHOOTING_MODE -> appConfig.shootingModeKeyCode
                        DraggableItemType.FIRE -> appConfig.fireKeyCode
                        DraggableItemType.SCOPE -> appConfig.scopeKeyCode
                        else -> item.keyCode
                    }
                    if (isMouseButtonKeyCode(k)) {
                        explicitlyMappedMouseKeys.add(k)
                        if (
                            item.type == DraggableItemType.FIRE ||
                            item.type == DraggableItemType.SCOPE
                        ) {
                            aimingOnlyMouseKeys.add(k)
                        }
                    }
                    val (position, end) = touchBounds(item.position, item.size)
                    keyMap[k] = KeyMap(position = position, end = end)
                    alloc(k)
                }

                is DraggableItem.CancelableKey -> {
                    val k = item.keyCode ?: return@forEach
                    if (isMouseButtonKeyCode(k)) explicitlyMappedMouseKeys.add(k)
                    val (position, end) = touchBounds(item.position, item.size)
                    val (cancelPosition, cancelEnd) = touchBounds(item.cancelPosition, item.size)
                    keyMap[k] =
                        KeyMap(type = KeymapType.CANCELABLE, position = position, end = end)
                    cancelKeyMap[k] =
                        KeyMap(
                            type = KeymapType.CANCELABLE,
                            position = cancelPosition,
                            end = cancelEnd
                        )
                    alloc(k)
                }
            }
        }

        alloc(KEYCODE_LMC)
    }

    private fun touchBounds(position: Offset, size: Int): Pair<Offset, Offset> {
        val safeSize = size.coerceAtLeast(0).toFloat()
        val inset = safeSize / 3f
        return Offset(position.x + inset, position.y + inset) to
            Offset(position.x + safeSize - inset, position.y + safeSize - inset)
    }

    @Synchronized fun clear() {
        eventInjector.clear()
        resetTransientInputState()
        setShootingMode(false)
    }

    @Synchronized fun resetForGameSwitch() {
        eventInjector.cancelSystemTouch()
        resetTransientInputState()
        setShootingMode(false)
    }

    @Synchronized fun shutdown() {
        resetTransientInputState()
        setShootingMode(false)
        eventInjector.shutdown()
    }

    private fun resetTransientInputState() {
        cameraController?.abandon()
        cameraController = null
        physicalKeysDown.clear()
        normalKeyStates.clear()
        cancelToggle.clear()
        wasdMask = 0
        lastWasdMask = 0
        nativePointersDown.clear()
        shootingPointerActive = false
        shootingPointerPosition = null
    }

    private companion object {
        const val SCROLL_POINTER_ID = 9
        const val SCROLL_HEIGHT_FRACTION = 0.12f
        const val MIN_SCROLL_DISTANCE = 96f
        const val MAX_SCROLL_DISTANCE = 320f
        const val MAX_SCROLL_NOTCHES = 3f
    }
}
