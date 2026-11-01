package com.devoid.keysync.data.external

import org.junit.Assert.assertEquals
import org.junit.Test

class RootMouseScrollTest {
    @Test
    fun `root wheel units preserve direction and fractional detents`() {
        assertEquals(1f, RootMouseScroll.toNotches(120), 0f)
        assertEquals(-1f, RootMouseScroll.toNotches(-120), 0f)
        assertEquals(0.25f, RootMouseScroll.toNotches(30), 0f)
    }
}
