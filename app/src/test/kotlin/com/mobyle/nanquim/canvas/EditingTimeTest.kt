package com.mobyle.nanquim.canvas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EditingTimeTest {
    @Test
    fun gapsCountUpToTheIdleLimit() {
        val time = EditingTime(idleLimitMillis = 60_000)
        assertEquals(0, time.edit(1_000))
        assertEquals(1, time.edit(2_500))
        assertEquals(2, time.edit(4_000))
        assertEquals(60, time.edit(604_000))
        time.reset()
        assertEquals(0, time.edit(700_000))
        assertEquals(0, time.edit(650_000))
    }
}
