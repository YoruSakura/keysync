package com.devoid.keysync.domain.touch

import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WheelGestureControllerTest {
    private val viewport = TouchViewport(1920.0, 1080.0)
    private val cursor = TouchPoint(900.0, 700.0)
    private fun dispatcher(injector: TouchCoreEventInjector): MumuActionDispatcher {
        val text = requireNotNull(javaClass.classLoader!!.getResourceAsStream("com.hypergryph.endfield-默认操作方案.json"))
            .use { it.reader(Charsets.UTF_8).readText() }
        return MumuActionDispatcher(MumuProfileCodec.decode(text), injector, viewport)
    }

    @Test fun `wheel bursts move one finger instead of replacing swipes with taps`() = runBlocking {
        val backend = RecordingTouchBackend()
        val injector = TouchCoreEventInjector(backend, nowMillis = { 100L })
        val dispatcher = dispatcher(injector)
        dispatcher.scroll(1f, cursor)
        dispatcher.scroll(1f, cursor)
        dispatcher.awaitIdle()
        assertEquals(1, backend.frames.count { it.action == TouchAction.DOWN })
        assertEquals(1, backend.frames.count { it.action == TouchAction.UP })
        val moves = backend.frames.filter { it.action == TouchAction.MOVE }
        assertTrue(moves.size >= 4)
        assertTrue(moves.zipWithNext().all { (a, b) -> a.contacts.single().position.y < b.contacts.single().position.y })
        assertEquals(cursor.y + viewport.height * .12 * 2, backend.frames.last().contacts.single().position.y, 1e-6)
        assertEquals(0, injector.pipeline.diagnostics().activeContacts)
        dispatcher.scroll(-1f, cursor)
        dispatcher.awaitIdle()
        assertEquals(cursor.y - viewport.height * .12, backend.frames.last().contacts.single().position.y, 1e-6)
        assertEquals(0L, injector.pipeline.diagnostics().rejected)
        dispatcher.close(); injector.pipeline.shutdown()
    }

    @Test fun `outward scroll at the display edge does not become a click`() = runBlocking {
        val backend = RecordingTouchBackend()
        val injector = TouchCoreEventInjector(backend, nowMillis = { 100L })
        val dispatcher = dispatcher(injector)
        dispatcher.scroll(1f, TouchPoint(900.0, viewport.height - 1))
        dispatcher.awaitIdle()
        assertTrue(backend.frames.isEmpty())
        dispatcher.close(); injector.pipeline.shutdown()
    }

    @Test fun `aiming wheel pinches in both directions without dropping movement or attack`() = runBlocking {
        val backend = RecordingTouchBackend()
        val injector = TouchCoreEventInjector(backend, nowMillis = { 100L })
        val dispatcher = dispatcher(injector)
        dispatcher.key(KeyEvent.KEYCODE_ALT_LEFT, true)
        dispatcher.key(KeyEvent.KEYCODE_ALT_LEFT, false)
        dispatcher.key(KeyEvent.KEYCODE_W, true)
        dispatcher.mouseButton(MotionEvent.BUTTON_PRIMARY, true, cursor)
        dispatcher.move(TouchPoint(10.0, 0.0))
        dispatcher.awaitIdle()
        assertEquals(3, injector.pipeline.diagnostics().activeContacts)
        val gameplayOwners = backend.frames.last().contacts.take(2).map { it.owner }.toSet()
        val zoomOwners = setOf(ContactId(-20_005), ContactId(-20_006))
        dispatcher.scroll(1f, cursor)
        dispatcher.move(TouchPoint(100.0, 0.0)) // Moving the mouse must not reintroduce a third gesture finger.
        dispatcher.awaitIdle()
        val outward = backend.frames.filter { it.action == TouchAction.MOVE && it.contacts.any { it.owner in zoomOwners } }
        assertTrue(outward.isNotEmpty())
        assertTrue(outward.all { frame -> frame.contacts.size == 4 && frame.contacts.map { it.owner }.containsAll(gameplayOwners) })
        val outwardPoints = outward.last().contacts.filter { it.owner in zoomOwners }.map { it.position }
        assertTrue(outwardPoints[1].x - outwardPoints[0].x > viewport.height * .3)
        assertEquals(outwardPoints[0].y, outwardPoints[1].y, 0.0)
        assertEquals(2, injector.pipeline.diagnostics().activeContacts)
        val nextFrame = backend.frames.size
        dispatcher.scroll(-1f, cursor)
        dispatcher.awaitIdle()
        val inward = backend.frames.drop(nextFrame).last { it.action == TouchAction.MOVE }
        val inwardPoints = inward.contacts.filter { it.owner in zoomOwners }.map { it.position }
        assertTrue(inwardPoints[1].x - inwardPoints[0].x < viewport.height * .3)
        assertTrue(dispatcher.shootingModeFlow.value)
        dispatcher.move(TouchPoint(10.0, 0.0))
        dispatcher.awaitIdle()
        assertEquals(3, injector.pipeline.diagnostics().activeContacts)
        assertEquals(0L, injector.pipeline.diagnostics().rejected)
        dispatcher.close(); injector.pipeline.shutdown()
    }

    @Test fun `mode switches and reset release wheel fingers before another gesture can move`() = runBlocking {
        val backend = RecordingTouchBackend()
        val injector = TouchCoreEventInjector(backend, nowMillis = { 100L })
        val dispatcher = dispatcher(injector)
        dispatcher.scroll(1f, cursor)
        dispatcher.key(KeyEvent.KEYCODE_ALT_LEFT, true)
        dispatcher.key(KeyEvent.KEYCODE_ALT_LEFT, false)
        dispatcher.scroll(1f, cursor)
        dispatcher.key(KeyEvent.KEYCODE_ALT_LEFT, true)
        dispatcher.key(KeyEvent.KEYCODE_ALT_LEFT, false)
        dispatcher.awaitIdle()
        assertEquals(0, injector.pipeline.diagnostics().activeContacts)
        val stoppedAt = backend.frames.size
        delay(100)
        injector.pipeline.awaitIdle()
        assertEquals(stoppedAt, backend.frames.size)
        dispatcher.scroll(-1f, cursor)
        dispatcher.reset()
        delay(100)
        injector.pipeline.awaitIdle()
        assertEquals(0, injector.pipeline.diagnostics().activeContacts)
        assertEquals(0L, injector.pipeline.diagnostics().rejected)
        dispatcher.close(); injector.pipeline.shutdown()
    }
}
