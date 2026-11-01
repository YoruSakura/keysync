package com.devoid.keysync.domain.touch

import android.view.KeyEvent
import com.devoid.keysync.domain.KEYCODE_LMC
import com.devoid.keysync.domain.mouseButtonToKeyCode
import kotlin.math.abs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Game-independent semantic layer; JSON policies stay out of RootService and the backend. */
class MumuActionDispatcher(private val profile: MumuProfile, private val injector: TouchCoreEventInjector,
                           private var viewport: TouchViewport) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val buttons = profile.buttons.associateBy { it.key }
    private val held = mutableSetOf<Int>()
    private val physicalSources = mutableMapOf<Int, MutableSet<Int>>()
    private val macroJobs = mutableMapOf<Int, Job>()
    private val mode = MutableStateFlow(false)
    val shootingModeFlow = mode.asStateFlow()
    private val camera = CameraGestureController(CAMERA, injector.pipeline::submit)
    private var joystick = createJoystick()
    private var cursorHeld = false

    @Synchronized
    fun key(rawKey: Int, pressed: Boolean): Boolean {
        val key = when (rawKey) {
            KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.KEYCODE_ALT_LEFT
            KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.KEYCODE_SHIFT_LEFT
            KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.KEYCODE_CTRL_LEFT
            else -> rawKey
        }
        val sources = physicalSources.getOrPut(key) { mutableSetOf() }
        if (pressed) {
            if (!sources.add(rawKey) || sources.size > 1) return true
            held.add(key)
        } else {
            if (!sources.remove(rawKey) || sources.isNotEmpty()) return true
            physicalSources.remove(key)
            held.remove(key)
        }
        if (joystick?.key(key, pressed) == true) return true
        if (key == profile.camera?.key) { if (pressed) setCamera(!mode.value); return true }
        buttons[key]?.let { button ->
            val id = buttonId(key)
            if (pressed) injector.pipeline.submit(TouchCommand.Down(id, viewport.resolve(button.point)))
            else injector.pipeline.submit(TouchCommand.Up(id))
            return true
        }
        profile.macros.firstOrNull { it.key == key }?.let { macro ->
            if (pressed) executeMacro(key, macro.press)
            else if (macro.release.isNotEmpty()) executeMacro(key, macro.release)
            return true
        }
        return false
    }

    @Synchronized
    fun mouseButton(button: Int, pressed: Boolean, cursor: TouchPoint): Boolean {
        val key = mouseButtonToKeyCode(button) ?: return false
        if (!pressed && key in held) return this.key(key, false)
        // Explicit MuMu bindings are gameplay actions only in camera mode; menus use the cursor.
        if (mode.value || key == profile.camera?.key) return this.key(key, pressed)
        if (key != KEYCODE_LMC) return false
        if (pressed && !cursorHeld) { injector.pipeline.submit(TouchCommand.Down(CURSOR, cursor)); cursorHeld = true }
        else if (!pressed && cursorHeld) { injector.pipeline.submit(TouchCommand.Up(CURSOR)); cursorHeld = false }
        return true
    }

    @Synchronized
    fun move(delta: TouchPoint) {
        if (mode.value) camera.move(delta)
        else if (cursorHeld) injector.pipeline.submit(TouchCommand.MoveBy(mapOf(CURSOR to delta)))
    }

    @Synchronized
    fun scroll(delta: Float, cursor: TouchPoint): Boolean {
        if (mode.value || !delta.isFinite() || delta == 0f) return false
        val distance = (viewport.height * .12).coerceIn(96.0, 320.0) * abs(delta.toDouble()).coerceIn(1.0, 3.0)
        val end = viewport.bounds.clamp(cursor + TouchPoint(0.0, if (delta > 0) -distance else distance))
        injector.injectSwipe(SCROLL.value, cursor.offset(), end.offset())
        return true
    }

    @Synchronized
    private fun setCamera(enabled: Boolean) {
        if (enabled == mode.value) return
        if (!enabled) { camera.exit(); mode.value = false; return }
        val config = profile.camera ?: return
        val anchor = viewport.resolve(config.anchor)
        val bounds = TouchRect(maxOf(0.0, anchor.x - viewport.width * config.width / 2),
            maxOf(0.0, anchor.y - viewport.height * config.height / 2),
            minOf(viewport.width - 1, anchor.x + viewport.width * config.width / 2),
            minOf(viewport.height - 1, anchor.y + viewport.height * config.height / 2))
        // Our explicit linear gain convention; NOT an inferred MuMu sensitivity formula.
        camera.enter(CameraConfig(bounds.clamp(anchor).let {
            TouchPoint(it.x.coerceIn(bounds.left + .001, bounds.right - .001),
                it.y.coerceIn(bounds.top + .001, bounds.bottom - .001))
        }, bounds, config.sensitivityX / 50.0, config.sensitivityY / 50.0, rangeReset = config.reset))
        mode.value = true
    }

    private fun executeMacro(key: Int, actions: List<MacroAction>) {
        val previous = macroJobs.remove(key)
        previous?.cancel()
        val id = macroId(key)
        // Release a superseded macro contact, never camera/movement/attack.
        if (previous != null) injector.pipeline.submit(TouchCommand.Up(id))
        val job = scope.launch {
            for (action in actions) {
                synchronized(this@MumuActionDispatcher) {
                    if (macroJobs[key] !== coroutineContext[Job]) return@launch
                    when (action) {
                        is MacroAction.Click -> injector.pipeline.submit(TouchCommand.Down(id, viewport.resolve(action.point)))
                        MacroAction.ExitStaring -> setCamera(false)
                    }
                }
                if (action is MacroAction.Click) {
                    delay(24) // KeySync macro tap duration, not MuMu's unknown interval units.
                    synchronized(this@MumuActionDispatcher) {
                        if (macroJobs[key] !== coroutineContext[Job]) return@launch
                        injector.pipeline.submit(TouchCommand.Up(id))
                    }
                }
            }
            synchronized(this@MumuActionDispatcher) { if (macroJobs[key] === coroutineContext[Job]) macroJobs.remove(key) }
        }
        macroJobs[key] = job
    }

    @Synchronized
    fun reset() {
        macroJobs.values.forEach(Job::cancel); macroJobs.clear(); held.clear(); physicalSources.clear()
        cursorHeld = false; camera.abandon(); joystick = createJoystick(); mode.value = false
        injector.clear()
        injector.logDiagnostics()
    }
    @Synchronized
    fun resize(newViewport: TouchViewport) {
        if (newViewport == viewport) return
        val resume = mode.value
        reset(); viewport = newViewport; joystick = createJoystick()
        if (resume) setCamera(true)
    }
    @Synchronized
    fun resumeCamera() { setCamera(true) }
    @Synchronized
    fun close() { reset(); scope.cancel() }

    internal suspend fun awaitIdle() {
        val jobs = synchronized(this) { macroJobs.values.toList() }
        jobs.joinAll()
        injector.pipeline.awaitIdle()
    }

    private fun createJoystick() = profile.joystick?.let {
        JoystickGestureController(MOVEMENT, viewport.resolve(it.center), it.radius * minOf(viewport.width, viewport.height),
            it.up, it.left, it.down, it.right, injector.pipeline::submit)
    }
    private fun buttonId(key: Int) = ContactId(100_000 + key)
    private fun macroId(key: Int) = ContactId(200_000 + key)
    companion object {
        private val CAMERA = ContactId(-20_001)
        private val MOVEMENT = ContactId(-20_002)
        private val CURSOR = ContactId(-20_003)
        private val SCROLL = ContactId(-20_004)
    }
}
