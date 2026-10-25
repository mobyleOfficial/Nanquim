package com.mobyle.nanquim.canvas.adjust

import kotlin.math.abs

data class CurvePoint(
    val x: Float,
    val y: Float,
)

enum class CurveChannel {
    Master,
    Red,
    Green,
    Blue,
    Alpha,
}

object CurveEditing {
    const val MAXIMUM_POINTS = 16

    const val MINIMUM_GAP = 0.01f

    val STRAIGHT = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))

    fun insert(
        points: List<CurvePoint>,
        x: Float,
        y: Float,
    ): Pair<List<CurvePoint>, Int>? {
        if (points.size >= MAXIMUM_POINTS || points.any { abs(it.x - x) < MINIMUM_GAP * 2 }) return null
        val point = CurvePoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        val index = points.indexOfFirst { it.x > point.x }.let { if (it < 0) points.size else it }
        return points.toMutableList().apply { add(index, point) } to index
    }

    fun move(
        points: List<CurvePoint>,
        index: Int,
        x: Float,
        y: Float,
    ): List<CurvePoint> {
        val low = if (index == 0) 0f else points[index - 1].x + MINIMUM_GAP
        val high = if (index == points.lastIndex) 1f else points[index + 1].x - MINIMUM_GAP
        return points.toMutableList().apply { set(index, CurvePoint(x.coerceIn(low, maxOf(low, high)), y.coerceIn(0f, 1f))) }
    }

    fun remove(
        points: List<CurvePoint>,
        index: Int,
    ): List<CurvePoint> = if (points.size <= 2) points else points.filterIndexed { position, _ -> position != index }

    fun pointNear(
        points: List<CurvePoint>,
        x: Float,
        y: Float,
        radius: Float,
    ): Int? =
        points.indices
            .map { it to (points[it].x - x).let { dx -> dx * dx } + (points[it].y - y).let { dy -> dy * dy } }
            .filter { it.second <= radius * radius }
            .minByOrNull { it.second }
            ?.first

    fun tableData(curves: List<List<CurvePoint>>): FloatArray =
        curves.flatMap { curve -> listOf(curve.size.toFloat()) + curve.flatMap { listOf(it.x, it.y) } }.toFloatArray()

    fun evaluate(
        points: List<CurvePoint>,
        x: Float,
    ): Float {
        if (points.size == 1 || x <= points.first().x) return points.first().y
        if (x >= points.last().x) return points.last().y
        val secants = points.zipWithNext { a, b -> (b.y - a.y) / (b.x - a.x) }
        val tangents = FloatArray(points.size)
        tangents[0] = secants.first()
        tangents[points.lastIndex] = secants.last()
        for (index in 1 until points.lastIndex) {
            tangents[index] = if (secants[index - 1] * secants[index] <= 0f) 0f else (secants[index - 1] + secants[index]) / 2
        }
        for (index in secants.indices) {
            if (secants[index] == 0f) {
                tangents[index] = 0f
                tangents[index + 1] = 0f
                continue
            }
            val a = tangents[index] / secants[index]
            val b = tangents[index + 1] / secants[index]
            val length = a * a + b * b
            if (length > 9f) {
                val scale = 3f / kotlin.math.sqrt(length)
                tangents[index] = scale * a * secants[index]
                tangents[index + 1] = scale * b * secants[index]
            }
        }
        var segment = 0
        while (x > points[segment + 1].x) segment++
        val width = points[segment + 1].x - points[segment].x
        val t = (x - points[segment].x) / width
        val t2 = t * t
        val t3 = t2 * t
        val value =
            (2 * t3 - 3 * t2 + 1) * points[segment].y + (t3 - 2 * t2 + t) * width * tangents[segment] +
                (-2 * t3 + 3 * t2) * points[segment + 1].y + (t3 - t2) * width * tangents[segment + 1]
        return value.coerceIn(0f, 1f)
    }
}

class Histogram(
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
    val luminance: IntArray,
    val alpha: IntArray,
) {
    fun of(channel: CurveChannel): IntArray =
        when (channel) {
            CurveChannel.Master -> luminance
            CurveChannel.Red -> red
            CurveChannel.Green -> green
            CurveChannel.Blue -> blue
            CurveChannel.Alpha -> alpha
        }

    companion object {
        private const val BINS = 256

        fun of(pixels: ByteArray): Histogram {
            val red = IntArray(BINS)
            val green = IntArray(BINS)
            val blue = IntArray(BINS)
            val luminance = IntArray(BINS)
            val alpha = IntArray(BINS)
            for (offset in 0 until pixels.size / 4 * 4 step 4) {
                val a = pixels[offset + 3].toInt() and 0xFF
                if (a == 0) continue
                val r = pixels[offset].toInt() and 0xFF
                val g = pixels[offset + 1].toInt() and 0xFF
                val b = pixels[offset + 2].toInt() and 0xFF
                red[r]++
                green[g]++
                blue[b]++
                alpha[a]++
                luminance[((0.3f * r + 0.59f * g + 0.11f * b) + 0.5f).toInt().coerceIn(0, BINS - 1)]++
            }
            return Histogram(red, green, blue, luminance, alpha)
        }
    }
}
