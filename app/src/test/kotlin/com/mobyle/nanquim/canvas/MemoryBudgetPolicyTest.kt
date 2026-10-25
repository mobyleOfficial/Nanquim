package com.mobyle.nanquim.canvas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MemoryBudgetPolicyTest {
    private val megabyte = 1024L * 1024
    private val threshold = 200 * megabyte

    @Test
    fun `starts at the maximum`() {
        assertEquals(1000 * megabyte, MemoryBudgetPolicy(1000 * megabyte, 256 * megabyte).currentBytes)
    }

    @Test
    fun `shrinks under pressure down to the minimum`() {
        val policy = MemoryBudgetPolicy(1000 * megabyte, 256 * megabyte)

        assertEquals(
            800 * megabyte,
            policy.onMemorySample(availableBytes = 300 * megabyte, lowMemoryThresholdBytes = threshold, lowMemory = false),
        )
        repeat(20) { policy.onMemorySample(availableBytes = 100 * megabyte, lowMemoryThresholdBytes = threshold, lowMemory = true) }

        assertEquals(256 * megabyte, policy.currentBytes)
    }

    @Test
    fun `grows back gradually when memory is plentiful, never above the maximum`() {
        val policy = MemoryBudgetPolicy(1000 * megabyte, 256 * megabyte)
        policy.onBackground()

        assertEquals(
            306 * megabyte,
            policy.onMemorySample(availableBytes = 2000 * megabyte, lowMemoryThresholdBytes = threshold, lowMemory = false),
        )
        repeat(40) { policy.onMemorySample(availableBytes = 2000 * megabyte, lowMemoryThresholdBytes = threshold, lowMemory = false) }

        assertEquals(1000 * megabyte, policy.currentBytes)
    }

    @Test
    fun `holds steady between pressure and plenty`() {
        val policy = MemoryBudgetPolicy(1000 * megabyte, 256 * megabyte)

        assertNull(policy.onMemorySample(availableBytes = 600 * megabyte, lowMemoryThresholdBytes = threshold, lowMemory = false))
    }

    @Test
    fun `background drops to the minimum once`() {
        val policy = MemoryBudgetPolicy(1000 * megabyte, 256 * megabyte)

        assertEquals(256 * megabyte, policy.onBackground())
        assertNull(policy.onBackground())
    }

    @Test
    fun `developer maximum is respected and never below the minimum`() {
        val policy = MemoryBudgetPolicy(1000 * megabyte, 256 * megabyte)

        assertEquals(1536 * megabyte, policy.setMaximum(1536 * megabyte))
        assertEquals(256 * megabyte, policy.setMaximum(16 * megabyte))
    }
}
