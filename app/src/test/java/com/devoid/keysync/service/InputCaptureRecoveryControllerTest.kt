package com.devoid.keysync.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputCaptureRecoveryControllerTest {

    @Test
    fun `capture loss resets input once and restores only after stable capture`() {
        var interruptions = 0
        var restorations = 0
        val controller = InputCaptureRecoveryController(
            stableChecksRequired = 2,
            onInterrupted = { interruptions++ },
            onRestored = { restorations++ }
        )

        controller.reportAvailable()
        assertTrue(controller.isInterrupted())
        controller.reportAvailable()
        assertFalse(controller.isInterrupted())
        assertEquals(1, restorations)

        controller.reportUnavailable()
        controller.reportUnavailable()
        assertTrue(controller.isInterrupted())
        assertEquals(1, interruptions)

        controller.reportAvailable()
        controller.reportUnavailable()
        controller.reportAvailable()
        assertTrue(controller.isInterrupted())
        assertEquals(1, restorations)

        controller.reportAvailable()
        assertFalse(controller.isInterrupted())
        assertEquals(2, restorations)
    }
}
