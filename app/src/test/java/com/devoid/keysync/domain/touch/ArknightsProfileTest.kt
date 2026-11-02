package com.devoid.keysync.domain.touch

import android.view.KeyEvent
import android.view.MotionEvent
import com.devoid.keysync.domain.KEYCODE_RMC
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ArknightsProfileTest {
    private fun profile() = requireNotNull(javaClass.classLoader!!.getResourceAsStream("com.hypergryph.arknights-默认操作方案.json"))
        .use { MumuProfileCodec.decode(it.reader(Charsets.UTF_8).readText()) }
    private val viewport = TouchViewport(1600.0, 900.0)
    private val cursor = TouchPoint(400.0, 500.0)
    private val back = SimulatedKey(KeyEvent.KEYCODE_BACK, 158)

    @Test fun `actual profile preserves all mappings including held observe and out of screen curve`() {
        val profile = profile()
        assertEquals(7, profile.buttons.size)
        assertEquals(8, profile.macros.size)
        assertNull(profile.camera)
        assertNull(profile.joystick)
        assertEquals(KeyEvent.KEYCODE_ALT_LEFT, profile.observe!!.key)
        val deploy = profile.macros.single { it.key == KEYCODE_RMC }
        assertEquals(MacroAction.ReleaseAll, deploy.press.first())
        assertEquals(MacroAction.Curve(listOf(TouchPoint(2.714441, 4.826255)), 17, true),
            deploy.press.filterIsInstance<MacroAction.Curve>().single())
        assertEquals(listOf(MacroAction.ReleaseAll, MacroAction.MouseButton(false)), deploy.release)
        assertEquals(profile, MumuProfileCodec.decode(profile.source.toString()))
    }

    @Test fun `D clicks current cursor then skill and survives physical release during sleep`() = runBlocking {
        val backend = RecordingTouchBackend()
        val injector = TouchCoreEventInjector(backend, nowMillis = { 100L })
        val dispatcher = MumuActionDispatcher(profile(), injector, viewport)
        dispatcher.key(KeyEvent.KEYCODE_D, true, cursor)
        dispatcher.key(KeyEvent.KEYCODE_D, false, cursor)
        dispatcher.awaitIdle()
        assertEquals(listOf(TouchAction.DOWN, TouchAction.UP, TouchAction.DOWN, TouchAction.UP), backend.frames.map { it.action })
        assertEquals(cursor, backend.frames.first().contacts.single().position)
        assertEquals(viewport.resolve(TouchPoint(.665, .542222)), backend.frames[2].contacts.single().position)
        assertEquals(0, injector.pipeline.diagnostics().activeContacts)
        dispatcher.close(); injector.pipeline.shutdown()
    }

    @Test fun `C drags at cursor while F holds BACK and reset releases both`() = runBlocking {
        val backend = RecordingTouchBackend()
        val injector = TouchCoreEventInjector(backend, nowMillis = { 100L })
        val dispatcher = MumuActionDispatcher(profile(), injector, viewport)
        dispatcher.key(KeyEvent.KEYCODE_C, true, cursor); dispatcher.awaitIdle()
        dispatcher.move(TouchPoint(20.0, -10.0)); dispatcher.awaitIdle()
        assertEquals(cursor + TouchPoint(20.0, -10.0), backend.frames.last().contacts.single().position)
        dispatcher.key(KeyEvent.KEYCODE_F, true); dispatcher.awaitIdle()
        assertEquals(listOf(true), backend.outputs.filterIsInstance<RecordingTouchBackend.KeyOutput>().map { it.pressed })
        dispatcher.key(KeyEvent.KEYCODE_F, false); dispatcher.awaitIdle()
        dispatcher.key(KeyEvent.KEYCODE_F, true); dispatcher.awaitIdle()
        dispatcher.reset(); dispatcher.awaitIdle()
        val keys = backend.outputs.filterIsInstance<RecordingTouchBackend.KeyOutput>()
        assertEquals(listOf(true, false, true, false), keys.map { it.pressed })
        assertTrue(keys.all { it.key == back && it.downTime == 100L && it.eventTime == 100L })
        assertEquals(0, injector.pipeline.diagnostics().activeContacts)
        dispatcher.close(); injector.pipeline.shutdown()
    }

    @Test fun `Alt only drags while held and releases before cursor clicking resumes`() = runBlocking {
        val backend = RecordingTouchBackend()
        val injector = TouchCoreEventInjector(backend, nowMillis = { 100L })
        val profile = profile()
        val dispatcher = MumuActionDispatcher(profile, injector, viewport)
        dispatcher.key(KeyEvent.KEYCODE_ALT_LEFT, true, cursor)
        assertTrue(dispatcher.shootingModeFlow.value)
        dispatcher.move(TouchPoint(10.0, -5.0), cursor); dispatcher.awaitIdle()
        val anchor = viewport.resolve(profile.observe!!.anchor)
        assertEquals(anchor, backend.frames.first().contacts.single().position)
        assertEquals(anchor + TouchPoint(40.0, -20.0), backend.frames.last().contacts.single().position)
        dispatcher.key(KeyEvent.KEYCODE_ALT_LEFT, false, cursor); dispatcher.awaitIdle()
        assertFalse(dispatcher.shootingModeFlow.value)
        assertEquals(TouchAction.UP, backend.frames.last().action)
        dispatcher.mouseButton(MotionEvent.BUTTON_PRIMARY, true, cursor)
        dispatcher.mouseButton(MotionEvent.BUTTON_PRIMARY, false, cursor)
        dispatcher.awaitIdle()
        assertEquals(cursor, backend.frames.last().contacts.single().position)
        assertEquals(0L, injector.pipeline.diagnostics().rejected)
        dispatcher.close(); injector.pipeline.shutdown()
    }

    @Test fun `right button preserves pause drag BACK order and release clears held outputs`() = runBlocking {
        val backend = RecordingTouchBackend()
        val injector = TouchCoreEventInjector(backend, nowMillis = { 100L })
        val profile = profile()
        val dispatcher = MumuActionDispatcher(profile, injector, viewport)
        dispatcher.mouseButton(MotionEvent.BUTTON_PRIMARY, true, cursor); dispatcher.awaitIdle()
        dispatcher.mouseButton(MotionEvent.BUTTON_SECONDARY, true, cursor); dispatcher.awaitIdle()
        assertEquals(listOf(TouchAction.DOWN, TouchAction.UP), backend.frames.take(2).map { it.action })
        assertEquals(viewport.resolve(profile.buttons.single { it.key == KeyEvent.KEYCODE_0 }.point),
            backend.frames[2].contacts.single().position)
        val drag = backend.frames.single { it.action == TouchAction.MOVE }
        assertEquals(viewport.resolve(TouchPoint(2.714441, 4.826255)), drag.contacts.single().position)
        val keys = backend.outputs.filterIsInstance<RecordingTouchBackend.KeyOutput>()
        assertEquals(listOf(true, false, false), keys.map { it.pressed })
        assertTrue(backend.outputs.indexOf(drag) < backend.outputs.indexOf(keys.first()))
        assertEquals(2, injector.pipeline.diagnostics().activeContacts)
        dispatcher.mouseButton(MotionEvent.BUTTON_SECONDARY, false, cursor); dispatcher.awaitIdle()
        assertEquals(0, injector.pipeline.diagnostics().activeContacts)
        assertEquals(0L, injector.pipeline.diagnostics().rejected)
        assertTrue(backend.frames.none { it.action == TouchAction.CANCEL })
        dispatcher.close(); injector.pipeline.shutdown()
    }

    @Test fun `immediate macro release and stopping delayed macros never leave late DOWN`() = runBlocking {
        val backend = RecordingTouchBackend()
        val injector = TouchCoreEventInjector(backend, nowMillis = { 100L })
        val dispatcher = MumuActionDispatcher(profile(), injector, viewport)
        dispatcher.mouseButton(MotionEvent.BUTTON_SECONDARY, true, cursor)
        dispatcher.mouseButton(MotionEvent.BUTTON_SECONDARY, false, cursor)
        dispatcher.awaitIdle()
        assertEquals(0, injector.pipeline.diagnostics().activeContacts)
        dispatcher.key(KeyEvent.KEYCODE_E, true, cursor)
        dispatcher.reset()
        dispatcher.awaitIdle()
        assertEquals(0, injector.pipeline.diagnostics().activeContacts)
        assertFalse(backend.outputs.lastOrNull() is RecordingTouchBackend.KeyOutput &&
            (backend.outputs.last() as RecordingTouchBackend.KeyOutput).pressed)
        assertEquals(0L, injector.pipeline.diagnostics().rejected)
        dispatcher.close(); injector.pipeline.shutdown()
    }
}
