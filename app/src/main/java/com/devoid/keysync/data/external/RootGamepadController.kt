package com.devoid.keysync.data.external

import kotlin.math.sqrt

internal interface VirtualGamepadSink {
    fun setLeftStick(position: StickPosition)
    fun setRightStick(position: StickPosition)
    fun setButton(button: GamepadButton, pressed: Boolean)
    fun reset()
}

internal enum class GamepadButton(val linuxCode: Int) {
    A(0x130),
    B(0x131),
    X(0x133),
    Y(0x134),
    LEFT_BUMPER(0x136),
    RIGHT_BUMPER(0x137),
    LEFT_TRIGGER(0x138),
    RIGHT_TRIGGER(0x139),
    SELECT(0x13a),
    START(0x13b),
    MODE(0x13c),
    LEFT_STICK(0x13d),
    RIGHT_STICK(0x13e)
}

/** Routes the captured keyboard and mouse to one standard virtual gamepad. */
internal class RootGamepadController(
    private val sink: VirtualGamepadSink,
    private val mouseMapper: MouseToRightStickMapper = MouseToRightStickMapper(),
    private val rightStickTimeoutNanos: Long = RIGHT_STICK_TIMEOUT_NANOS
) {
    private val pressedKeys = mutableSetOf<Int>()
    private val pressedMouseButtons = mutableSetOf<Int>()
    private var activeButtons = emptySet<GamepadButton>()
    private var leftStick = StickPosition.CENTER
    private var rightStick = StickPosition.CENTER
    private var lastMouseMotionNanos = 0L

    @Synchronized
    fun onKey(scanCode: Int, value: Int) {
        if (value == 0) {
            pressedKeys.remove(scanCode)
        } else {
            pressedKeys.add(scanCode)
        }
        updateLeftStick()
        updateButtons()
    }

    @Synchronized
    fun onMouseButton(linuxButton: Int, value: Int) {
        if (value == 0) {
            pressedMouseButtons.remove(linuxButton)
        } else {
            pressedMouseButtons.add(linuxButton)
        }
        updateButtons()
    }

    @Synchronized
    fun onMouseMove(deltaX: Int, deltaY: Int, eventTimeNanos: Long) {
        if (deltaX == 0 && deltaY == 0) return
        val elapsedNanos = if (
            lastMouseMotionNanos == 0L ||
            eventTimeNanos - lastMouseMotionNanos !in 1..MouseToRightStickMapper.MAX_CONTINUOUS_SAMPLE_GAP_NANOS
        ) {
            MouseToRightStickMapper.DEFAULT_SAMPLE_NANOS
        } else {
            eventTimeNanos - lastMouseMotionNanos
        }
        lastMouseMotionNanos = eventTimeNanos
        setRightStick(mouseMapper.map(deltaX, deltaY, elapsedNanos))
    }

    /** Called by the Root service's one-shot watchdog after mouse activity. */
    @Synchronized
    fun centerRightStickIfIdle(nowNanos: Long): Long? {
        if (rightStick == StickPosition.CENTER || lastMouseMotionNanos == 0L) return null
        val remainingNanos = rightStickTimeoutNanos - (nowNanos - lastMouseMotionNanos)
        if (remainingNanos <= 0L) {
            setRightStick(StickPosition.CENTER)
            return null
        }
        return remainingNanos
    }

    @Synchronized
    fun reset() {
        pressedKeys.clear()
        pressedMouseButtons.clear()
        activeButtons = emptySet()
        leftStick = StickPosition.CENTER
        rightStick = StickPosition.CENTER
        lastMouseMotionNanos = 0L
        sink.reset()
    }

    private fun updateLeftStick() {
        val horizontal = pressedKeys.contains(KEY_D).toInt() - pressedKeys.contains(KEY_A).toInt()
        val vertical = pressedKeys.contains(KEY_S).toInt() - pressedKeys.contains(KEY_W).toInt()
        val diagonalScale = if (horizontal != 0 && vertical != 0) {
            ONE_OVER_SQRT_TWO
        } else {
            1.0
        }
        val position = StickPosition(
            x = (horizontal * AXIS_MAXIMUM * diagonalScale).toInt(),
            y = (vertical * AXIS_MAXIMUM * diagonalScale).toInt()
        )
        if (position != leftStick) {
            leftStick = position
            sink.setLeftStick(position)
        }
    }

    private fun updateButtons() {
        val next = buildSet {
            if (KEY_SPACE in pressedKeys || KEY_4 in pressedKeys) add(GamepadButton.A)
            if (
                KEY_LEFT_SHIFT in pressedKeys ||
                KEY_RIGHT_SHIFT in pressedKeys ||
                BUTTON_RIGHT in pressedMouseButtons ||
                KEY_3 in pressedKeys
            ) {
                add(GamepadButton.B)
            }
            if (BUTTON_LEFT in pressedMouseButtons || KEY_1 in pressedKeys) add(GamepadButton.X)
            if (KEY_F in pressedKeys || KEY_2 in pressedKeys) add(GamepadButton.Y)
            if (KEY_E in pressedKeys) add(GamepadButton.RIGHT_BUMPER)
            if (pressedKeys.any { it in KEY_1..KEY_4 }) add(GamepadButton.LEFT_BUMPER)
            if (BUTTON_MIDDLE in pressedMouseButtons) add(GamepadButton.RIGHT_STICK)
        }
        (activeButtons - next).forEach { sink.setButton(it, false) }
        (next - activeButtons).forEach { sink.setButton(it, true) }
        activeButtons = next
    }

    private fun setRightStick(position: StickPosition) {
        if (position == rightStick) return
        rightStick = position
        sink.setRightStick(position)
    }

    private fun Boolean.toInt(): Int = if (this) 1 else 0

    companion object {
        const val RIGHT_STICK_TIMEOUT_NANOS = 6_000_000L
        private const val AXIS_MAXIMUM = MouseToRightStickMapper.AXIS_MAXIMUM
        private val ONE_OVER_SQRT_TWO = 1.0 / sqrt(2.0)

        private const val KEY_1 = 2
        private const val KEY_2 = 3
        private const val KEY_3 = 4
        private const val KEY_4 = 5
        private const val KEY_E = 18
        private const val KEY_W = 17
        private const val KEY_A = 30
        private const val KEY_S = 31
        private const val KEY_D = 32
        private const val KEY_F = 33
        private const val KEY_LEFT_SHIFT = 42
        private const val KEY_RIGHT_SHIFT = 54
        private const val KEY_SPACE = 57

        private const val BUTTON_LEFT = 0x110
        private const val BUTTON_RIGHT = 0x111
        private const val BUTTON_MIDDLE = 0x112
    }
}
