package com.mobyle.nanquim.gallery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TimelapseExportTest {
    @Test
    fun aSpeedUpKeepsExactlyTheWantedFramesSpreadEvenly() {
        for (total in listOf(811, 1000, 2500, 12345)) {
            val kept = (0 until total).filter { keptWhenSpedUp(it, total, 810) }
            assertEquals(810, kept.size, "total $total")
            assertEquals(0, kept.first())
            val gaps = kept.zipWithNext { a, b -> b - a }
            assertTrue(gaps.max() - gaps.min() <= 1, "total $total gaps ${gaps.min()}..${gaps.max()}")
        }
    }
}
