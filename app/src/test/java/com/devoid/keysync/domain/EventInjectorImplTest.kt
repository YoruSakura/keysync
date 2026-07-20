package com.devoid.keysync.domain

import android.view.InputEvent
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
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
}

private class TestEventInjector(eventManager: EventManager) :
    EventInjectorImpl(eventManager) {
    override fun inject(event: InputEvent) = Unit
}

private class RecordingEventManager : EventManager {
    val calls = mutableListOf<String>()
    var addPointerGate: CompletableDeferred<Unit>? = null
    private val activePointers = mutableListOf<Int>()

    override suspend fun addPointer(id: Int, x: Float, y: Float): Int {
        addPointerGate?.await()
        calls += "add:$id"
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

    override suspend fun offsetPointer(id: Int, offset: Offset): Boolean =
        id in activePointers

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
