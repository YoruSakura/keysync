package com.devoid.keysync.domain.touch

import kotlin.math.abs
import kotlin.math.hypot

data class TouchRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init { require(listOf(left, top, right, bottom).all { it.isFinite() } && left < right && top < bottom) }
    fun clamp(point: TouchPoint) = TouchPoint(point.x.coerceIn(left, right), point.y.coerceIn(top, bottom))
}

/** Backend-independent display viewport; optional future native orientation mapping lives here. */
data class TouchViewport(val width: Double, val height: Double) {
    init { require(width.isFinite() && height.isFinite() && width > 1 && height > 1) }
    fun resolve(normalized: TouchPoint) = TouchPoint(normalized.x * (width - 1), normalized.y * (height - 1))
    val bounds get() = TouchRect(0.0, 0.0, width - 1, height - 1)
}

data class CameraConfig(val anchor: TouchPoint, val bounds: TouchRect,
                        val gainX: Double = 1.0, val gainY: Double = 1.0,
                        val lazyDown: Boolean = true, val rangeReset: Boolean = true) {
    init {
        require(gainX.isFinite() && gainY.isFinite() && gainX in 0.0..100.0 && gainY in 0.0..100.0)
        require(anchor.x > bounds.left && anchor.x < bounds.right && anchor.y > bounds.top && anchor.y < bounds.bottom)
    }
}

class CameraGestureController(private val id: ContactId, private val emit: (TouchCommand) -> Unit) {
    enum class State { DISABLED, ACTIVE, RESETTING, EXITING, ERROR_RECOVERY }
    var state = State.DISABLED
        private set
    var position: TouchPoint? = null
        private set
    var resets = 0L
        private set
    private var config: CameraConfig? = null
    private var touching = false

    fun enter(configuration: CameraConfig) {
        exit()
        config = configuration
        state = State.ACTIVE
        position = configuration.anchor
        if (!configuration.lazyDown) down()
    }

    private fun down() {
        if (touching) return
        emit(TouchCommand.Down(id, config!!.anchor))
        touching = true
        position = config!!.anchor
    }

    fun move(rawDelta: TouchPoint) {
        if (state != State.ACTIVE) return
        val cfg = config ?: return
        var remaining = TouchPoint(rawDelta.x * cfg.gainX, rawDelta.y * cfg.gainY)
        if (remaining == TouchPoint(0.0, 0.0)) return
        down()
        var crossings = 0
        while (abs(remaining.x) > EPSILON || abs(remaining.y) > EPSILON) {
            val current = position!!
            val target = current + remaining
            if (!cfg.rangeReset) {
                position = cfg.bounds.clamp(target)
                emit(TouchCommand.Move(mapOf(id to position!!)))
                return
            }
            var fraction = 1.0
            if (remaining.x > 0) fraction = minOf(fraction, (cfg.bounds.right - current.x) / remaining.x)
            if (remaining.x < 0) fraction = minOf(fraction, (cfg.bounds.left - current.x) / remaining.x)
            if (remaining.y > 0) fraction = minOf(fraction, (cfg.bounds.bottom - current.y) / remaining.y)
            if (remaining.y < 0) fraction = minOf(fraction, (cfg.bounds.top - current.y) / remaining.y)
            fraction = fraction.coerceIn(0.0, 1.0)
            if (fraction > EPSILON) {
                position = cfg.bounds.clamp(current + remaining * fraction)
                emit(TouchCommand.Move(mapOf(id to position!!)))
            }
            if (fraction >= 1.0) return
            if (++crossings > MAX_CROSSINGS) {
                // Corrupt/implausibly large sample: never spin or teleport a held finger.
                exit(); state = State.ERROR_RECOVERY
                return
            }
            state = State.RESETTING
            emit(TouchCommand.Rebase(id, cfg.anchor)) // Ordered UP frame then DOWN frame.
            position = cfg.anchor
            remaining *= 1.0 - fraction // Carry overshoot, including fractional motion.
            resets++
            state = State.ACTIVE
        }
    }

    fun exit() {
        if (state == State.DISABLED) return
        state = State.EXITING
        if (touching) emit(TouchCommand.Up(id))
        touching = false
        position = null
        state = State.DISABLED
    }

    /** Global session CANCEL already released this contact. */
    fun abandon() { touching = false; position = null; state = State.DISABLED }

    companion object { private const val EPSILON = 1e-9; private const val MAX_CROSSINGS = 128 }
}

class JoystickGestureController(
    private val id: ContactId, private val center: TouchPoint, private val radius: Double,
    private val up: Int, private val left: Int, private val down: Int, private val right: Int,
    private val emit: (TouchCommand) -> Unit
) {
    private val held = mutableSetOf<Int>()
    private var touching = false
    fun key(key: Int, pressed: Boolean): Boolean {
        if (key !in setOf(up, left, down, right)) return false
        if (pressed) held.add(key) else held.remove(key)
        if (held.isEmpty()) { reset(); return true }
        if (!touching) { emit(TouchCommand.Down(id, center)); touching = true }
        val x = (if (right in held) 1.0 else 0.0) - (if (left in held) 1.0 else 0.0)
        val y = (if (down in held) 1.0 else 0.0) - (if (up in held) 1.0 else 0.0)
        val length = hypot(x, y).coerceAtLeast(1.0)
        emit(TouchCommand.Move(mapOf(id to (center + TouchPoint(x / length, y / length) * radius))))
        return true
    }
    fun reset() {
        if (touching) emit(TouchCommand.Up(id))
        held.clear(); touching = false
    }
}
