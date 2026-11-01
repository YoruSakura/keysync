package com.devoid.keysync.domain

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.DraggableItemType
import com.devoid.keysync.model.EventInjector
import com.devoid.keysync.model.TouchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventHandlerTest {

    @Test
    fun `mouse pseudo key codes are outside the Android physical key namespace`() {
        assertTrue(KEYCODE_LMC < KeyEvent.KEYCODE_UNKNOWN)
        assertTrue(KEYCODE_RMC < KeyEvent.KEYCODE_UNKNOWN)
        assertTrue(KEYCODE_MMC < KeyEvent.KEYCODE_UNKNOWN)
        assertEquals(3, setOf(KEYCODE_LMC, KEYCODE_RMC, KEYCODE_MMC).size)
    }

    @Test
    fun `both legacy mouse key namespaces migrate without changing physical keys`() {
        assertEquals(KEYCODE_LMC, migrateLegacyMouseKeyCode(64))
        assertEquals(KEYCODE_RMC, migrateLegacyMouseKeyCode(128))
        assertEquals(KEYCODE_MMC, migrateLegacyMouseKeyCode(256))
        assertEquals(KEYCODE_LMC, migrateLegacyMouseKeyCode(1002))
        assertEquals(KEYCODE_RMC, migrateLegacyMouseKeyCode(1003))
        assertEquals(KEYCODE_MMC, migrateLegacyMouseKeyCode(1004))
        assertEquals(KeyEvent.KEYCODE_1, migrateLegacyMouseKeyCode(KeyEvent.KEYCODE_1))
    }

    @Test
    fun `legacy global and per-game mouse bindings migrate together`() {
        val migratedConfig = AppConfig.Default.copy(
            shootingModeKeyCode = 256,
            fireKeyCode = 64,
            scopeKeyCode = 128
        ).migrateLegacyMouseKeyCodes()
        val migratedItem = variableKey(1, 1003, Offset(600f, 400f))
            .migrateLegacyMouseKeyCode() as DraggableItem.VariableKey

        assertEquals(KEYCODE_MMC, migratedConfig.shootingModeKeyCode)
        assertEquals(KEYCODE_LMC, migratedConfig.fireKeyCode)
        assertEquals(KEYCODE_RMC, migratedConfig.scopeKeyCode)
        assertEquals(KEYCODE_RMC, migratedItem.keyCode)
    }

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
        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_BACK, MotionEvent.ACTION_UP))

        assertEquals(callsBeforeBack + 2, injector.calls.size)
        assertTrue(injector.calls[callsBeforeBack] is RecordingEventInjector.Call.InjectPointer)
    }

    @Test
    fun `regular mapping stays touched for the full physical key press`() {
        val injector = RecordingEventInjector()
        val handler = EventHandler(injector)
        handler.updateKeyMapping(
            listOf(variableKey(1, KeyEvent.KEYCODE_4, Offset(400f, 300f)))
        )

        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_4, MotionEvent.ACTION_DOWN))
        assertEquals(
            listOf(
                RecordingEventInjector.Call.InjectPointer(
                    pointerId = 0,
                    start = Offset(430f, 330f),
                    end = Offset(460f, 360f)
                )
            ),
            injector.calls
        )

        // No synthetic release is scheduled: the virtual finger remains down
        // for however long the physical key remains held.
        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_4, MotionEvent.ACTION_UP))
        assertEquals(RecordingEventInjector.Call.ReleasePointer(0), injector.calls.last())
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
        handler.updateKeyMapping(
            listOf(variableKey(1, KEYCODE_RMC, Offset(600f, 400f)))
        )

        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_SECONDARY, true))
        assertEquals(1, injector.calls.size)
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

        assertTrue(handler.handlePointerMove(Offset(10f, 5f)))
        assertTrue(injector.calls.isEmpty())

        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_PRIMARY, true))
        assertTrue(handler.handlePointerMove(Offset(10f, 5f)))
        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_PRIMARY, false))

        assertEquals(
            listOf(
                RecordingEventInjector.Call.Clear,
                RecordingEventInjector.Call.InjectPointer(0, Offset(320f, 240f), null),
                RecordingEventInjector.Call.UpdatePointer(0, Offset(10f, 5f)),
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
                RecordingEventInjector.Call.TransformGesture(0, NORTH_EAST),
                RecordingEventInjector.Call.InjectPointer(
                    pointerId = 1,
                    start = Offset(530f, 230f),
                    end = null
                ),
                RecordingEventInjector.Call.UpdatePointer(1, Offset(8f, -3f))
            ),
            injector.calls.takeLast(3)
        )
    }

    @Test
    fun `entering aiming rebuilds the shared touch stream and held inputs`() {
        val injector = RecordingEventInjector()
        val handler = EventHandler(injector)
        handler.updateKeyMapping(
            listOf(
                wasdGroup(),
                variableKey(2, KeyEvent.KEYCODE_4, Offset(400f, 300f)),
                DraggableItem.FixedKey(
                    id = 3,
                    position = Offset(500f, 200f),
                    type = DraggableItemType.SHOOTING_MODE,
                    keyCode = KEYCODE_MMC,
                    size = 90
                )
            )
        )

        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_W, MotionEvent.ACTION_DOWN))
        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_4, MotionEvent.ACTION_DOWN))
        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_TERTIARY, true))

        assertEquals(
            listOf(
                RecordingEventInjector.Call.Clear,
                RecordingEventInjector.Call.InjectGesture(0, CENTER, NORTH),
                RecordingEventInjector.Call.InjectPointer(
                    pointerId = 1,
                    start = Offset(430f, 330f),
                    end = Offset(460f, 360f)
                )
            ),
            injector.calls.takeLast(3)
        )
    }

    @Test
    fun `capture interruption clears stale input and restores aiming`() {
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
        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_TERTIARY, true))
        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_TERTIARY, false))

        assertTrue(handler.pauseForInputCaptureLoss())
        assertFalse(handler.shootingModeFlow.value)
        assertEquals(RecordingEventInjector.Call.Clear, injector.calls.last())

        assertTrue(handler.resumeShootingMode())
        assertTrue(handler.shootingModeFlow.value)
        assertEquals(RecordingEventInjector.Call.Clear, injector.calls.last())

        // Key-up may have been lost with window focus. Clearing the physical
        // key state lets the next down event establish a fresh WASD gesture.
        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_W, MotionEvent.ACTION_DOWN))
        assertEquals(
            RecordingEventInjector.Call.InjectGesture(0, CENTER, NORTH),
            injector.calls.last()
        )
    }

    @Test
    fun `game switch cancels the system touch stream and resets aiming state`() {
        val injector = RecordingEventInjector()
        val handler = EventHandler(injector)
        handler.updateKeyMapping(
            listOf(
                DraggableItem.FixedKey(
                    id = 1,
                    position = Offset(500f, 200f),
                    type = DraggableItemType.SHOOTING_MODE,
                    keyCode = KEYCODE_MMC,
                    size = 90
                )
            )
        )

        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_TERTIARY, true))
        assertTrue(handler.shootingModeFlow.value)

        handler.resetForGameSwitch()

        assertFalse(handler.shootingModeFlow.value)
        assertEquals(
            RecordingEventInjector.Call.CancelSystemTouch,
            injector.calls.last()
        )
    }

    @Test
    fun `shooting mode key release cannot release its camera pointer`() {
        val injector = RecordingEventInjector()
        val handler = EventHandler(injector)
        handler.updateKeyMapping(
            listOf(
                DraggableItem.FixedKey(
                    id = 1,
                    position = Offset(500f, 200f),
                    type = DraggableItemType.SHOOTING_MODE,
                    keyCode = KEYCODE_MMC,
                    size = 90
                )
            )
        )

        assertTrue(handler.handleKeyEvent(KEYCODE_MMC, MotionEvent.ACTION_DOWN))
        assertTrue(handler.handleKeyEvent(KEYCODE_MMC, MotionEvent.ACTION_UP))

        assertTrue(handler.shootingModeFlow.value)
        assertEquals(listOf(RecordingEventInjector.Call.Clear), injector.calls)
    }

    @Test
    fun `dialogue dismissal click restarts camera while aiming and WASD stay enabled`() {
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
                ),
                DraggableItem.FixedKey(
                    id = 3,
                    position = Offset(800f, 500f),
                    type = DraggableItemType.FIRE,
                    keyCode = KEYCODE_LMC,
                    size = 90
                )
            )
        )

        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_TERTIARY, true))
        assertTrue(handler.shootingModeFlow.value)
        assertEquals(listOf(RecordingEventInjector.Call.Clear), injector.calls)

        assertTrue(handler.handlePointerMove(Offset(12f, -4f)))
        assertEquals(
            listOf(
                RecordingEventInjector.Call.InjectPointer(1, Offset(530f, 230f), null),
                RecordingEventInjector.Call.UpdatePointer(1, Offset(12f, -4f))
            ),
            injector.calls.takeLast(2)
        )

        // Mirrors dismissing a dialogue/reward UI with the mapped fire click
        // while the logical aiming mode and hidden cursor remain enabled.
        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_PRIMARY, true))
        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_PRIMARY, false))
        assertEquals(
            listOf(
                RecordingEventInjector.Call.ReleasePointer(1),
                RecordingEventInjector.Call.InjectPointer(
                    2,
                    Offset(830f, 530f),
                    Offset(860f, 560f)
                ),
                RecordingEventInjector.Call.ReleasePointer(2)
            ),
            injector.calls.takeLast(3)
        )

        val callsAfterClick = injector.calls.size
        handler.releaseIdleShootingPointer()
        assertTrue(handler.shootingModeFlow.value)
        assertEquals(callsAfterClick, injector.calls.size)

        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_W, MotionEvent.ACTION_DOWN))
        assertEquals(
            RecordingEventInjector.Call.InjectGesture(0, CENTER, NORTH),
            injector.calls.last()
        )

        assertTrue(handler.handlePointerMove(Offset(-6f, 3f)))
        assertEquals(
            listOf(
                RecordingEventInjector.Call.InjectPointer(1, Offset(530f, 230f), null),
                RecordingEventInjector.Call.UpdatePointer(1, Offset(-6f, 3f))
            ),
            injector.calls.takeLast(2)
        )
        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_D, MotionEvent.ACTION_DOWN))
        assertEquals(
            RecordingEventInjector.Call.TransformGesture(0, NORTH_EAST),
            injector.calls.last()
        )
    }

    @Test
    fun `idle camera pointer is released without disabling aiming`() {
        val injector = RecordingEventInjector()
        val handler = EventHandler(injector)
        handler.updateKeyMapping(
            listOf(
                DraggableItem.FixedKey(
                    id = 1,
                    position = Offset(500f, 200f),
                    type = DraggableItemType.SHOOTING_MODE,
                    keyCode = KEYCODE_MMC,
                    size = 90
                )
            )
        )

        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_TERTIARY, true))
        assertTrue(handler.handlePointerMove(Offset(4f, 2f)))

        handler.releaseIdleShootingPointer()

        assertTrue(handler.shootingModeFlow.value)
        assertEquals(RecordingEventInjector.Call.ReleasePointer(0), injector.calls.last())
        assertTrue(handler.handlePointerMove(Offset(-2f, 1f)))
        assertEquals(
            listOf(
                RecordingEventInjector.Call.InjectPointer(0, Offset(530f, 230f), null),
                RecordingEventInjector.Call.UpdatePointer(0, Offset(-2f, 1f))
            ),
            injector.calls.takeLast(2)
        )
    }

    @Test
    fun `continuous camera movement rebases before the pointer can remain outside screen`() {
        val injector = RecordingEventInjector()
        val handler = EventHandler(injector)
        handler.setPointerBounds(Offset(1000f, 600f))
        handler.updateKeyMapping(
            listOf(
                DraggableItem.FixedKey(
                    id = 1,
                    position = Offset(500f, 200f),
                    type = DraggableItemType.SHOOTING_MODE,
                    keyCode = KEYCODE_MMC,
                    size = 90
                )
            )
        )

        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_TERTIARY, true))
        assertTrue(handler.handlePointerMove(Offset(470f, 0f)))
        assertTrue(handler.handlePointerMove(Offset(20f, 0f)))

        assertEquals(
            listOf(
                RecordingEventInjector.Call.RebasePointer(0, Offset(530f, 230f)),
                RecordingEventInjector.Call.UpdatePointer(0, Offset(20f, 0f))
            ),
            injector.calls.takeLast(2)
        )
    }

    @Test
    fun `display resize releases camera pointer and clamps its next anchor`() {
        val injector = RecordingEventInjector()
        val handler = EventHandler(injector)
        handler.setPointerBounds(Offset(1000f, 600f))
        handler.updateKeyMapping(
            listOf(
                DraggableItem.FixedKey(
                    id = 1,
                    position = Offset(700f, 500f),
                    type = DraggableItemType.SHOOTING_MODE,
                    keyCode = KEYCODE_MMC,
                    size = 90
                )
            )
        )

        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_TERTIARY, true))
        assertTrue(handler.handlePointerMove(Offset(5f, 2f)))
        handler.setPointerBounds(Offset(599f, 399f))
        assertEquals(RecordingEventInjector.Call.ReleasePointer(0), injector.calls.last())

        assertTrue(handler.handlePointerMove(Offset(-4f, -3f)))
        assertEquals(
            listOf(
                RecordingEventInjector.Call.InjectPointer(0, Offset(599f, 399f), null),
                RecordingEventInjector.Call.UpdatePointer(0, Offset(-4f, -3f))
            ),
            injector.calls.takeLast(2)
        )
    }

    @Test
    fun `shooting mode cannot enter without a camera mapping`() {
        val injector = RecordingEventInjector()
        val handler = EventHandler(injector)
        handler.updateKeyMapping(emptyList())

        assertFalse(handler.handleMouseButton(MotionEvent.BUTTON_TERTIARY, true))
        assertFalse(handler.shootingModeFlow.value)
        assertTrue(injector.calls.isEmpty())
    }

    @Test
    fun `fixed fire mouse mapping clicks the visible cursor outside aiming mode`() {
        val injector = RecordingEventInjector()
        val handler = EventHandler(injector)
        handler.mousePointerPosition = Offset(40f, 50f)
        handler.updateKeyMapping(
            listOf(
                DraggableItem.FixedKey(
                    id = 1,
                    position = Offset(700f, 400f),
                    type = DraggableItemType.FIRE,
                    keyCode = KEYCODE_LMC,
                    size = 90
                )
            )
        )

        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_PRIMARY, true))
        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_PRIMARY, false))

        assertEquals(
            listOf(
                RecordingEventInjector.Call.Clear,
                RecordingEventInjector.Call.InjectPointer(
                    pointerId = 0,
                    start = Offset(40f, 50f),
                    end = null
                ),
                RecordingEventInjector.Call.ReleasePointer(0)
            ),
            injector.calls
        )
    }

    @Test
    fun `leaving aiming makes fixed fire click the cursor instead of the fire mapping`() {
        val injector = RecordingEventInjector()
        val handler = EventHandler(injector)
        handler.mousePointerPosition = Offset(320f, 240f)
        handler.updateKeyMapping(
            listOf(
                DraggableItem.FixedKey(
                    id = 1,
                    position = Offset(500f, 200f),
                    type = DraggableItemType.SHOOTING_MODE,
                    keyCode = KEYCODE_MMC,
                    size = 90
                ),
                DraggableItem.FixedKey(
                    id = 2,
                    position = Offset(800f, 500f),
                    type = DraggableItemType.FIRE,
                    keyCode = KEYCODE_LMC,
                    size = 90
                )
            )
        )

        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_TERTIARY, true))
        assertTrue(handler.handlePointerMove(Offset(12f, -4f)))
        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_TERTIARY, true))
        assertFalse(handler.shootingModeFlow.value)

        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_PRIMARY, true))
        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_PRIMARY, false))

        assertEquals(
            listOf(
                RecordingEventInjector.Call.ReleasePointer(0),
                RecordingEventInjector.Call.Clear,
                RecordingEventInjector.Call.InjectPointer(
                    pointerId = 1,
                    start = Offset(320f, 240f),
                    end = null
                ),
                RecordingEventInjector.Call.ReleasePointer(1)
            ),
            injector.calls.takeLast(4)
        )
    }

    @Test
    fun `opposite WASD directions cancel and remaining direction resumes`() {
        val injector = RecordingEventInjector()
        val handler = EventHandler(injector)
        handler.updateKeyMapping(listOf(wasdGroup()))

        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_W, MotionEvent.ACTION_DOWN))
        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_S, MotionEvent.ACTION_DOWN))
        assertTrue(handler.handleKeyEvent(KeyEvent.KEYCODE_W, MotionEvent.ACTION_UP))

        assertEquals(
            listOf(
                RecordingEventInjector.Call.InjectGesture(0, CENTER, NORTH),
                RecordingEventInjector.Call.ReleaseGesture(0),
                RecordingEventInjector.Call.InjectGesture(0, CENTER, SOUTH)
            ),
            injector.calls
        )
    }

    @Test
    fun `wheel direction becomes a swipe at the software cursor in mouse mode`() {
        val injector = RecordingEventInjector()
        val handler = EventHandler(injector)
        handler.setPointerBounds(Offset(999f, 999f))
        handler.mousePointerPosition = Offset(500f, 500f)
        handler.updateKeyMapping(emptyList())

        assertTrue(handler.handleMouseScroll(1f))
        assertTrue(handler.handleMouseScroll(-1f))

        assertEquals(
            listOf(
                RecordingEventInjector.Call.Clear,
                RecordingEventInjector.Call.InjectSwipe(
                    pointerId = 9,
                    start = Offset(500f, 500f),
                    end = Offset(500f, 380f)
                ),
                RecordingEventInjector.Call.Clear,
                RecordingEventInjector.Call.InjectSwipe(
                    pointerId = 9,
                    start = Offset(500f, 500f),
                    end = Offset(500f, 620f)
                )
            ),
            injector.calls
        )
    }

    @Test
    fun `wheel is ignored while aiming`() {
        val injector = RecordingEventInjector()
        val handler = EventHandler(injector)
        handler.appConfig = AppConfig.Default.copy(shootingModeKeyCode = KEYCODE_MMC)
        handler.updateKeyMapping(
            listOf(
                DraggableItem.FixedKey(
                    id = 1,
                    position = Offset(500f, 200f),
                    type = DraggableItemType.SHOOTING_MODE,
                    keyCode = KEYCODE_MMC,
                    size = 90
                )
            )
        )

        assertTrue(handler.handleMouseButton(MotionEvent.BUTTON_TERTIARY, true))
        val callsBeforeScroll = injector.calls.toList()

        assertFalse(handler.handleMouseScroll(1f))
        assertEquals(callsBeforeScroll, injector.calls)
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

    override fun rebasePointer(pointerID: Int, position: Offset) {
        calls += Call.RebasePointer(pointerID, position)
    }

    override fun injectSwipe(pointerID: Int, start: Offset, end: Offset) {
        calls += Call.InjectSwipe(pointerID, start, end)
    }

    override fun clear() {
        calls += Call.Clear
    }

    override fun cancelSystemTouch() {
        calls += Call.CancelSystemTouch
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
        data class RebasePointer(val pointerId: Int, val position: Offset) : Call
        data class InjectSwipe(val pointerId: Int, val start: Offset, val end: Offset) : Call
        data class ReleasePointer(val pointerId: Int) : Call
        data object Clear : Call
        data object CancelSystemTouch : Call
    }
}
