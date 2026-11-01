package com.devoid.keysync.domain.touch

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

internal class RecordingTouchBackend : TouchBackend {
    val frames = mutableListOf<TouchFrame>()
    var rejectNext = false
    var cancels = 0
    override fun submit(frame: TouchFrame): Boolean {
        if (rejectNext) { rejectNext = false; return false }
        synchronized(frames) { frames += frame }
        return true
    }
    override fun cancel(): Boolean { cancels++; return true }
}

class TouchSessionTest {
    private val a = ContactId(5000)
    private val b = ContactId(-2000)
    private val c = ContactId(12345)
    private val point = TouchPoint(100.0, 200.0)
    private var time = 10L

    @Test fun `pointer index shifts but pointer id and gesture downTime stay stable`() {
        val backend = RecordingTouchBackend()
        val session = TouchSessionManager(backend, { time++ }, { 3 })
        session.apply(TouchCommand.Down(a, point))
        session.apply(TouchCommand.Down(b, point))
        session.apply(TouchCommand.Down(c, point))
        session.apply(TouchCommand.Up(b))
        session.apply(TouchCommand.Move(mapOf(c to TouchPoint(300.0, 400.0))))
        session.apply(TouchCommand.Up(a))
        session.apply(TouchCommand.Up(c))
        assertEquals(listOf(TouchAction.DOWN, TouchAction.POINTER_DOWN, TouchAction.POINTER_DOWN,
            TouchAction.POINTER_UP, TouchAction.MOVE, TouchAction.POINTER_UP, TouchAction.UP), backend.frames.map { it.action })
        assertEquals(1, backend.frames[3].actionIndex)
        assertEquals(listOf(0, 2), backend.frames[4].contacts.map { it.pointerId })
        assertEquals(1, backend.frames.last().contacts.size)
        assertEquals(2, backend.frames.last().contacts.single().pointerId)
        assertEquals(1, backend.frames.map { it.downTime }.distinct().size)
        assertTrue(backend.frames.all { it.displayId == 3 && it.eventTime >= it.downTime })
        assertTrue(session.contacts.isEmpty())
    }

    @Test fun `camera rebase only replaces camera while joystick and fire survive`() {
        val backend = RecordingTouchBackend()
        val session = TouchSessionManager(backend, { time++ })
        session.apply(TouchCommand.Down(a, point))
        session.apply(TouchCommand.Down(b, point))
        session.apply(TouchCommand.Down(c, point))
        session.apply(TouchCommand.Rebase(b, TouchPoint(50.0, 60.0)))
        assertEquals(listOf(TouchAction.POINTER_UP, TouchAction.POINTER_DOWN), backend.frames.takeLast(2).map { it.action })
        assertEquals(listOf(a, c, b), session.contacts.map { it.owner })
        assertEquals(1, backend.frames.map { it.downTime }.distinct().size)
    }

    @Test fun `duplicate edges and nonexistent absolute moves are rejected`() {
        val backend = RecordingTouchBackend()
        val session = TouchSessionManager(backend, { time++ })
        session.apply(TouchCommand.Down(a, point))
        session.apply(TouchCommand.Down(a, point))
        session.apply(TouchCommand.Up(b))
        session.apply(TouchCommand.Move(mapOf(b to point)))
        assertEquals(3L, session.rejectedTransitions)
        assertEquals(1, backend.frames.size)
    }

    @Test fun `failed injection does not commit next state and blocks stale commands until explicit reset`() {
        val backend = RecordingTouchBackend()
        var failures = 0
        val session = TouchSessionManager(backend, { time++ }, onFailure = { failures++ })
        session.apply(TouchCommand.Down(a, point))
        backend.rejectNext = true
        session.apply(TouchCommand.Down(b, point))
        assertTrue(session.faulted)
        assertTrue(session.contacts.isEmpty())
        session.apply(TouchCommand.Down(c, point))
        session.apply(TouchCommand.MoveBy(mapOf(a to point)))
        assertEquals(1, backend.frames.size)
        assertEquals(1, failures)
        assertEquals(1, backend.cancels)
        session.apply(TouchCommand.Reset)
        session.apply(TouchCommand.Down(c, point))
        assertEquals(TouchAction.DOWN, backend.frames.last().action)
        assertEquals(c, session.contacts.single().owner)
    }

    @Test fun `failed camera release never issues replacement DOWN`() {
        val backend = RecordingTouchBackend()
        val session = TouchSessionManager(backend, { time++ })
        session.apply(TouchCommand.Down(a, point))
        backend.rejectNext = true
        session.apply(TouchCommand.Rebase(a, point))
        assertEquals(1, backend.frames.size)
        assertTrue(session.faulted)
    }

    @Test fun `one thousand taps and randomized multi contact stream finish cleanly`() {
        val backend = RecordingTouchBackend()
        val session = TouchSessionManager(backend, { time++ })
        repeat(1000) { session.apply(TouchCommand.Down(a, point)); session.apply(TouchCommand.Up(a)) }
        val random = Random(20261008)
        repeat(5000) {
            val id = ContactId(random.nextInt(10))
            val active = session.contacts.any { it.owner == id }
            val command = if (!active) TouchCommand.Down(id, point) else when (random.nextInt(3)) {
                0 -> TouchCommand.Up(id)
                1 -> TouchCommand.Move(mapOf(id to TouchPoint(random.nextDouble(1000.0), random.nextDouble(1000.0))))
                else -> TouchCommand.Rebase(id, point)
            }
            session.apply(command)
        }
        session.apply(TouchCommand.Cancel)
        assertTrue(session.contacts.isEmpty())
        assertEquals(0L, session.rejectedTransitions)
        assertEquals(0L, session.failures)
        backend.frames.forEach { frame ->
            assertTrue(frame.contacts.size in 1..10)
            assertEquals(frame.contacts.size, frame.contacts.map { it.pointerId }.distinct().size)
            assertTrue(frame.actionIndex in frame.contacts.indices)
        }
    }
}
