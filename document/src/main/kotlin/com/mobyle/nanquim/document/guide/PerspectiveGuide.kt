package com.mobyle.nanquim.document.guide

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

data class VanishingPoint(
    val x: Double,
    val y: Double,
)

data class PerspectiveGuide(
    val points: List<VanishingPoint> = emptyList(),
) {
    init {
        require(points.size <= MAXIMUM_POINTS) { "At most $MAXIMUM_POINTS vanishing points" }
    }

    fun adding(point: VanishingPoint): PerspectiveGuide? = if (points.size < MAXIMUM_POINTS) PerspectiveGuide(points + point) else null

    fun moving(
        index: Int,
        point: VanishingPoint,
    ): PerspectiveGuide? = if (index in points.indices) PerspectiveGuide(points.toMutableList().also { it[index] = point }) else null

    fun removing(index: Int): PerspectiveGuide? =
        if (index in points.indices) PerspectiveGuide(points.filterIndexed { i, _ -> i != index }) else null

    companion object {
        const val MAXIMUM_POINTS = 3
    }
}

object PerspectiveGeometry {
    fun horizonDirection(perspective: PerspectiveGuide): Double {
        val points = perspective.points
        if (points.size < 2) return 0.0
        val (a, b) = points
        if (hypot(b.x - a.x, b.y - a.y) < MINIMUM_SEPARATION) return 0.0
        return atan2(b.y - a.y, b.x - a.x)
    }

    fun lines(
        perspective: PerspectiveGuide,
        area: GuideRect,
        parallelSpacing: Double,
        rays: Int = RAYS,
        maximumLines: Int = GridGeometry.MAXIMUM_LINES,
    ): List<GuideSegment> {
        val points = perspective.points
        if (points.isEmpty()) return emptyList()
        val horizon = horizonDirection(perspective)
        val first = points.first()
        val segments = mutableListOf<GuideSegment>()
        GuideLines.line(first.x, first.y, horizon, area, emphasized = true)?.let(segments::add)
        for (point in points) {
            for (k in 0 until rays) {
                GuideLines.line(point.x, point.y, horizon + PI * k / rays, area)?.let(segments::add)
            }
        }
        if (parallelSpacing > 0.0) {
            val families =
                when (points.size) {
                    1 -> listOf(horizon, horizon + PI / 2)
                    2 -> listOf(horizon + PI / 2)
                    else -> emptyList()
                }
            for (direction in families) {
                segments += GuideLines.family(direction, first.x, first.y, parallelSpacing, area, maximumLines)
            }
        }
        return segments
    }

    fun pointAt(
        perspective: PerspectiveGuide,
        x: Double,
        y: Double,
        radius: Double,
    ): Int? =
        perspective.points
            .withIndex()
            .map { (index, point) -> index to hypot(point.x - x, point.y - y) }
            .filter { it.second <= radius }
            .minByOrNull { it.second }
            ?.first

    const val RAYS = 24
    private const val MINIMUM_SEPARATION = 1e-6
}
