package com.mobyle.nanquim.canvas

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class HoverTipTest {
    @Test
    fun `the outline is the boundary of the covered texels`() {
        val coverage = IntArray(16)
        for (index in listOf(5, 6, 9, 10)) coverage[index] = 255
        coverage[0] = 100
        val expected = BooleanArray(16).also { for (index in listOf(5, 6, 9, 10)) it[index] = true }
        assertArrayEquals(expected, HoverTip.outlineMask(coverage, 4))

        val full = HoverTip.outlineMask(IntArray(9) { 255 }, 3)
        assertArrayEquals(booleanArrayOf(true, true, true, true, false, true, true, true, true), full)
    }
}
