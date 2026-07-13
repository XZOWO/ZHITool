package com.zhitool.rearlyric.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmResponseTest {
    @Test
    fun `ordinary music range is unchanged`() {
        listOf(0f, 0.08f, 0.25f, 0.55f).forEach { input ->
            assertEquals(input, softRhythmEnergy(input), 0.0001f)
        }
    }

    @Test
    fun `strong input keeps increasing without hard clipping`() {
        val one = softRhythmEnergy(1f)
        val two = softRhythmEnergy(2f)
        val five = softRhythmEnergy(5f)

        assertTrue(one in 0.65f..0.69f)
        assertTrue(two > one)
        assertTrue(five > two)
        assertTrue(five < 1f)
    }

    @Test
    fun `invalid or negative energy is safe`() {
        assertEquals(0f, softRhythmEnergy(-1f), 0f)
        assertEquals(0f, softRhythmEnergy(Float.NaN), 0f)
        assertEquals(1f, softRhythmEnergy(Float.POSITIVE_INFINITY), 0f)
    }
}
