package com.mobyle.nanquim.engine

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class TextGeometry(
    val width: Float = 0f,
    val height: Float = 0f,
    val vertical: Boolean = false,
    val length: Int = 0,
    val transform: List<Double> = listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
    val lines: List<List<Float>> = emptyList(),
    val clusters: List<List<Float>> = emptyList(),
    val carets: List<List<Float>> = emptyList(),
    val error: String? = null,
) {
    data class Box(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
    )

    fun caret(index: Int): List<Float> = carets.getOrNull(index.coerceIn(0, length)) ?: listOf(0f, 0f, 0f, 0f)

    fun selection(
        start: Int,
        end: Int,
    ): List<Box> {
        if (start >= end) return emptyList()
        val extents = mutableMapOf<Int, MutableList<Pair<Float, Float>>>()
        for (cluster in clusters) {
            val first = cluster[0].toInt()
            val last = cluster[1].toInt()
            if (last <= start || first >= end) continue
            extents.getOrPut(cluster[2].toInt()) { mutableListOf() } += minOf(cluster[3], cluster[4]) to maxOf(cluster[3], cluster[4])
        }
        val boxes = mutableListOf<Box>()
        for ((line, spans) in extents.toSortedMap()) {
            val box = lines.getOrNull(line) ?: continue
            val merged = mutableListOf<Pair<Float, Float>>()
            for (span in spans.sortedBy { it.first }) {
                val previous = merged.lastOrNull()
                if (previous != null && span.first <= previous.second + 0.5f) {
                    merged[merged.lastIndex] = previous.first to maxOf(previous.second, span.second)
                } else {
                    merged += span
                }
            }
            for ((from, to) in merged) {
                boxes += if (vertical) Box(box[0], from, box[2], to - from) else Box(from, box[1], to - from, box[3])
            }
        }
        return boxes
    }

    fun hitTest(
        x: Float,
        y: Float,
    ): Int {
        if (carets.isEmpty()) return 0
        val across = if (vertical) x else y
        val along = if (vertical) y else x
        var bestDistance = Float.MAX_VALUE
        var best = 0
        for ((index, caret) in carets.withIndex()) {
            val low = if (vertical) minOf(caret[0], caret[2]) else minOf(caret[1], caret[3])
            val high = if (vertical) maxOf(caret[0], caret[2]) else maxOf(caret[1], caret[3])
            val crossDistance =
                if (across < low) {
                    low - across
                } else if (across > high) {
                    across - high
                } else {
                    0f
                }
            val position = if (vertical) caret[1] else caret[0]
            val distance = crossDistance * LINE_DISTANCE_WEIGHT + abs(along - position)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }
}

private const val LINE_DISTANCE_WEIGHT = 1000f
