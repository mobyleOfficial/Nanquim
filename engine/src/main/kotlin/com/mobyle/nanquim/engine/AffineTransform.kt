package com.mobyle.nanquim.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Point(
    val x: Double,
    val y: Double,
)

data class AffineTransform(
    val a: Double = 1.0,
    val b: Double = 0.0,
    val c: Double = 0.0,
    val d: Double = 1.0,
    val tx: Double = 0.0,
    val ty: Double = 0.0,
) {
    fun apply(point: Point): Point = Point(a * point.x + c * point.y + tx, b * point.x + d * point.y + ty)

    fun then(next: AffineTransform): AffineTransform =
        AffineTransform(
            a = next.a * a + next.c * b,
            b = next.b * a + next.d * b,
            c = next.a * c + next.c * d,
            d = next.b * c + next.d * d,
            tx = next.a * tx + next.c * ty + next.tx,
            ty = next.b * tx + next.d * ty + next.ty,
        )

    val determinant: Double get() = a * d - b * c

    val scaleFactor: Double get() = sqrt(abs(determinant))

    fun inverted(): AffineTransform? {
        val det = determinant
        if (abs(det) < 1e-12) return null
        val inverseA = d / det
        val inverseB = -b / det
        val inverseC = -c / det
        val inverseD = a / det
        return AffineTransform(
            a = inverseA,
            b = inverseB,
            c = inverseC,
            d = inverseD,
            tx = -(inverseA * tx + inverseC * ty),
            ty = -(inverseB * tx + inverseD * ty),
        )
    }

    companion object {
        val IDENTITY = AffineTransform()

        fun translation(
            offsetX: Double,
            offsetY: Double,
        ) = AffineTransform(tx = offsetX, ty = offsetY)

        fun scale(
            factorX: Double,
            factorY: Double = factorX,
        ) = AffineTransform(a = factorX, d = factorY)

        fun rotation(radians: Double): AffineTransform {
            val cosine = cos(radians)
            val sine = sin(radians)
            return AffineTransform(a = cosine, b = sine, c = -sine, d = cosine)
        }
    }
}
