package com.devoid.keysync.data.external

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MouseToRightStickMapperTest {
    private val mapper = MouseToRightStickMapper()

    @Test
    fun `mapping is based on velocity rather than report size`() {
        val eightMillisecondSample = mapper.map(8, 0, 8_000_000)
        val fourMillisecondSample = mapper.map(4, 0, 4_000_000)

        assertEquals(eightMillisecondSample, fourMillisecondSample)
    }

    @Test
    fun `faster movement produces greater stick deflection`() {
        val slow = mapper.map(1, 0, 8_000_000)
        val fast = mapper.map(10, 0, 8_000_000)

        assertTrue(slow.x > 0)
        assertTrue(fast.x > slow.x)
        assertEquals(0, slow.y)
    }

    @Test
    fun `two dimensional normalization keeps diagonal inside stick circle`() {
        val diagonal = mapper.map(100, 100, 1_000_000)

        assertTrue(
            hypot(diagonal.x.toDouble(), diagonal.y.toDouble()) <=
                MouseToRightStickMapper.AXIS_MAXIMUM + 1.0
        )
        assertEquals(diagonal.x, diagonal.y)
    }

    @Test
    fun `zero delta remains centered`() {
        assertEquals(StickPosition.CENTER, mapper.map(0, 0, 8_000_000))
    }
}
