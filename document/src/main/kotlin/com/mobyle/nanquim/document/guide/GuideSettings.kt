package com.mobyle.nanquim.document.guide

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class GuideType(
    val id: String,
) {
    Grid("grid2d"),
    Isometric("isometric"),
    Perspective("perspective"),
    Symmetry("symmetry"),
    ;

    companion object {
        fun fromId(id: String): GuideType? = entries.firstOrNull { it.id == id }
    }
}

data class GridGuide(
    val size: Double = DEFAULT_SIZE,
    val rotation: Double = 0.0,
    val originX: Double = 0.0,
    val originY: Double = 0.0,
) {
    companion object {
        const val DEFAULT_SIZE = 150.0
        const val MINIMUM_SIZE = 4.0
        const val MAXIMUM_SIZE = 4000.0
    }
}

data class GuideSettings(
    val type: GuideType = GuideType.Grid,
    val visible: Boolean = false,
    val hue: Float = DEFAULT_HUE,
    val opacity: Float = DEFAULT_OPACITY,
    val thickness: Float = DEFAULT_THICKNESS,
    val grid: GridGuide = GridGuide(),
    val perspective: PerspectiveGuide = PerspectiveGuide(),
    val symmetry: SymmetryGuide = SymmetryGuide(),
) {
    companion object {
        const val DEFAULT_HUE = 200f
        const val DEFAULT_OPACITY = 0.6f
        const val DEFAULT_THICKNESS = 1.5f
        const val MINIMUM_THICKNESS = 0.5f
        const val MAXIMUM_THICKNESS = 8f

        fun forCanvas(
            widthPx: Int,
            heightPx: Int,
        ) = GuideSettings(
            grid = GridGuide(originX = widthPx / 2.0, originY = heightPx / 2.0),
            symmetry = SymmetryGuide(centerX = widthPx / 2.0, centerY = heightPx / 2.0),
        )
    }
}

data class GuideSegment(
    val x0: Double,
    val y0: Double,
    val x1: Double,
    val y1: Double,
    val emphasized: Boolean = false,
)

data class GuideRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    fun intersect(other: GuideRect): GuideRect? {
        val rect = GuideRect(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom))
        return rect.takeIf { it.left < it.right && it.top < it.bottom }
    }
}

object GridGeometry {
    fun directions(
        type: GuideType,
        rotation: Double,
    ): List<Double> =
        when (type) {
            GuideType.Isometric -> listOf(PI / 6, PI / 2, 5 * PI / 6).map { it + rotation }
            else -> listOf(0.0, PI / 2).map { it + rotation }
        }

    fun spacing(
        type: GuideType,
        size: Double,
    ): Double = if (type == GuideType.Isometric) size * sqrt(3.0) / 2 else size

    fun lines(
        type: GuideType,
        grid: GridGuide,
        area: GuideRect,
        stride: Int = 1,
        maximumLines: Int = MAXIMUM_LINES,
    ): List<GuideSegment> {
        val step = spacing(type, grid.size) * stride.coerceAtLeast(1)
        if (step <= 0.0) return emptyList()
        return directions(type, grid.rotation).flatMap { direction ->
            GuideLines.family(direction, grid.originX, grid.originY, step, area, maximumLines)
        }
    }

    fun rotateHandle(
        type: GuideType,
        grid: GridGuide,
        distance: Double = grid.size * 2,
    ): Pair<Double, Double> {
        val direction = directions(type, grid.rotation).first()
        return grid.originX + cos(direction) * distance to grid.originY + sin(direction) * distance
    }

    fun rotationToward(
        type: GuideType,
        grid: GridGuide,
        x: Double,
        y: Double,
    ): Double {
        val angle = kotlin.math.atan2(y - grid.originY, x - grid.originX)
        val base = directions(type, 0.0).first()
        return normalize(angle - base)
    }

    fun normalize(angle: Double): Double {
        var value = angle % (2 * PI)
        if (value <= -PI) value += 2 * PI
        if (value > PI) value -= 2 * PI
        return value
    }

    const val MAXIMUM_LINES = 400
}
