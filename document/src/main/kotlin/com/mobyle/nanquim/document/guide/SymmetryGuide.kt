package com.mobyle.nanquim.document.guide

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class SymmetryKind(
    val id: String,
) {
    Vertical("vertical"),
    Horizontal("horizontal"),
    Quadrant("quadrant"),
    Radial("radial"),
    ;

    companion object {
        fun fromId(id: String): SymmetryKind? = entries.firstOrNull { it.id == id }
    }
}

data class SymmetryGuide(
    val kind: SymmetryKind = SymmetryKind.Vertical,
    val segments: Int = DEFAULT_SEGMENTS,
    val rotational: Boolean = false,
    val centerX: Double = 0.0,
    val centerY: Double = 0.0,
    val rotation: Double = 0.0,
) {
    companion object {
        const val DEFAULT_SEGMENTS = 8
        const val MINIMUM_SEGMENTS = 2
        const val MAXIMUM_SEGMENTS = 32
    }
}

data class GuideTransform(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double,
) {
    val mirrored: Boolean get() = a * e - b * d < 0

    fun apply(
        x: Double,
        y: Double,
    ): Pair<Double, Double> = a * x + b * y + c to d * x + e * y + f

    fun after(other: GuideTransform) =
        GuideTransform(
            a * other.a + b * other.d,
            a * other.b + b * other.e,
            a * other.c + b * other.f + c,
            d * other.a + e * other.d,
            d * other.b + e * other.e,
            d * other.c + e * other.f + f,
        )

    companion object {
        val IDENTITY = GuideTransform(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)

        fun rotation(
            angle: Double,
            cx: Double,
            cy: Double,
        ): GuideTransform {
            val cos = cos(angle)
            val sin = sin(angle)
            return GuideTransform(cos, -sin, cx - cos * cx + sin * cy, sin, cos, cy - sin * cx - cos * cy)
        }

        fun reflection(
            angle: Double,
            cx: Double,
            cy: Double,
        ): GuideTransform {
            val cos = cos(2 * angle)
            val sin = sin(2 * angle)
            return GuideTransform(cos, sin, cx - cos * cx - sin * cy, sin, -cos, cy - sin * cx + cos * cy)
        }
    }
}

object SymmetryGeometry {
    fun effectiveSegments(symmetry: SymmetryGuide): Int {
        val segments = symmetry.segments.coerceIn(SymmetryGuide.MINIMUM_SEGMENTS, SymmetryGuide.MAXIMUM_SEGMENTS)
        return if (symmetry.rotational || segments % 2 == 0) segments else segments + 1
    }

    private fun up(symmetry: SymmetryGuide) = symmetry.rotation - PI / 2

    fun transforms(symmetry: SymmetryGuide): List<GuideTransform> {
        val cx = symmetry.centerX
        val cy = symmetry.centerY
        val up = up(symmetry)

        fun turns(count: Int) =
            (0 until count).map { k -> if (k == 0) GuideTransform.IDENTITY else GuideTransform.rotation(2 * PI * k / count, cx, cy) }
        return when (symmetry.kind) {
            SymmetryKind.Vertical, SymmetryKind.Horizontal ->
                if (symmetry.rotational) {
                    turns(2)
                } else {
                    val axis = if (symmetry.kind == SymmetryKind.Vertical) up else symmetry.rotation
                    listOf(GuideTransform.IDENTITY, GuideTransform.reflection(axis, cx, cy))
                }
            SymmetryKind.Quadrant ->
                if (symmetry.rotational) {
                    turns(4)
                } else {
                    listOf(
                        GuideTransform.IDENTITY,
                        GuideTransform.reflection(up, cx, cy),
                        GuideTransform.reflection(symmetry.rotation, cx, cy),
                        GuideTransform.rotation(PI, cx, cy),
                    )
                }
            SymmetryKind.Radial -> {
                val count = effectiveSegments(symmetry)
                if (symmetry.rotational) {
                    turns(count)
                } else {
                    val wedge = 2 * PI / count
                    (0 until count).map { k ->
                        when {
                            k == 0 -> GuideTransform.IDENTITY
                            k % 2 == 0 -> GuideTransform.rotation(k * wedge, cx, cy)
                            else -> GuideTransform.reflection(up + (k + 1) * wedge / 2, cx, cy)
                        }
                    }
                }
            }
        }
    }

    fun lines(
        symmetry: SymmetryGuide,
        area: GuideRect,
    ): List<GuideSegment> {
        val cx = symmetry.centerX
        val cy = symmetry.centerY
        val up = up(symmetry)
        return when (symmetry.kind) {
            SymmetryKind.Vertical -> listOfNotNull(GuideLines.line(cx, cy, up, area))
            SymmetryKind.Horizontal -> listOfNotNull(GuideLines.line(cx, cy, symmetry.rotation, area))
            SymmetryKind.Quadrant -> listOfNotNull(GuideLines.line(cx, cy, up, area), GuideLines.line(cx, cy, symmetry.rotation, area))
            SymmetryKind.Radial -> {
                val count = effectiveSegments(symmetry)
                (0 until count).mapNotNull { k -> GuideLines.ray(cx, cy, up + 2 * PI * k / count, area) }
            }
        }
    }

    fun rotateHandle(
        symmetry: SymmetryGuide,
        distance: Double,
    ): Pair<Double, Double> {
        val up = up(symmetry)
        return symmetry.centerX + cos(up) * distance to symmetry.centerY + sin(up) * distance
    }

    fun rotationToward(
        symmetry: SymmetryGuide,
        x: Double,
        y: Double,
    ): Double = GridGeometry.normalize(atan2(y - symmetry.centerY, x - symmetry.centerX) + PI / 2)
}
