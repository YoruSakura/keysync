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

const val KEYCODE_LMC = 1 shl 6//mouse left click
const val KEYCODE_RMC = 1 shl 7//mouse right click
const val KEYCODE_MMC = 1 shl 8//mouse middle click

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

    /* ---------- state ---------- */

    private val keyMap = HashMap<Int, KeyMap>(64)
    private val cancelKeyMap = HashMap<Int, KeyMap>(16)
    private val wasdKeyMap = HashMap<Int, KeyMap>(8)
    private val explicitlyMappedMouseKeys = HashSet<Int>(3)

    private val physicalKeysDown = HashSet<Int>(16)
    private val normalKeyStates = HashMap<Int, Boolean>(64)
    private val cancelToggle = HashMap<Int, Boolean>(16)
    private val pointerIds = HashMap<Int, Int>(64)
    private var wasdPointerId: Int? = null

    private var wasdMask = 0
    private var lastWasdMask = 0

    @Volatile
    private var shootingMode = false
    private val _shootingModeFlow = MutableStateFlow(false)
    val shootingModeFlow = _shootingModeFlow.asStateFlow()


    var mousePointerPosition = Offset.Zero
    var appConfig = AppConfig.Default

    private var cancelableTouchHandler: MultiModeTouchHandler = MixedModeTouchHandler(eventInjector)
    // Regular mappings always mirror the lifetime of the physical key press.
    // A quick down/up is a tap; keeping the key down is a long press.
    private val normalTouchHandler: MultiModeTouchHandler = HoldModeTouchHandler(eventInjector)


    /* ---------- touch mode ---------- */

    fun setCancelableTouchMode(mode: TouchMode) {
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
            val mapping = keyMap[key] ?: return false
            if (pointerId == null) return false
            setShootingMode(true)
            eventInjector.injectPointer(pointerId, mapping.position)
        } else {
            setShootingMode(false)
            pointerId?.let(eventInjector::releasePointer)
        }
        return true
    }


    /* ---------- key events ---------- */

    fun handleKeyEvent(event: KeyEvent): Boolean =
        handleKeyEvent(event.keyCode, event.action)

    internal fun handleKeyEvent(keyCode: Int, action: Int): Boolean {
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
        if (wasdMask == 0) {
            eventInjector.releaseGesture(pointerId)
            lastWasdMask = 0
            return
        }

        val mapping = wasdKeyMap[wasdMask] ?: return
        if (lastWasdMask != 0) {
            eventInjector.transFormGesture(pointerId, mapping.position)
        } else {
            val center = mapping.center ?: return
            eventInjector.injectGesture(pointerId, center, mapping.position)
        }
        lastWasdMask = wasdMask
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

    fun handleMouseButton(button: Int, pressed: Boolean): Boolean {
        val keyCode = mouseButtonToKeyCode(button) ?: return false
        if (keyCode == appConfig.shootingModeKeyCode) {
            if (!pressed) return true
            return toggleShootingMode()
        }
        val pointerId = pointerIds[keyCode] ?: return false

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

        if (!shootingMode) {
            simulateNativeClick(pointerId = pointerId, pressed = pressed)
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

    fun handlePointerMove(position: Offset): Boolean {
        val pointerId = if (shootingMode)
            pointerIds[appConfig.shootingModeKeyCode]
        else
            pointerIds[KEYCODE_LMC]

        pointerId?.let {
            eventInjector.updatePointerPosition(it, position)
            return true
        }
        return false
    }

    /* ---------- shooting mode ---------- */

    private fun toggleShootingMode(): Boolean = changeShootingMode(!shootingMode)

    internal fun pauseForInputCaptureLoss(): Boolean {
        val shouldResumeShootingMode = shootingMode
        eventInjector.clear()
        resetTransientInputState()
        setShootingMode(false)
        return shouldResumeShootingMode
    }

    internal fun resumeShootingMode(): Boolean = changeShootingMode(true)


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

    fun updateKeyMapping(items: List<DraggableItem>) {
        keyMap.clear()
        cancelKeyMap.clear()
        wasdKeyMap.clear()
        explicitlyMappedMouseKeys.clear()
        pointerIds.clear()
        physicalKeysDown.clear()
        normalKeyStates.clear()
        cancelToggle.clear()
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
                    wasdKeyMap[MASK_W or MASK_A] = KeyMap(position = wa, center = item.center)
                    wasdKeyMap[MASK_W or MASK_D] = KeyMap(position = wd, center = item.center)
                    wasdKeyMap[MASK_S or MASK_A] = KeyMap(position = sa, center = item.center)
                    wasdKeyMap[MASK_S or MASK_D] = KeyMap(position = sd, center = item.center)

                    if (wasdPointerId == null) wasdPointerId = nextPointer++
                }

                is DraggableItem.VariableKey -> {
                    val k = item.keyCode ?: return@forEach
                    if (isMouseButtonKeyCode(k)) explicitlyMappedMouseKeys.add(k)
                    var position = item.position
                    val shrinkSize =
                        item.size / 3f  //shrink the size so that artificial taps can be accurate on circular buttons
                    val end =
                        position + Offset(x = item.size - shrinkSize, y = item.size - shrinkSize)
                    position = Offset(position.x + shrinkSize, position.y + shrinkSize)

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
                    var position = item.position
                    val shrinkSize =
                        item.size / 3f  //shrink the size so that artificial taps can be accurate on circular buttons
                    val end =
                        position + Offset(x = item.size - shrinkSize, y = item.size - shrinkSize)
                    position = Offset(position.x + shrinkSize, position.y + shrinkSize)
                    keyMap[k] = KeyMap(position = position, end = end)
                    alloc(k)
                }

                is DraggableItem.CancelableKey -> {
                    val k = item.keyCode ?: return@forEach
                    if (isMouseButtonKeyCode(k)) explicitlyMappedMouseKeys.add(k)
                    var position =
                        item.position
                    var cancelPosition =
                        item.cancelPosition
                    val shrinkSize =
                        item.size / 3f                                     //shrink the size so that artificial taps can be accurate on circular buttons
                    val end =
                        position + Offset(x = item.size - shrinkSize, y = item.size - shrinkSize)
                    val cancelEnd =
                        cancelPosition + Offset(
                            x = item.size - shrinkSize,
                            y = item.size - shrinkSize
                        )
                    position = Offset(position.x + shrinkSize, position.y + shrinkSize)
                    cancelPosition =
                        Offset(cancelPosition.x + shrinkSize, cancelPosition.y + shrinkSize)
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

    fun clear() {
        eventInjector.clear()
        resetTransientInputState()
        setShootingMode(false)
    }

    fun shutdown() {
        resetTransientInputState()
        setShootingMode(false)
        eventInjector.shutdown()
    }

    private fun resetTransientInputState() {
        physicalKeysDown.clear()
        normalKeyStates.clear()
        cancelToggle.clear()
        wasdMask = 0
        lastWasdMask = 0
    }
}
