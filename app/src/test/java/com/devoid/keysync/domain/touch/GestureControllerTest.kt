package com.devoid.keysync.domain.touch

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class GestureControllerTest {
    private val id = ContactId(999)
    private val cfg = CameraConfig(TouchPoint(50.0, 50.0), TouchRect(0.0, 0.0, 100.0, 100.0))

    @Test fun `camera stationary keeps contact and subpixel movement is preserved`() {
        val commands = mutableListOf<TouchCommand>()
        val camera = CameraGestureController(id, commands::add)
        camera.enter(cfg)
        assertTrue(commands.isEmpty())
        repeat(100) { camera.move(TouchPoint(.01, -.01)) }
        assertEquals(1, commands.count { it is TouchCommand.Down })
        assertFalse(commands.any { it is TouchCommand.Up })
        assertEquals(51.0, camera.position!!.x, 1e-8)
        camera.exit()
        val count = commands.size
        camera.move(TouchPoint(10.0, 10.0))
        assertEquals(count, commands.size)
        assertTrue(commands.last() is TouchCommand.Up)
    }

    @Test fun `large delta crosses several bounds without teleport or lost overshoot`() {
        val commands = mutableListOf<TouchCommand>()
        val camera = CameraGestureController(id, commands::add)
        camera.enter(cfg)
        camera.move(TouchPoint(180.0, 0.0))
        assertEquals(3L, camera.resets)
        assertEquals(80.0, camera.position!!.x, 1e-8)
        assertEquals(50.0, camera.position!!.y, 1e-8)
        assertEquals(4, commands.count { it is TouchCommand.Move })
        camera.move(TouchPoint(-190.0, 0.0))
        assertEquals(40.0, camera.position!!.x, 1e-8)
        assertEquals(50.0, camera.position!!.y, 1e-8)
    }

    @Test fun `axis safe bounds and repeated resets never reach attack button region`() {
        val commands = mutableListOf<TouchCommand>()
        val camera = CameraGestureController(id, commands::add)
        val safe = CameraConfig(TouchPoint(80.0, 40.0), TouchRect(55.0, 15.0, 99.0, 65.0))
        camera.enter(safe)
        repeat(500) { camera.move(TouchPoint(23.0, 17.0)) }
        commands.filterIsInstance<TouchCommand.Move>().forEach { move ->
            assertTrue(move.points.getValue(id).y in 15.0..65.0)
            assertTrue(move.points.getValue(id).x in 55.0..99.0)
        }
        assertTrue(camera.resets > 100)
    }

    @Test fun `WASD diagonal normalized and partial release keeps same contact`() {
        val commands = mutableListOf<TouchCommand>()
        val joystick = JoystickGestureController(id, TouchPoint(50.0, 50.0), 10.0, 1, 2, 3, 4, commands::add)
        joystick.key(1, true); joystick.key(4, true)
        val point = (commands.last() as TouchCommand.Move).points.getValue(id)
        assertEquals(10.0, hypot(point.x - 50, point.y - 50), 1e-9)
        joystick.key(1, false)
        assertEquals(1, commands.count { it is TouchCommand.Down })
        assertEquals(TouchPoint(60.0, 50.0), (commands.last() as TouchCommand.Move).points.getValue(id))
        joystick.key(2, true)
        assertEquals(TouchPoint(50.0, 50.0), (commands.last() as TouchCommand.Move).points.getValue(id))
        assertFalse(commands.any { it is TouchCommand.Up })
        joystick.key(2, false); joystick.key(4, false)
        assertTrue(commands.last() is TouchCommand.Up)
    }

    @Test fun `viewport scales to actual device instead of hardcoded MuMu raw axes`() {
        assertEquals(TouchPoint(999.5, 349.5), TouchViewport(2000.0, 700.0).resolve(TouchPoint(.5, .5)))
    }
}
