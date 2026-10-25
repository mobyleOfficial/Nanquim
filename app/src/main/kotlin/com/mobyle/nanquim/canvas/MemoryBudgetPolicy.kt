package com.mobyle.nanquim.canvas

import kotlin.math.max
import kotlin.math.min

class MemoryBudgetPolicy(
    maximumBytes: Long,
    val minimumBytes: Long,
) {
    var maximumBytes: Long = max(maximumBytes, minimumBytes)
        private set

    var currentBytes: Long = this.maximumBytes
        private set

    fun setMaximum(bytes: Long): Long {
        maximumBytes = max(bytes, minimumBytes)
        currentBytes = maximumBytes
        return currentBytes
    }

    fun onMemorySample(
        availableBytes: Long,
        lowMemoryThresholdBytes: Long,
        lowMemory: Boolean,
    ): Long? {
        val target =
            when {
                lowMemory || availableBytes < lowMemoryThresholdBytes * PRESSURE_FACTOR ->
                    max(minimumBytes, (currentBytes * SHRINK_RATIO).toLong())
                availableBytes > lowMemoryThresholdBytes * RELAXED_FACTOR ->
                    min(maximumBytes, currentBytes + (maximumBytes * GROWTH_FRACTION).toLong())
                else -> currentBytes
            }
        return updateTo(target)
    }

    fun onBackground(): Long? = updateTo(minimumBytes)

    private fun updateTo(target: Long): Long? {
        if (target == currentBytes) return null
        currentBytes = target
        return target
    }

    companion object {
        const val DEFAULT_FRACTION_OF_TOTAL_RAM = 0.35
        const val DEFAULT_MINIMUM_BYTES = 256L * 1024 * 1024

        const val PRESSURE_FACTOR = 2

        const val RELAXED_FACTOR = 4
        const val SHRINK_RATIO = 0.8
        const val GROWTH_FRACTION = 0.05
    }
}
