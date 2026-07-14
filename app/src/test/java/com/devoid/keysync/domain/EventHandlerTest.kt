package com.devoid.keysync.domain

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.DraggableItemType
import com.devoid.keysync.model.EventInjector
import com.devoid.keysync.model.TouchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventHandlerTest {

    @Test
    fun `S remains a WASD direction when Back is also mapped`() {
        val injector = RecordingEventInjector()
        val handler = EventHandler(injector)
        handler.updateKeyMapping(
            listOf(
                wasdGroup(),
                variableKey(2, KeyEvent.KEYCODE_BACK, Offset(500f, 500f))
            )
        )

        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_S, MotionEvent.ACTION_DOWN))

        assertEquals(
            RecordingEventInjector.Call.InjectGesture(
                pointerId = 0,
                start = CENTER,
                end = SOUTH
            ),
            injector.calls.single()
        )
    }

    @Test
    fun `number mappings cannot replace WASD directions or diagonals`() {
        val injector = RecordingEventInjector()
        val handler = EventHandler(injector)
        handler.updateKeyMapping(
            listOf(
                wasdGroup(),
                variableKey(2, KeyEvent.KEYCODE_1, Offset(500f, 100f)),
                variableKey(3, KeyEvent.KEYCODE_2, Offset(600f, 100f)),
                variableKey(4, KeyEvent.KEYCODE_5, Offset(700f, 100f))
            )
        )

        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_D, MotionEvent.ACTION_DOWN))
        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_D, MotionEvent.ACTION_UP))
        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_W, MotionEvent.ACTION_DOWN))
        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_D, MotionEvent.ACTION_DOWN))
        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_D, MotionEvent.ACTION_UP))
        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_W, MotionEvent.ACTION_UP))
        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_S, MotionEvent.ACTION_DOWN))
        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_D, MotionEvent.ACTION_DOWN))

        assertEquals(
            listOf(
                RecordingEventInjector.Call.InjectGesture(0, CENTER, EAST),
                RecordingEventInjector.Call.ReleaseGesture(0),
                RecordingEventInjector.Call.InjectGesture(0, CENTER, NORTH),
                RecordingEventInjector.Call.TransformGesture(0, NORTH_EAST),
                RecordingEventInjector.Call.TransformGesture(0, NORTH),
                RecordingEventInjector.Call.ReleaseGesture(0),
                RecordingEventInjector.Call.InjectGesture(0, CENTER, SOUTH),
                RecordingEventInjector.Call.TransformGesture(0, SOUTH_EAST)
            ),
            injector.calls
        )
    }

    @Test
    fun `using 1 through 4 and Escape cannot suppress another mapped key`() {
        val injector = RecordingEventInjector()
        val handler = EventHandler(injector)
        handler.setNormalBtnTouchMode(TouchMode.TAP)

        val keyCodes = listOf(
            KeyEvent.KEYCODE_1,
            KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_4,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_BACK
        )
        handler.updateKeyMapping(
            keyCodes.mapIndexed { index, keyCode ->
                variableKey(index + 1, keyCode, Offset(index * 100f, 300f))
            }
        )

        keyCodes.take(5).forEach { keyCode ->
            assertTrue(handler.handleKeyEvent(keyCode, MotionEvent.ACTION_DOWN))
            assertTrue(handler.handleKeyEvent(keyCode, MotionEvent.ACTION_UP))
        }
        val callsBeforeBack = injector.calls.size

        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_BACK, MotionEvent.ACTION_DOWN))

        assertEquals(callsBeforeBack + 2, injector.calls.size)
        assertTrue(injector.calls[callsBeforeBack] is RecordingEventInjector.Call.InjectPointer)
    }

    @Test
    fun `cancel target cannot be overwritten by a real keycode`() {
        val injector = RecordingEventInjector()
        val handler = EventHandler(injector)
        handler.setCancelableTouchMode(TouchMode.TAP)
        handler.updateKeyMapping(
            listOf(
                DraggableItem.CancelableKey(
                    id = 1,
                    position = Offset(100f, 100f),
                    cancelPosition = Offset(300f, 300f),
                    type = DraggableItemType.BAG_MAP,
                    keyCode = KeyEvent.KEYCODE_1,
                    size = 90
                ),
                // KEYCODE_1 or the old cancel flag (16) equals 24.
                variableKey(2, KeyEvent.KEYCODE_VOLUME_UP, Offset(700f, 700f))
            )
        )

        repeat(2) {
            assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_1, MotionEvent.ACTION_DOWN))
            assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_1, MotionEvent.ACTION_UP))
        }

        val injectedPositions = injector.calls
            .filterIsInstance<RecordingEventInjector.Call.InjectPointer>()
            .map { it.start }
        assertEquals(listOf(Offset(130f, 130f), Offset(330f, 330f)), injectedPositions)
    }

    @Test
    fun `right mouse button can trigger an ordinary key mapping`() {
        val injector = RecordingEventInjector()
        val handler = EventHandler(injector)
        handler.setNormalBtnTouchMode(TouchMode.TAP)
        handler.updateKeyMapping(
            listOf(variableKey(1, KEYCODE_RMC, Offset(600f, 400f)))
        )

        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_SECONDARY, true))
        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_SECONDARY, false))

        assertEquals(
            listOf(
                RecordingEventInjector.Call.InjectPointer(
                    pointerId = 0,
                    start = Offset(630f, 430f),
                    end = Offset(660f, 460f)
                ),
                RecordingEventInjector.Call.ReleasePointer(0)
            ),
            injector.calls
        )
    }

    @Test
    fun `unmapped left mouse button remains a native pointer click`() {
        val injector = RecordingEventInjector()
        val handler = EventHandler(injector)
        handler.mousePointerPosition = Offset(320f, 240f)
        handler.updateKeyMapping(emptyList())

        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_PRIMARY, true))
        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_PRIMARY, false))

        assertEquals(
            listOf(
                RecordingEventInjector.Call.InjectPointer(0, Offset(320f, 240f), null),
                RecordingEventInjector.Call.ReleasePointer(0)
            ),
            injector.calls
        )
    }

    @Test
    fun `WASD and camera pointer remain independent after repeated aiming toggles`() {
        val injector = RecordingEventInjector()
        val handler = EventHandler(injector)
        handler.updateKeyMapping(
            listOf(
                wasdGroup(),
                DraggableItem.FixedKey(
                    id = 2,
                    position = Offset(500f, 200f),
                    type = DraggableItemType.SHOOTING_MODE,
                    keyCode = KEYCODE_MMC,
                    size = 90
                )
            )
        )

        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_W, MotionEvent.ACTION_DOWN))
        repeat(5) {
            assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_TERTIARY, true))
            handler.handleMouseButton(MotionEvent.BUTTON_TERTIARY, false)
            assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_TERTIARY, true))
            handler.handleMouseButton(MotionEvent.BUTTON_TERTIARY, false)
        }
        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_TERTIARY, true))
        handler.handleMouseButton(MotionEvent.BUTTON_TERTIARY, false)

        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_D, MotionEvent.ACTION_DOWN))
        assertTrue(handler.handlePointerMove(Offset(8f, -3f)))

        assertEquals(
            listOf(
                RecordingEventInjector.Call.InjectPointer(
                    pointerId = 1,
                    start = Offset(530f, 230f),
                    end = null
                ),
                RecordingEventInjector.Call.TransformGesture(0, NORTH_EAST),
                RecordingEventInjector.Call.UpdatePointer(1, Offset(8f, -3f))
            ),
            injector.calls.takeLast(3)
        )
    }

    private fun variableKey(id: Int, keyCode: Int, position: Offset) =
        DraggableItem.VariableKey(
            id = id,
            position = position,
            keyCode = keyCode,
            size = 90
        )

    private fun wasdGroup() = DraggableItem.WASDGroup(
        id = 1,
        position = Offset.Zero,
        center = CENTER,
        w = NORTH,
        a = WEST,
        s = SOUTH,
        d = EAST
    )

    private companion object {
        val CENTER = Offset(100f, 100f)
        val NORTH = Offset(100f, 50f)
        val WEST = Offset(50f, 100f)
        val SOUTH = Offset(100f, 150f)
        val EAST = Offset(150f, 100f)
        val NORTH_EAST = Offset(125f, 75f)
        val SOUTH_EAST = Offset(125f, 125f)
    }
}

private class RecordingEventInjector : EventInjector {
    val calls = mutableListOf<Call>()

    override fun injectGesture(pointerID: Int, o1: Offset, o2: Offset) {
        calls += Call.InjectGesture(pointerID, o1, o2)
    }

    override fun transFormGesture(pointerID: Int, position: Offset) {
        calls += Call.TransformGesture(pointerID, position)
    }

    override fun releaseGesture(pointerID: Int) {
        calls += Call.ReleaseGesture(pointerID)
    }

    override fun injectPointer(pointerID: Int, o1: Offset, o2: Offset) {
        calls += Call.InjectPointer(pointerID, o1, o2)
    }

    override fun injectPointer(pointerID: Int, position: Offset) {
        calls += Call.InjectPointer(pointerID, position, null)
    }

    override fun updatePointerPosition(pointerID: Int, position: Offset) {
        calls += Call.UpdatePointer(pointerID, position)
    }

    override fun clear() {
        calls += Call.Clear
    }

    override fun releasePointer(pointerID: Int) {
        calls += Call.ReleasePointer(pointerID)
    }

    override suspend fun injected(pointerID: Int): Boolean = false

    sealed interface Call {
        data class InjectGesture(val pointerId: Int, val start: Offset, val end: Offset) : Call
        data class TransformGesture(val pointerId: Int, val position: Offset) : Call
        data class ReleaseGesture(val pointerId: Int) : Call
        data class InjectPointer(val pointerId: Int, val start: Offset, val end: Offset?) : Call
        data class UpdatePointer(val pointerId: Int, val position: Offset) : Call
        data class ReleasePointer(val pointerId: Int) : Call
        data object Clear : Call
    }
}
