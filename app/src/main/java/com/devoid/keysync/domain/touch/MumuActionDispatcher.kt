package com.devoid.keysync.domain.touch

import android.view.KeyEvent
import com.devoid.keysync.domain.KEYCODE_LMC
import com.devoid.keysync.domain.mouseButtonToKeyCode
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
    private val contacts = mutableSetOf<ContactId>()
    private val mode = MutableStateFlow(false)
    val shootingModeFlow = mode.asStateFlow()
    private val camera = CameraGestureController(CAMERA, injector.pipeline::submit)
    private val wheel = WheelGestureController(scope, injector.pipeline::submit)
    private var joystick = createJoystick()
    private var cursorPosition = TouchPoint(viewport.width / 2, viewport.height / 2)
    private var observePosition: TouchPoint? = null
    private var cursorHeld = false

    @Synchronized
    fun key(rawKey: Int, pressed: Boolean, cursor: TouchPoint = cursorPosition): Boolean {
        cursorPosition = cursor
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
        if (key == profile.observe?.key) { setObserve(pressed); return true }
        buttons[key]?.let { button ->
            if (pressed) down(buttonId(key), viewport.resolve(button.point)) else up(buttonId(key))
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
        cursorPosition = cursor
        val key = mouseButtonToKeyCode(button) ?: return false
        if (!pressed && key in held) return this.key(key, false)
        // Staring profiles reserve explicit mouse bindings for aiming; cursor-based profiles use them directly.
        if (profile.camera == null || mode.value || key == profile.camera.key) {
            if (buttons.containsKey(key) || profile.macros.any { it.key == key } || key == profile.camera?.key)
                return this.key(key, pressed)
        }
        if (key != KEYCODE_LMC) return false
        setCursorButton(pressed)
        return true
    }

    @Synchronized
    fun move(delta: TouchPoint, cursor: TouchPoint = cursorPosition + delta) {
        cursorPosition = cursor
        val observe = profile.observe
        if (mode.value && observe != null) {
            val current = observePosition ?: viewport.resolve(observe.anchor).also { down(OBSERVE, it) }
            val next = viewport.bounds.clamp(current + TouchPoint(delta.x * observe.sensitivityX / 50.0,
                delta.y * observe.sensitivityY / 50.0))
            injector.pipeline.submit(TouchCommand.Move(mapOf(OBSERVE to next)))
            observePosition = next
        } else if (mode.value) {
            if (!wheel.isZooming) camera.move(delta)
        } else if (cursorHeld) injector.pipeline.submit(TouchCommand.Move(mapOf(CURSOR to cursor)))
    }

    @Synchronized
    fun scroll(delta: Float, cursor: TouchPoint): Boolean {
        if (!delta.isFinite() || delta == 0f) return false
        cursorPosition = cursor
        if (mode.value && profile.camera != null) {
            camera.releaseContact()
            wheel.zoom(delta, viewport)
        } else wheel.scroll(delta, cursor, viewport)
        return true
    }

    private fun setObserve(enabled: Boolean) {
        if (mode.value == enabled) return
        wheel.cancel()
        if (!enabled) { up(OBSERVE); observePosition = null }
        mode.value = enabled
    }

    private fun setCursorButton(pressed: Boolean) {
        if (pressed == cursorHeld) return
        cursorHeld = pressed
        if (pressed) down(CURSOR, cursorPosition) else up(CURSOR)
    }

    private fun down(id: ContactId, point: TouchPoint) {
        if (contacts.add(id)) injector.pipeline.submit(TouchCommand.Down(id, point))
    }

    private fun up(id: ContactId) {
        if (contacts.remove(id)) injector.pipeline.submit(TouchCommand.Up(id))
    }

    private fun setCamera(enabled: Boolean) {
        if (enabled == mode.value) return
        wheel.cancel()
        if (!enabled) { camera.exit(); mode.value = false; return }
        val config = profile.camera ?: return
        val anchor = viewport.resolve(config.anchor)
        val bounds = TouchRect(maxOf(0.0, anchor.x - viewport.width * config.width / 2),
            maxOf(0.0, anchor.y - viewport.height * config.height / 2),
            minOf(viewport.width - 1, anchor.x + viewport.width * config.width / 2),
            minOf(viewport.height - 1, anchor.y + viewport.height * config.height / 2))
        camera.enter(CameraConfig(bounds.clamp(anchor).let {
            TouchPoint(it.x.coerceIn(bounds.left + .001, bounds.right - .001),
                it.y.coerceIn(bounds.top + .001, bounds.bottom - .001))
        }, bounds, config.sensitivityX / 50.0, config.sensitivityY / 50.0, rangeReset = config.reset))
        mode.value = true
    }

    private fun executeMacro(key: Int, actions: List<MacroAction>) {
        macroJobs.remove(key)?.cancel()
        up(macroId(key)); up(curveId(key))
        // Programs with explicit sleeps control their own timing; retain the old tap duration for legacy click-only macros.
        val tapMillis = if (actions.any { it is MacroAction.Sleep || it is MacroAction.Curve }) 0L else 24L
        val job = scope.launch(start = CoroutineStart.LAZY) {
            fun step(action: () -> Unit): Boolean = synchronized(this@MumuActionDispatcher) {
                if (macroJobs[key] !== coroutineContext[Job]) false else { action(); true }
            }
            suspend fun click(point: TouchPoint) {
                if (!step { down(macroId(key), point) }) return
                injector.pipeline.awaitIdle()
                if (tapMillis > 0) delay(tapMillis)
                step { up(macroId(key)) }
                injector.pipeline.awaitIdle()
            }
            for (action in actions) {
                if (!step {}) return@launch
                when (action) {
                    is MacroAction.Click -> click(viewport.resolve(action.point))
                    MacroAction.ClickCursor -> click(synchronized(this@MumuActionDispatcher) { cursorPosition })
                    is MacroAction.Sleep -> delay(action.millis)
                    is MacroAction.MouseButton -> step { setCursorButton(action.pressed) }
                    is MacroAction.Keyboard -> step {
                        // The file's scan 11 invokes its mapped 0/pause button; unmapped BACK goes to Android.
                        val button = buttons[action.key.code]
                        if (button != null) {
                            val id = ContactId(400_000 + action.key.scanCode)
                            if (action.pressed) down(id, viewport.resolve(button.point)) else up(id)
                        } else injector.pipeline.submit(TouchCommand.Key(action.key, action.pressed))
                    }
                    is MacroAction.Curve -> {
                        if (!step { down(curveId(key), cursorPosition) }) return@launch
                        injector.pipeline.awaitIdle()
                        delay(action.firstWaitMillis)
                        for (point in action.points) {
                            if (!step { injector.pipeline.submit(TouchCommand.Move(mapOf(curveId(key) to viewport.resolve(point)))) }) return@launch
                            injector.pipeline.awaitIdle()
                        }
                        if (!action.holdEnd) step { up(curveId(key)) }
                    }
                    MacroAction.ReleaseAll -> step { releaseOutputs(cancel = false) }
                    MacroAction.ExitStaring -> step { setCamera(false) }
                }
                injector.pipeline.awaitIdle()
            }
            synchronized(this@MumuActionDispatcher) {
                if (macroJobs[key] === coroutineContext[Job]) macroJobs.remove(key)
            }
        }
        macroJobs[key] = job
        job.start()
    }

    private fun releaseOutputs(cancel: Boolean = true) {
        wheel.cancel(); camera.abandon(); observePosition = null; mode.value = false
        contacts.clear(); cursorHeld = false; joystick = createJoystick()
        if (cancel) injector.clear() else injector.pipeline.submit(TouchCommand.ReleaseAll)
    }

    @Synchronized
    fun reset() {
        macroJobs.values.forEach(Job::cancel); macroJobs.clear(); held.clear(); physicalSources.clear()
        releaseOutputs()
        injector.logDiagnostics()
    }
    @Synchronized
    fun resize(newViewport: TouchViewport) {
        if (newViewport == viewport) return
        val resume = mode.value && profile.camera != null
        reset(); viewport = newViewport; joystick = createJoystick()
        cursorPosition = viewport.bounds.clamp(cursorPosition)
        if (resume) setCamera(true)
    }
    @Synchronized
    fun resumeCamera() { setCamera(true) }
    @Synchronized
    fun close() { reset(); scope.cancel() }

    internal suspend fun awaitIdle() {
        val jobs = synchronized(this) { macroJobs.values.toList() }
        jobs.joinAll()
        wheel.awaitIdle()
        injector.pipeline.awaitIdle()
    }

    private fun createJoystick() = profile.joystick?.let {
        JoystickGestureController(MOVEMENT, viewport.resolve(it.center), it.radius * minOf(viewport.width, viewport.height),
            it.up, it.left, it.down, it.right, injector.pipeline::submit)
    }
    private fun buttonId(key: Int) = ContactId(100_000 + key)
    private fun macroId(key: Int) = ContactId(200_000 + key)
    private fun curveId(key: Int) = ContactId(300_000 + key)
    companion object {
        private val CAMERA = ContactId(-20_001)
        private val MOVEMENT = ContactId(-20_002)
        private val CURSOR = ContactId(-20_003)
        private val OBSERVE = ContactId(-20_004)
    }
}
