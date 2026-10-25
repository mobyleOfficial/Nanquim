package com.mobyle.nanquim.document.guide

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class AssistLine(
    val x: Double,
    val y: Double,
    val direction: Double,
) {
    fun project(
        px: Double,
        py: Double,
    ): Pair<Double, Double> {
        val dx = cos(direction)
        val dy = sin(direction)
        val t = (px - x) * dx + (py - y) * dy
        return x + dx * t to y + dy * t
    }
}

object AssistGeometry {
    fun constrains(guides: GuideSettings): Boolean =
        guides.visible &&
            when (guides.type) {
                GuideType.Grid, GuideType.Isometric -> true
                GuideType.Perspective -> guides.perspective.points.isNotEmpty()
                GuideType.Symmetry -> false
            }

    fun symmetry(guides: GuideSettings): List<GuideTransform> =
        if (guides.visible && guides.type == GuideType.Symmetry) SymmetryGeometry.transforms(guides.symmetry) else emptyList()

    fun directions(
        guides: GuideSettings,
        x: Double,
        y: Double,
    ): List<Double> =
        when (guides.type) {
            GuideType.Grid, GuideType.Isometric -> GridGeometry.directions(guides.type, guides.grid.rotation)
            GuideType.Perspective -> {
                val points = guides.perspective.points
                val horizon = PerspectiveGeometry.horizonDirection(guides.perspective)
                val parallels =
                    when (points.size) {
                        1 -> listOf(horizon, horizon + PI / 2)
                        2 -> listOf(horizon + PI / 2)
                        else -> emptyList()
                    }
                points.filter { hypot(it.x - x, it.y - y) > MINIMUM_DISTANCE }.map { atan2(it.y - y, it.x - x) } + parallels
            }
            GuideType.Symmetry -> emptyList()
        }

    fun choose(
        guides: GuideSettings,
        startX: Double,
        startY: Double,
        x: Double,
        y: Double,
    ): AssistLine? {
        val movement = atan2(y - startY, x - startX)
        val direction = directions(guides, startX, startY).minByOrNull { lineAngle(it, movement) } ?: return null
        return AssistLine(startX, startY, direction)
    }

    fun lineAngle(
        a: Double,
        b: Double,
    ): Double {
        val difference = abs((a - b) % PI)
        return minOf(difference, PI - difference)
    }

    private const val MINIMUM_DISTANCE = 1e-6
}

class StrokeAssist(
    private val guides: GuideSettings,
    private val startX: Double,
    private val startY: Double,
    private val decideDistance: Double = DEFAULT_DECIDE_DISTANCE,
) {
    var line: AssistLine? = null
        private set

    fun place(
        x: Double,
        y: Double,
    ): Pair<Double, Double>? {
        line?.let { return it.project(x, y) }
        if (free) return x to y
        if (hypot(x - startX, y - startY) < decideDistance) return null
        val chosen = AssistGeometry.choose(guides, startX, startY, x, y)
        if (chosen == null) {
            free = true
            return x to y
        }
        line = chosen
        return chosen.project(x, y)
    }

    var free: Boolean = false
        private set

    fun release(
        x: Double,
        y: Double,
    ): Pair<Double, Double> = line?.project(x, y) ?: (x to y)

    companion object {
        const val DEFAULT_DECIDE_DISTANCE = 20.0

        fun lineFor(
            guides: GuideSettings,
            points: List<Pair<Double, Double>>,
            decideDistance: Double = DEFAULT_DECIDE_DISTANCE,
        ): AssistLine? {
            val (startX, startY) = points.firstOrNull() ?: return null
            val assist = StrokeAssist(guides, startX, startY, decideDistance)
            for ((x, y) in points) if (assist.place(x, y) != null) return assist.line
            return null
        }
    }
}
