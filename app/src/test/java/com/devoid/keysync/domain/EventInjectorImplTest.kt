package com.devoid.keysync.domain

import android.view.InputEvent
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.DraggableItemType
import com.devoid.keysync.model.EventManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class EventInjectorImplTest {

    @Test
    fun `rapid release and reinjection preserve call order`() = runBlocking {
        val manager = RecordingEventManager()
        val injector = TestEventInjector(manager)

        try {
            injector.injectPointer(1, Offset(10f, 10f))
            injector.releasePointer(1)
            injector.injectPointer(1, Offset(20f, 20f))
            injector.awaitIdle()

            assertEquals(
                listOf(
                    "add:1",
                    "action:1:down",
                    "event:${MotionEvent.ACTION_DOWN}",
                    "action:1:up",
                    "event:${MotionEvent.ACTION_UP}",
                    "remove:1",
                    "add:1",
                    "action:1:down",
                    "event:${MotionEvent.ACTION_DOWN}"
                ),
                manager.calls
            )
        } finally {
            injector.close()
        }
    }

    @Test
    fun `duplicate pointer down does not emit another down action`() = runBlocking {
        val manager = RecordingEventManager()
        val injector = TestEventInjector(manager)

        try {
            injector.injectPointer(2, Offset(10f, 10f))
            injector.injectPointer(2, Offset(20f, 20f))
            injector.awaitIdle()

            assertEquals(2, manager.calls.count { it == "add:2" })
            assertEquals(1, manager.calls.count { it == "action:2:down" })
            assertEquals(1, manager.calls.count { it == "event:${MotionEvent.ACTION_DOWN}" })
        } finally {
            injector.close()
        }
    }

    @Test
    fun `rapid aim toggles keep the WASD pointer and ignore stale camera moves`() = runBlocking {
        val manager = RecordingEventManager()
        val injector = TestEventInjector(manager)

        try {
            injector.injectPointer(0, Offset(100f, 100f))
            repeat(5) {
                injector.injectPointer(1, Offset(500f, 200f))
                injector.releasePointer(1)
                injector.updatePointerPosition(1, Offset(4f, -2f))
            }
            injector.injectPointer(1, Offset(500f, 200f))
            injector.updatePointerPosition(1, Offset(4f, -2f))
            injector.awaitIdle()

            assertEquals(listOf(0, 1), manager.activePointerIds())
            assertEquals(6, manager.calls.count { it == "add:1" })
            assertEquals(5, manager.calls.count { it == "remove:1" })
            assertEquals(1, manager.calls.count { it == "event:${MotionEvent.ACTION_MOVE}" })
        } finally {
            injector.close()
        }
    }

    @Test
    fun `mouse move bursts are coalesced and discarded across pointer release`() = runBlocking {
        val manager = RecordingEventManager()
        val injector = TestEventInjector(manager)
        val addPointerGate = CompletableDeferred<Unit>()
        manager.addPointerGate = addPointerGate

        try {
            injector.injectPointer(1, Offset(500f, 200f))
            repeat(500) {
                injector.updatePointerPosition(1, Offset(1f, -1f))
            }
            injector.releasePointer(1)
            injector.injectPointer(1, Offset(500f, 200f))
            injector.updatePointerPosition(1, Offset(4f, -2f))

            addPointerGate.complete(Unit)
            injector.awaitIdle()

            assertEquals(listOf(1), manager.activePointerIds())
            assertEquals(1, manager.calls.count { it == "event:${MotionEvent.ACTION_MOVE}" })
        } finally {
            injector.close()
        }
    }

    @Test
    fun `camera rebase preserves moves on both sides of the ordered restart`() = runBlocking {
        val manager = RecordingEventManager()
        val injector = TestEventInjector(manager)
        val addPointerGate = CompletableDeferred<Unit>()
        manager.addPointerGate = addPointerGate

        try {
            injector.injectPointer(1, Offset(500f, 200f))
            injector.updatePointerPosition(1, Offset(4f, -2f))
            injector.rebasePointer(1, Offset(500f, 200f))
            injector.updatePointerPosition(1, Offset(6f, 3f))
            addPointerGate.complete(Unit)
            injector.awaitIdle()

            assertEquals(listOf(1), manager.activePointerIds())
            assertEquals(
                listOf("offset:1:4.0:-2.0", "offset:1:6.0:3.0"),
                manager.calls.filter { it.startsWith("offset:") }
            )
            assertEquals(2, manager.calls.count { it == "add:1" })
            assertEquals(1, manager.calls.count { it == "remove:1" })
        } finally {
            injector.close()
        }
    }

    @Test
    fun `system touch cancellation clears local pointer state in queue order`() = runBlocking {
        val manager = RecordingEventManager()
        val injector = TestEventInjector(manager)

        try {
            injector.injectPointer(1, Offset(500f, 200f))
            injector.cancelSystemTouch()
            injector.awaitIdle()

            assertEquals(emptyList<Int>(), manager.activePointerIds())
            assertEquals(1, injector.localResetCount)
            assertEquals(
                listOf(
                    "add:1",
                    "action:1:down",
                    "event:${MotionEvent.ACTION_DOWN}",
                    "event:${MotionEvent.ACTION_CANCEL}"
                ),
                manager.calls
            )
        } finally {
            injector.close()
        }
    }

    @Test
    fun `reversed touch bounds are normalized before choosing a point`() = runBlocking {
        val manager = RecordingEventManager()
        val injector = TestEventInjector(manager)

        try {
            injector.injectPointer(3, Offset(80f, 90f), Offset(20f, 10f))
            injector.awaitIdle()

            assertEquals(listOf(3), manager.activePointerIds())
        } finally {
            injector.close()
        }
    }

    @Test
    fun `cursor click becomes a fresh primary gesture after an aiming menu transition`() =
        runBlocking {
            val manager = RecordingEventManager()
            val injector = TestEventInjector(manager)
            val handler = EventHandler(injector)
            handler.mousePointerPosition = Offset(320f, 240f)
            handler.updateKeyMapping(
                listOf(
                    DraggableItem.VariableKey(
                        id = 1,
                        position = Offset(400f, 300f),
                        keyCode = android.view.KeyEvent.KEYCODE_4,
                        size = 90
                    ),
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

            try {
                // A held mapping represents another pointer which can survive
                // the game changing from its world view to a menu.
                handler.handleKeyEvent(android.view.KeyEvent.KEYCODE_4, MotionEvent.ACTION_DOWN)
                handler.handleMouseButton(MotionEvent.BUTTON_TERTIARY, true)
                handler.handlePointerMove(Offset(12f, -4f))
                handler.handleMouseButton(MotionEvent.BUTTON_TERTIARY, true)
                handler.handleMouseButton(MotionEvent.BUTTON_PRIMARY, true)
                handler.handleMouseButton(MotionEvent.BUTTON_PRIMARY, false)
                injector.awaitIdle()

                assertEquals(emptyList<Int>(), manager.activePointerIds())
                assertEquals(2 to Offset(320f, 240f), manager.addedPointerPositions.last())
                assertEquals(
                    listOf(
                        "event:${MotionEvent.ACTION_CANCEL}",
                        "add:2",
                        "action:2:down",
                        "event:${MotionEvent.ACTION_DOWN}",
                        "action:2:up",
                        "event:${MotionEvent.ACTION_UP}",
                        "remove:2"
                    ),
                    manager.calls.takeLast(7)
                )
            } finally {
                injector.close()
            }
        }


    @Test
    fun `wheel swipe is one complete ordered touch gesture`() = runBlocking {
        val manager = RecordingEventManager()
        val injector = TestEventInjector(manager)

        try {
            injector.injectSwipe(9, Offset(300f, 500f), Offset(300f, 300f))
            injector.awaitIdle()

            assertEquals(emptyList<Int>(), manager.activePointerIds())
            assertEquals(1, manager.calls.count { it == "add:9" })
            assertEquals(4, manager.calls.count { it == "event:${MotionEvent.ACTION_MOVE}" })
            assertEquals(
                listOf(
                    "add:9",
                    "action:9:down",
                    "event:${MotionEvent.ACTION_DOWN}"
                ),
                manager.calls.take(3)
            )
            assertEquals(
                listOf(
                    "action:9:up",
                    "event:${MotionEvent.ACTION_UP}",
                    "remove:9"
                ),
                manager.calls.takeLast(3)
            )
        } finally {
            injector.close()
        }
    }
}

private class TestEventInjector(eventManager: EventManager) :
    EventInjectorImpl(eventManager) {
    var localResetCount = 0
        private set

    override fun inject(event: InputEvent): Boolean = true

    override fun onLocalInputReset() {
        localResetCount++
    }
}

private class RecordingEventManager : EventManager {
    val calls = mutableListOf<String>()
    val addedPointerPositions = mutableListOf<Pair<Int, Offset>>()
    var addPointerGate: CompletableDeferred<Unit>? = null
    private val activePointers = mutableListOf<Int>()

    override suspend fun addPointer(id: Int, x: Float, y: Float): Int {
        addPointerGate?.await()
        calls += "add:$id"
        addedPointerPositions += id to Offset(x, y)
        if (id in activePointers) return -2
        activePointers += id
        return activePointers.lastIndex
    }

    override suspend fun updatePointer(id: Int, x: Float, y: Float): Boolean =
        id in activePointers

    override suspend fun removePointer(id: Int): Int {
        calls += "remove:$id"
        val index = activePointers.indexOf(id)
        if (index >= 0) activePointers.removeAt(index)
        return index
    }

    override suspend fun createMotionEvent(action: Int): MotionEvent? {
        calls += "event:$action"
        return null
    }

    override suspend fun offsetPointer(id: Int, offset: Offset): Boolean {
        calls += "offset:$id:${offset.x}:${offset.y}"
        return id in activePointers
    }

    override suspend fun getPointerAction(pointerId: Int, isUp: Boolean): Int {
        calls += "action:$pointerId:${if (isUp) "up" else "down"}"
        if (pointerId !in activePointers) return -1
        return if (isUp) MotionEvent.ACTION_UP else MotionEvent.ACTION_DOWN
    }

    override suspend fun getPointerLocation(pointerId: Int): Offset? =
        if (pointerId in activePointers) Offset.Zero else null

    override suspend fun clear(): Boolean {
        activePointers.clear()
        return true
    }

    fun activePointerIds(): List<Int> = activePointers.toList()
}
