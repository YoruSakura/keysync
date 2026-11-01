package com.devoid.keysync.service

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareCursorControllerTest {

    @Test
    fun `move burst renders only the latest position once per frame`() {
        val frames = mutableListOf<() -> Unit>()
        val renderedPositions = mutableListOf<Offset>()
        val controller = SoftwareCursorController(
            initialPosition = Offset(50f, 50f),
            maximumPosition = Offset(1000f, 1000f),
            scheduleFrame = { frames += it },
            renderPosition = { renderedPositions += it }
        )

        repeat(500) {
            controller.moveBy(Offset(1f, 0f))
        }

        assertEquals(1, frames.size)
        assertTrue(renderedPositions.isEmpty())
        assertEquals(Offset(550f, 50f), controller.position)

        frames.removeAt(0).invoke()
        assertEquals(listOf(Offset(550f, 50f)), renderedPositions)

        controller.moveBy(Offset(2f, 3f))
        assertEquals(1, frames.size)
        frames.removeAt(0).invoke()
        assertEquals(Offset(552f, 53f), renderedPositions.last())
    }

    @Test
    fun `position is clamped and a cancelled frame cannot render stale state`() {
        val frames = mutableListOf<() -> Unit>()
        val renderedPositions = mutableListOf<Offset>()
        val controller = SoftwareCursorController(
            initialPosition = Offset(50f, 50f),
            maximumPosition = Offset(100f, 80f),
            scheduleFrame = { frames += it },
            renderPosition = { renderedPositions += it }
        )

        assertEquals(Offset(50f, -50f), controller.moveBy(Offset(1000f, -1000f)))
        assertEquals(Offset(100f, 0f), controller.position)
        assertEquals(Offset.Zero, controller.moveBy(Offset(1f, -1f)))

        controller.cancel()
        frames.single().invoke()
        assertTrue(renderedPositions.isEmpty())
    }

    @Test
    fun `display resize clamps both the logical click position and rendered cursor`() {
        val frames = mutableListOf<() -> Unit>()
        val renderedPositions = mutableListOf<Offset>()
        val controller = SoftwareCursorController(
            initialPosition = Offset(900f, 700f),
            maximumPosition = Offset(1000f, 800f),
            scheduleFrame = { frames += it },
            renderPosition = { renderedPositions += it }
        )

        assertEquals(
            Offset(599f, 499f),
            controller.updateMaximumPosition(Offset(599f, 499f))
        )
        assertEquals(Offset(599f, 499f), controller.position)
        assertEquals(1, frames.size)

        frames.single().invoke()
        assertEquals(listOf(Offset(599f, 499f)), renderedPositions)
    }
}
