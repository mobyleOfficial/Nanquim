package com.mobyle.nanquim.document.brush

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.hypot
import kotlin.math.sqrt

class BrushCurve(
    points: List<Pair<Double, Double>>,
) {
    val points: List<Pair<Double, Double>> = if (isValid(points)) points else LINEAR_POINTS
    private val tangents: DoubleArray = tangentsOf(this.points)

    fun evaluate(x: Double): Double {
        val clamped = x.coerceIn(0.0, 1.0)
        var segment = 0
        while (segment < points.size - 2 && clamped > points[segment + 1].first) segment++
        val (x0, y0) = points[segment]
        val (x1, y1) = points[segment + 1]
        val width = x1 - x0
        val t = (clamped - x0) / width
        val t2 = t * t
        val t3 = t2 * t
        val value =
            (2 * t3 - 3 * t2 + 1) * y0 +
                (t3 - 2 * t2 + t) * width * tangents[segment] +
                (-2 * t3 + 3 * t2) * y1 +
                (t3 - t2) * width * tangents[segment + 1]
        return value.coerceIn(0.0, 1.0)
    }

    fun toJson(): JsonArray = JsonArray(points.map { (x, y) -> JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y))) })

    override fun equals(other: Any?): Boolean = other is BrushCurve && other.points == points

    override fun hashCode(): Int = points.hashCode()

    companion object {
        const val REFERENCE_PREFIX = "curve:"
        private val LINEAR_POINTS = listOf(0.0 to 0.0, 1.0 to 1.0)

        val LINEAR = BrushCurve(LINEAR_POINTS)
        val SOFT = BrushCurve(listOf(0.0 to 0.0, 0.25 to 0.45, 1.0 to 1.0))
        val HARD = BrushCurve(listOf(0.0 to 0.0, 0.6 to 0.3, 1.0 to 1.0))

        private val BUILT_IN = mapOf("linear" to LINEAR, "soft" to SOFT, "hard" to HARD)

        fun builtIn(name: String): BrushCurve? = BUILT_IN[name]

        fun fromJson(element: JsonElement?): BrushCurve? {
            val array = element as? JsonArray ?: return null
            val points =
                array.map { point ->
                    val pair = point as? JsonArray ?: return null
                    if (pair.size != 2) return null
                    val x = (pair[0] as? JsonPrimitive)?.doubleOrNull ?: return null
                    val y = (pair[1] as? JsonPrimitive)?.doubleOrNull ?: return null
                    x to y
                }
            return if (isValid(points)) BrushCurve(points) else null
        }

        fun resolve(
            document: BrushDocument,
            reference: String?,
        ): BrushCurve? {
            val name = reference?.removePrefix(REFERENCE_PREFIX)?.takeIf { reference.startsWith(REFERENCE_PREFIX) } ?: return null
            return fromJson((document["curves"] as? JsonObject)?.get(name)) ?: builtIn(name)
        }

        fun isValid(points: List<Pair<Double, Double>>): Boolean {
            if (points.size < 2 || points.first().first != 0.0 || points.last().first != 1.0) return false
            return points.withIndex().all { (index, point) ->
                point.second in 0.0..1.0 && (index == 0 || point.first > points[index - 1].first)
            }
        }

        fun nearestPoint(
            points: List<Pair<Double, Double>>,
            x: Double,
            y: Double,
            radius: Double,
        ): Int {
            var best = -1
            var bestDistance = radius
            points.forEachIndexed { index, (px, py) ->
                val distance = hypot(px - x, py - y)
                if (distance <= bestDistance) {
                    best = index
                    bestDistance = distance
                }
            }
            return best
        }

        private fun tangentsOf(points: List<Pair<Double, Double>>): DoubleArray {
            val count = points.size
            val secants = DoubleArray(count - 1) { (points[it + 1].second - points[it].second) / (points[it + 1].first - points[it].first) }
            val tangents = DoubleArray(count)
            tangents[0] = secants.first()
            tangents[count - 1] = secants.last()
            for (index in 1 until count - 1) {
                tangents[index] = if (secants[index - 1] * secants[index] <= 0) 0.0 else (secants[index - 1] + secants[index]) / 2
            }
            for (index in 0 until count - 1) {
                if (secants[index] == 0.0) {
                    tangents[index] = 0.0
                    tangents[index + 1] = 0.0
                    continue
                }
                val alpha = tangents[index] / secants[index]
                val beta = tangents[index + 1] / secants[index]
                val sum = alpha * alpha + beta * beta
                if (sum > 9) {
                    val tau = 3 / sqrt(sum)
                    tangents[index] = tau * alpha * secants[index]
                    tangents[index + 1] = tau * beta * secants[index]
                }
            }
            return tangents
        }
    }
}
