package com.mobyle.nanquim.widget

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WidgetUpdatesTest {
    private val interval = WidgetUpdates.MINIMUM_INTERVAL_MS

    @Test
    fun updatesStayThirtySecondsApart() {
        assertEquals(0L, WidgetUpdates.delayBefore(lastUpdate = 0L, now = 1_000_000L))
        assertEquals(interval - 5_000L, WidgetUpdates.delayBefore(lastUpdate = 1_000_000L, now = 1_005_000L))
        assertEquals(0L, WidgetUpdates.delayBefore(lastUpdate = 1_000_000L, now = 1_000_000L + interval))
    }

    @Test
    fun aClockSetBackWaitsNoMoreThanTheInterval() {
        assertEquals(interval, WidgetUpdates.delayBefore(lastUpdate = 9_000_000L, now = 1_000_000L))
    }
}
