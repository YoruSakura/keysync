package com.devoid.keysync.domain.touch

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import com.devoid.keysync.domain.EventHandler
import com.devoid.keysync.domain.KEYCODE_MMC
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.DraggableItemType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MumuProfileTest {
    private fun original() = requireNotNull(javaClass.classLoader!!.getResourceAsStream("com.hypergryph.endfield-默认操作方案.json"))
        .use { it.reader(Charsets.UTF_8).readText() }
    private fun injector(backend: RecordingTouchBackend) = TouchCoreEventInjector(backend, nowMillis = { 100L })

    @Test fun `actual JSON maps Windows keys and uses macro command coordinates not icon coordinates`() {
        val profile = MumuProfileCodec.decode(original())
        assertEquals(19, profile.buttons.size)
        assertEquals(1, profile.buttons.count { it.fire })
        assertEquals(KeyEvent.KEYCODE_ALT_LEFT, profile.camera!!.key)
        assertEquals(.8376599756770017, profile.camera.anchor.x, 1e-12)
        assertEquals(50.0, profile.camera.sensitivityX, 0.0)
        assertEquals(1, profile.tips.size)
        assertEquals(KeyEvent.KEYCODE_ALT_LEFT, profile.tips.single().key)
        assertEquals("呼出/隐藏鼠标", profile.tips.single().icon.description)
        assertEquals(KeyEvent.KEYCODE_M, profile.macros.single().key)
        assertEquals("地图", profile.macros.single().icon.description)
        assertEquals(TouchPoint(.03456356180433509, .103125), profile.macros.single().icon.point)
        assertEquals(listOf(MacroAction.Click(TouchPoint(.072056, .106250)), MacroAction.ExitStaring), profile.macros.single().press)
        val roundTrip = MumuProfileCodec.decode(profile.source.toString())
        assertEquals(profile, roundTrip)
    }

    @Test fun `display metadata preserves separate icon position without moving the touch target`() {
        val root = Json.parseToJsonElement(original()).jsonObject
        val mappings = root.getValue("keymaps").jsonArray.toMutableList()
        val button = mappings.first().jsonObject
        val icon = button.getValue("icon").jsonObject
        mappings[0] = JsonObject(button + mapOf(
            "editor_icon_scale" to JsonPrimitive(1.5),
            "icon" to JsonObject(icon + mapOf(
                "visibility" to JsonPrimitive(false),
                "rel_position" to buildJsonObject { put("rel_x", .2); put("rel_y", .3) }
            ))
        ))
        val profile = MumuProfileCodec.decode(JsonObject(root + ("keymaps" to JsonArray(mappings))).toString())
        val decoded = profile.buttons.first()
        assertEquals(TouchPoint(.2, .3), decoded.icon.point)
        assertFalse(decoded.icon.visible)
        assertEquals(1.5, decoded.icon.scale, 0.0)
        assertEquals(TouchPoint(.9193481430762263, .8481818598679383), decoded.point)
    }

    @Test fun `layout calibration moves touch and marker together and preserves unrelated mappings and policies`() {
        val profile = MumuProfileCodec.decode(original())
        val button = profile.buttons.first()
        val joystick = profile.joystick!!
        val calibrated = MumuLayoutEditor.apply(profile, mapOf(
            MumuLayoutTarget(button.icon.mappingIndex, MumuLayoutField.WORK) to TouchPoint(.9, .8),
            MumuLayoutTarget(joystick.icon.mappingIndex, MumuLayoutField.WORK) to TouchPoint(444.0 / 3199, 1754.0 / 2135)
        ))
        assertEquals(TouchPoint(.9, .8), calibrated.buttons.first().point)
        assertEquals(calibrated.buttons.first().point, calibrated.buttons.first().icon.point)
        val center = TouchViewport(3200.0, 2136.0).resolve(calibrated.joystick!!.center)
        assertEquals(444.0, center.x, 1e-9)
        assertEquals(1754.0, center.y, 1e-9)
        assertEquals(joystick.radius, calibrated.joystick.radius, 0.0)
        assertEquals(profile.source["info"], calibrated.source["info"])
        assertEquals(profile.source["param"], calibrated.source["param"])
        profile.source.getValue("keymaps").jsonArray.forEachIndexed { index, originalMapping ->
            val changedMapping = calibrated.source.getValue("keymaps").jsonArray[index]
            if (index != button.icon.mappingIndex && index != joystick.icon.mappingIndex) {
                assertEquals(originalMapping, changedMapping)
            } else {
                assertEquals(originalMapping.jsonObject - setOf("rel_work_position", "icon"),
                    changedMapping.jsonObject - setOf("rel_work_position", "icon"))
            }
        }
    }

    @Test fun `macro calibration edits the actual click independently of its icon and executes at tablet coordinates`() = runBlocking {
        val original = MumuProfileCodec.decode(original())
        val macro = original.macros.single()
        val calibrated = MumuLayoutEditor.apply(original, mapOf(
            MumuLayoutTarget(macro.icon.mappingIndex, MumuLayoutField.PRESS, 0) to TouchPoint(.1, .15)
        ))
        assertEquals(macro.icon, calibrated.macros.single().icon)
        assertEquals(MacroAction.ExitStaring, calibrated.macros.single().press.last())
        val backend = RecordingTouchBackend()
        val injector = injector(backend)
        val dispatcher = MumuActionDispatcher(calibrated, injector, TouchViewport(3200.0, 2136.0))
        dispatcher.key(KeyEvent.KEYCODE_M, true)
        dispatcher.key(KeyEvent.KEYCODE_M, false)
        dispatcher.awaitIdle()
        val position = backend.frames.first().contacts.single().position
        assertEquals(319.9, position.x, 1e-9)
        assertEquals(320.25, position.y, 1e-9)
        assertEquals(TouchAction.UP, backend.frames.last().action)
        dispatcher.close(); injector.pipeline.shutdown()
    }

    @Test fun `camera joystick attack coexist and M releases only macro and camera`() = runBlocking {
        val backend = RecordingTouchBackend()
        val injector = injector(backend)
        val dispatcher = MumuActionDispatcher(MumuProfileCodec.decode(original()), injector, TouchViewport(1920.0, 1080.0))
        dispatcher.key(KeyEvent.KEYCODE_ALT_LEFT, true)
        dispatcher.key(KeyEvent.KEYCODE_ALT_LEFT, false)
        dispatcher.key(KeyEvent.KEYCODE_W, true)
        dispatcher.key(KeyEvent.KEYCODE_D, true)
        dispatcher.move(TouchPoint(10.0, 4.0))
        dispatcher.mouseButton(MotionEvent.BUTTON_PRIMARY, true, TouchPoint(20.0, 30.0))
        dispatcher.awaitIdle()
        assertEquals(3, backend.frames.last().contacts.size)
        dispatcher.key(KeyEvent.KEYCODE_M, true)
        dispatcher.key(KeyEvent.KEYCODE_M, false)
        dispatcher.awaitIdle()
        assertFalse(dispatcher.shootingModeFlow.value)
        assertEquals(2, backend.frames.last().contacts.size - 1) // camera UP frame includes departing finger
        val macroDown = backend.frames.first { it.contacts.size == 4 && it.action == TouchAction.POINTER_DOWN }
        assertEquals(.072056 * 1919, macroDown.contacts.last().position.x, 1e-9)
        dispatcher.mouseButton(MotionEvent.BUTTON_PRIMARY, false, TouchPoint(20.0, 30.0))
        dispatcher.key(KeyEvent.KEYCODE_W, false)
        dispatcher.key(KeyEvent.KEYCODE_D, false)
        dispatcher.awaitIdle()
        assertEquals(TouchAction.UP, backend.frames.last().action)
        dispatcher.close(); injector.pipeline.shutdown()
    }

    @Test fun `quick Alt toggles reset and new session do not replay old camera samples`() = runBlocking {
        val backend = RecordingTouchBackend()
        val injector = injector(backend)
        val dispatcher = MumuActionDispatcher(MumuProfileCodec.decode(original()), injector, TouchViewport(2400.0, 1600.0))
        repeat(50) {
            dispatcher.key(KeyEvent.KEYCODE_ALT_LEFT, true); dispatcher.key(KeyEvent.KEYCODE_ALT_LEFT, false)
            if (dispatcher.shootingModeFlow.value) dispatcher.move(TouchPoint(20.0, -7.0))
        }
        dispatcher.reset()
        dispatcher.move(TouchPoint(40.0, 40.0))
        dispatcher.awaitIdle()
        assertEquals(0, injector.pipeline.diagnostics().activeContacts)
        dispatcher.key(KeyEvent.KEYCODE_ALT_LEFT, true); dispatcher.key(KeyEvent.KEYCODE_ALT_LEFT, false)
        dispatcher.move(TouchPoint(1.0, 2.0))
        dispatcher.awaitIdle()
        val anchor = TouchViewport(2400.0, 1600.0).resolve(MumuProfileCodec.decode(original()).camera!!.anchor)
        assertEquals(anchor + TouchPoint(1.0, 2.0), backend.frames.last().contacts.single().position)
        dispatcher.close(); injector.pipeline.shutdown()
    }

    @Test fun `legacy editor mapping enters camera without cancelling held keyboard contact`() = runBlocking {
        val backend = RecordingTouchBackend()
        val injector = injector(backend)
        val handler = EventHandler(injector)
        handler.setPointerBounds(Offset(999f, 699f))
        handler.updateKeyMapping(listOf(
            DraggableItem.VariableKey(1, Offset(100f, 100f), KeyEvent.KEYCODE_F, 30),
            DraggableItem.FixedKey(2, Offset(500f, 300f), DraggableItemType.SHOOTING_MODE, KEYCODE_MMC, 30)))
        injector.pipeline.awaitIdle()
        val priorCancels = backend.frames.count { it.action == TouchAction.CANCEL }
        handler.handleKeyEvent(KeyEvent.KEYCODE_F, KeyEvent.ACTION_DOWN)
        handler.handleMouseButton(MotionEvent.BUTTON_TERTIARY, true)
        handler.handlePointerMove(Offset(7f, -3f))
        handler.releaseIdleShootingPointer()
        injector.pipeline.awaitIdle()
        assertEquals(2, injector.pipeline.diagnostics().activeContacts)
        assertEquals(priorCancels, backend.frames.count { it.action == TouchAction.CANCEL })
        handler.clear(); injector.pipeline.awaitIdle()
        assertEquals(0, injector.pipeline.diagnostics().activeContacts)
        injector.pipeline.shutdown()
    }

    @Test fun `swipe scheduler allows another key press before swipe finishes and replacement releases old swipe`() = runBlocking {
        val backend = RecordingTouchBackend()
        val injector = injector(backend)
        injector.injectSwipe(-999, Offset(500f, 500f), Offset(500f, 200f))
        injector.injectPointer(300, Offset(200f, 100f))
        injector.pipeline.awaitIdle()
        assertEquals(2, injector.pipeline.diagnostics().activeContacts)
        injector.injectSwipe(-999, Offset(500f, 500f), Offset(500f, 800f))
        injector.awaitScheduledGestures()
        assertEquals(1, injector.pipeline.diagnostics().activeContacts)
        assertEquals(0L, injector.pipeline.diagnostics().rejected)
        injector.pipeline.shutdown()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `gamepad JSON rejected`() { MumuProfileCodec.decode(original().replace("\"is_joystick\": false", "\"is_joystick\": true")) }

    @Test fun `two Shift sources share a hold until both physical keys are released`() = runBlocking {
        val backend = RecordingTouchBackend()
        val injector = injector(backend)
        val dispatcher = MumuActionDispatcher(MumuProfileCodec.decode(original()), injector, TouchViewport(1920.0, 1080.0))
        dispatcher.key(KeyEvent.KEYCODE_SHIFT_LEFT, true)
        dispatcher.key(KeyEvent.KEYCODE_SHIFT_RIGHT, true)
        dispatcher.key(KeyEvent.KEYCODE_SHIFT_LEFT, false)
        dispatcher.awaitIdle()
        assertEquals(1, injector.pipeline.diagnostics().activeContacts)
        dispatcher.key(KeyEvent.KEYCODE_SHIFT_RIGHT, false)
        dispatcher.awaitIdle()
        assertEquals(0, injector.pipeline.diagnostics().activeContacts)
        assertEquals(listOf(TouchAction.DOWN, TouchAction.UP), backend.frames.map { it.action })
        dispatcher.close(); injector.pipeline.shutdown()
    }

    @Test fun `ordinary editor joystick stays pressed at centre for opposite directions`() = runBlocking {
        val backend = RecordingTouchBackend()
        val injector = injector(backend)
        val handler = EventHandler(injector)
        handler.updateKeyMapping(listOf(DraggableItem.WASDGroup(1, Offset.Zero,
            center = Offset(100f, 100f), w = Offset(100f, 50f), a = Offset(50f, 100f),
            s = Offset(100f, 150f), d = Offset(150f, 100f))))
        handler.handleKeyEvent(KeyEvent.KEYCODE_W, KeyEvent.ACTION_DOWN)
        handler.handleKeyEvent(KeyEvent.KEYCODE_S, KeyEvent.ACTION_DOWN)
        injector.pipeline.awaitIdle()
        assertEquals(1, injector.pipeline.diagnostics().activeContacts)
        assertEquals(TouchPoint(100.0, 100.0), backend.frames.last().contacts.single().position)
        handler.handleKeyEvent(KeyEvent.KEYCODE_W, KeyEvent.ACTION_UP)
        injector.pipeline.awaitIdle()
        assertEquals(1, injector.pipeline.diagnostics().activeContacts)
        assertEquals(TouchPoint(100.0, 150.0), backend.frames.last().contacts.single().position)
        handler.handleKeyEvent(KeyEvent.KEYCODE_S, KeyEvent.ACTION_UP)
        injector.pipeline.awaitIdle()
        assertEquals(0, injector.pipeline.diagnostics().activeContacts)
        injector.pipeline.shutdown()
    }
}
