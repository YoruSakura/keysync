package com.devoid.keysync.data.external

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootGamepadControllerTest {
    @Test
    fun `mouse drives right stick and watchdog centers after six milliseconds`() {
        val sink = RecordingGamepadSink()
        val controller = RootGamepadController(sink)

        controller.onMouseMove(6, -3, 10_000_000)
        val moved = sink.currentRightStick
        assertTrue(moved.x > 0)
        assertTrue(moved.y < 0)

        controller.centerRightStickIfIdle(15_999_999)
        assertEquals(moved, sink.currentRightStick)
        controller.centerRightStickIfIdle(16_000_000)
        assertEquals(StickPosition.CENTER, sink.currentRightStick)
    }

    @Test
    fun `WASD diagonal is circularly normalized and opposite keys cancel`() {
        val sink = RecordingGamepadSink()
        val controller = RootGamepadController(sink)

        controller.onKey(KEY_W, 1)
        controller.onKey(KEY_D, 1)
        assertTrue(sink.currentLeftStick.x > 0)
        assertTrue(sink.currentLeftStick.y < 0)
        assertTrue(
            hypot(sink.currentLeftStick.x.toDouble(), sink.currentLeftStick.y.toDouble()) <=
                MouseToRightStickMapper.AXIS_MAXIMUM
        )

        controller.onKey(KEY_A, 1)
        assertEquals(0, sink.currentLeftStick.x)
        assertTrue(sink.currentLeftStick.y < 0)
    }

    @Test
    fun `overlapping semantic sources do not release a button early`() {
        val sink = RecordingGamepadSink()
        val controller = RootGamepadController(sink)

        controller.onKey(KEY_SPACE, 1)
        controller.onKey(KEY_4, 1)
        controller.onKey(KEY_SPACE, 0)
        assertTrue(GamepadButton.A in sink.buttons)

        controller.onKey(KEY_4, 0)
        assertFalse(GamepadButton.A in sink.buttons)
        assertFalse(GamepadButton.LEFT_BUMPER in sink.buttons)
    }

    @Test
    fun `mouse and keyboard semantic buttons use standard gamepad meanings`() {
        val sink = RecordingGamepadSink()
        val controller = RootGamepadController(sink)

        controller.onMouseButton(BUTTON_LEFT, 1)
        controller.onMouseButton(BUTTON_RIGHT, 1)
        controller.onMouseButton(BUTTON_MIDDLE, 1)
        controller.onKey(KEY_F, 1)
        controller.onKey(KEY_E, 1)

        assertEquals(
            setOf(
                GamepadButton.X,
                GamepadButton.B,
                GamepadButton.RIGHT_STICK,
                GamepadButton.Y,
                GamepadButton.RIGHT_BUMPER
            ),
            sink.buttons
        )
    }

    @Test
    fun `reset releases all virtual input state`() {
        val sink = RecordingGamepadSink()
        val controller = RootGamepadController(sink)
        controller.onKey(KEY_W, 1)
        controller.onMouseButton(BUTTON_LEFT, 1)
        controller.onMouseMove(3, 2, 10_000_000)

        controller.reset()

        assertEquals(StickPosition.CENTER, sink.currentLeftStick)
        assertEquals(StickPosition.CENTER, sink.currentRightStick)
        assertTrue(sink.buttons.isEmpty())
        assertEquals(1, sink.resetCount)
    }

    private class RecordingGamepadSink : VirtualGamepadSink {
        var currentLeftStick = StickPosition.CENTER
        var currentRightStick = StickPosition.CENTER
        val buttons = mutableSetOf<GamepadButton>()
        var resetCount = 0

        override fun setLeftStick(position: StickPosition) {
            currentLeftStick = position
        }

        override fun setRightStick(position: StickPosition) {
            currentRightStick = position
        }

        override fun setButton(button: GamepadButton, pressed: Boolean) {
            if (pressed) buttons.add(button) else buttons.remove(button)
        }

        override fun reset() {
            currentLeftStick = StickPosition.CENTER
            currentRightStick = StickPosition.CENTER
            buttons.clear()
            resetCount += 1
        }
    }

    private companion object {
        const val KEY_4 = 5
        const val KEY_E = 18
        const val KEY_W = 17
        const val KEY_A = 30
        const val KEY_D = 32
        const val KEY_F = 33
        const val KEY_SPACE = 57
        const val BUTTON_LEFT = 0x110
        const val BUTTON_RIGHT = 0x111
        const val BUTTON_MIDDLE = 0x112
    }
}
