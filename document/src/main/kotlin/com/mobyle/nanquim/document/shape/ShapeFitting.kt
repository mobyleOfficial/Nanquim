package com.mobyle.nanquim.document.shape

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class ShapePoint(
    val x: Double,
    val y: Double,
) {
    operator fun plus(other: ShapePoint) = ShapePoint(x + other.x, y + other.y)

    operator fun minus(other: ShapePoint) = ShapePoint(x - other.x, y - other.y)

    operator fun times(scale: Double) = ShapePoint(x * scale, y * scale)

    fun dot(other: ShapePoint): Double = x * other.x + y * other.y

    fun length(): Double = hypot(x, y)

    fun distanceTo(other: ShapePoint): Double = hypot(x - other.x, y - other.y)

    fun normalized(): ShapePoint {
        val length = length()
        return if (length < EPSILON) ShapePoint(0.0, 0.0) else ShapePoint(x / length, y / length)
    }

    private companion object {
        const val EPSILON = 1e-12
    }
}

data class FittedLine(
    val point: ShapePoint,
    val direction: ShapePoint,
) {
    fun parameter(p: ShapePoint): Double = (p - point).dot(direction)

    fun at(parameter: Double): ShapePoint = point + direction * parameter

    fun distanceTo(p: ShapePoint): Double = abs((p.x - point.x) * direction.y - (p.y - point.y) * direction.x)

    fun intersect(other: FittedLine): ShapePoint? {
        val cross = direction.x * other.direction.y - direction.y * other.direction.x
        if (abs(cross) < PARALLEL) return null
        val offset = other.point - point
        val t = (offset.x * other.direction.y - offset.y * other.direction.x) / cross
        return at(t)
    }

    private companion object {
        const val PARALLEL = 1e-9
    }
}

data class FittedCircle(
    val center: ShapePoint,
    val radius: Double,
)

data class FittedEllipse(
    val center: ShapePoint,
    val radiusX: Double,
    val radiusY: Double,
    val rotation: Double,
)

data class CubicBezier(
    val start: ShapePoint,
    val control1: ShapePoint,
    val control2: ShapePoint,
    val end: ShapePoint,
) {
    fun at(t: Double): ShapePoint {
        val u = 1 - t
        return start * (u * u * u) + control1 * (3 * u * u * t) + control2 * (3 * u * t * t) + end * (t * t * t)
    }

    fun derivative(t: Double): ShapePoint {
        val u = 1 - t
        return (control1 - start) * (3 * u * u) + (control2 - control1) * (6 * u * t) + (end - control2) * (3 * t * t)
    }

    fun secondDerivative(t: Double): ShapePoint =
        (control2 - control1 * 2.0 + start) * (6 * (1 - t)) + (end - control2 * 2.0 + control1) * (6 * t)
}

object ShapeFitting {
    fun line(points: List<ShapePoint>): FittedLine {
        val mean = centroid(points)
        var xx = 0.0
        var xy = 0.0
        var yy = 0.0
        for (p in points) {
            val dx = p.x - mean.x
            val dy = p.y - mean.y
            xx += dx * dx
            xy += dx * dy
            yy += dy * dy
        }
        val angle = 0.5 * atan2(2 * xy, xx - yy)
        return FittedLine(mean, ShapePoint(cos(angle), sin(angle)))
    }

    fun circle(points: List<ShapePoint>): FittedCircle? {
        if (points.size < 3) return null
        val mean = centroid(points)
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        var sxz = 0.0
        var syz = 0.0
        var sz = 0.0
        for (p in points) {
            val x = p.x - mean.x
            val y = p.y - mean.y
            val z = x * x + y * y
            sxx += x * x
            sxy += x * y
            syy += y * y
            sxz += x * z
            syz += y * z
            sz += z
        }
        val n = points.size.toDouble()
        val solution =
            solve3(
                arrayOf(
                    doubleArrayOf(sxx, sxy, 0.0),
                    doubleArrayOf(sxy, syy, 0.0),
                    doubleArrayOf(0.0, 0.0, n),
                ),
                doubleArrayOf(-sxz, -syz, -sz),
            ) ?: return null
        val (d, e, f) = solution.toList()
        var center = ShapePoint(mean.x - d / 2, mean.y - e / 2)
        val squared = d * d / 4 + e * e / 4 - f
        if (squared <= 0 || !squared.isFinite()) return null
        var radius = sqrt(squared)
        repeat(GEOMETRIC_ITERATIONS) {
            var sumDistance = 0.0
            var unitX = 0.0
            var unitY = 0.0
            for (p in points) {
                val distance = max(p.distanceTo(center), 1e-9)
                sumDistance += distance
                unitX += (center.x - p.x) / distance
                unitY += (center.y - p.y) / distance
            }
            radius = sumDistance / n
            center = ShapePoint(mean.x + radius * unitX / n, mean.y + radius * unitY / n)
        }
        return if (radius.isFinite() && center.x.isFinite() && center.y.isFinite()) FittedCircle(center, radius) else null
    }

    fun ellipse(points: List<ShapePoint>): FittedEllipse? {
        if (points.size < 5) return null
        val mean = centroid(points)
        val spread = sqrt(points.sumOf { (it - mean).dot(it - mean) } / points.size)
        if (spread < 1e-9) return null
        val s1 = Array(3) { DoubleArray(3) }
        val s2 = Array(3) { DoubleArray(3) }
        val s3 = Array(3) { DoubleArray(3) }
        for (p in points) {
            val x = (p.x - mean.x) / spread
            val y = (p.y - mean.y) / spread
            val quadratic = doubleArrayOf(x * x, x * y, y * y)
            val linear = doubleArrayOf(x, y, 1.0)
            for (i in 0..2) {
                for (j in 0..2) {
                    s1[i][j] += quadratic[i] * quadratic[j]
                    s2[i][j] += quadratic[i] * linear[j]
                    s3[i][j] += linear[i] * linear[j]
                }
            }
        }
        val s3Inverse = invert3(s3) ?: return null
        val t = Array(3) { i -> DoubleArray(3) { j -> -(0..2).sumOf { k -> s3Inverse[i][k] * s2[j][k] } } }
        val m = Array(3) { i -> DoubleArray(3) { j -> s1[i][j] + (0..2).sumOf { k -> s2[i][k] * t[k][j] } } }
        val reduced = arrayOf(DoubleArray(3) { m[2][it] / 2 }, DoubleArray(3) { -m[1][it] }, DoubleArray(3) { m[0][it] / 2 })
        val quadratic =
            realEigenvalues3(reduced)
                .mapNotNull { eigenvector3(reduced, it) }
                .firstOrNull { 4 * it[0] * it[2] - it[1] * it[1] > 0 }
                ?: return null
        val linear = DoubleArray(3) { i -> (0..2).sumOf { k -> t[i][k] * quadratic[k] } }
        val normalized = conicToEllipse(quadratic[0], quadratic[1], quadratic[2], linear[0], linear[1], linear[2]) ?: return null
        return FittedEllipse(
            center = ShapePoint(mean.x + normalized.center.x * spread, mean.y + normalized.center.y * spread),
            radiusX = normalized.radiusX * spread,
            radiusY = normalized.radiusY * spread,
            rotation = normalized.rotation,
        )
    }

    fun conicToEllipse(
        a: Double,
        b: Double,
        c: Double,
        d: Double,
        e: Double,
        f: Double,
    ): FittedEllipse? {
        val determinant = 4 * a * c - b * b
        if (determinant <= 0) return null
        val centerX = (b * e - 2 * c * d) / determinant
        val centerY = (b * d - 2 * a * e) / determinant
        val constant = a * centerX * centerX + b * centerX * centerY + c * centerY * centerY + d * centerX + e * centerY + f
        val halfTrace = (a + c) / 2
        val root = hypot((a - c) / 2, b / 2)
        val first = halfTrace + root
        val second = halfTrace - root
        val angleFirst = 0.5 * atan2(b, a - c)
        val radiusFirst = -constant / first
        val radiusSecond = -constant / second
        if (radiusFirst <= 0 || radiusSecond <= 0 || !radiusFirst.isFinite() || !radiusSecond.isFinite()) return null
        return FittedEllipse(
            center = ShapePoint(centerX, centerY),
            radiusX = sqrt(radiusSecond),
            radiusY = sqrt(radiusFirst),
            rotation = angleFirst + PI / 2,
        )
    }

    fun simplify(
        points: List<ShapePoint>,
        tolerance: Double,
        first: Int = 0,
        last: Int = points.lastIndex,
    ): List<Int> {
        val kept = sortedSetOf(first, last)
        val pending = ArrayDeque<Pair<Int, Int>>()
        pending.add(first to last)
        while (pending.isNotEmpty()) {
            val (start, end) = pending.removeLast()
            if (end - start < 2) continue
            var farthest = -1
            var farthestDistance = tolerance
            for (i in start + 1 until end) {
                val distance = segmentDistance(points[i], points[start], points[end])
                if (distance > farthestDistance) {
                    farthestDistance = distance
                    farthest = i
                }
            }
            if (farthest >= 0) {
                kept.add(farthest)
                pending.add(start to farthest)
                pending.add(farthest to end)
            }
        }
        return kept.toList()
    }

    fun bezierCurve(
        points: List<ShapePoint>,
        tolerance: Double,
        maximumSegments: Int,
    ): List<CubicBezier>? {
        if (points.size < 2) return null
        val reach = min(TANGENT_REACH, points.lastIndex)
        val startTangent = (points[reach] - points[0]).normalized()
        val endTangent = (points[points.lastIndex - reach] - points.last()).normalized()
        val segments = mutableListOf<CubicBezier>()
        return if (fitCubic(points, 0, points.lastIndex, startTangent, endTangent, tolerance, maximumSegments, segments)) segments else null
    }

    private fun fitCubic(
        points: List<ShapePoint>,
        first: Int,
        last: Int,
        startTangent: ShapePoint,
        endTangent: ShapePoint,
        tolerance: Double,
        maximumSegments: Int,
        segments: MutableList<CubicBezier>,
    ): Boolean {
        if (segments.size >= maximumSegments) return false
        val start = points[first]
        val end = points[last]
        if (last - first == 1) {
            val third = start.distanceTo(end) / 3
            segments += CubicBezier(start, start + startTangent * third, end + endTangent * third, end)
            return true
        }
        var parameters = chordLengthParameters(points, first, last)
        var curve = generateBezier(points, first, last, parameters, startTangent, endTangent)
        var (error, split) = maximumError(points, first, last, curve, parameters)
        if (error < tolerance) {
            segments += curve
            return true
        }
        if (error < tolerance * REPARAMETERIZE_FACTOR) {
            repeat(REPARAMETERIZE_ITERATIONS) {
                parameters = reparameterize(points, first, last, parameters, curve)
                curve = generateBezier(points, first, last, parameters, startTangent, endTangent)
                val result = maximumError(points, first, last, curve, parameters)
                error = result.first
                split = result.second
                if (error < tolerance) {
                    segments += curve
                    return true
                }
            }
        }
        val centerTangent = (points[split - 1] - points[split + 1]).normalized()
        return fitCubic(points, first, split, startTangent, centerTangent, tolerance, maximumSegments, segments) &&
            fitCubic(points, split, last, centerTangent * -1.0, endTangent, tolerance, maximumSegments, segments)
    }

    private fun chordLengthParameters(
        points: List<ShapePoint>,
        first: Int,
        last: Int,
    ): DoubleArray {
        val parameters = DoubleArray(last - first + 1)
        for (i in first + 1..last) parameters[i - first] = parameters[i - first - 1] + points[i].distanceTo(points[i - 1])
        val total = parameters.last()
        if (total > 0) for (i in parameters.indices) parameters[i] /= total
        return parameters
    }

    private fun generateBezier(
        points: List<ShapePoint>,
        first: Int,
        last: Int,
        parameters: DoubleArray,
        startTangent: ShapePoint,
        endTangent: ShapePoint,
    ): CubicBezier {
        val start = points[first]
        val end = points[last]
        var c00 = 0.0
        var c01 = 0.0
        var c11 = 0.0
        var x0 = 0.0
        var x1 = 0.0
        for (i in parameters.indices) {
            val u = parameters[i]
            val v = 1 - u
            val basis0 = 3 * v * v * u
            val basis1 = 3 * v * u * u
            val a0 = startTangent * basis0
            val a1 = endTangent * basis1
            c00 += a0.dot(a0)
            c01 += a0.dot(a1)
            c11 += a1.dot(a1)
            val baseline = start * (v * v * v + basis0) + end * (basis1 + u * u * u)
            val residual = points[first + i] - baseline
            x0 += a0.dot(residual)
            x1 += a1.dot(residual)
        }
        val determinant = c00 * c11 - c01 * c01
        val alphaStart = if (abs(determinant) < 1e-12) 0.0 else (x0 * c11 - x1 * c01) / determinant
        val alphaEnd = if (abs(determinant) < 1e-12) 0.0 else (c00 * x1 - c01 * x0) / determinant
        val chord = start.distanceTo(end)
        val minimum = chord * 1e-6
        if (alphaStart < minimum || alphaEnd < minimum) {
            val third = chord / 3
            return CubicBezier(start, start + startTangent * third, end + endTangent * third, end)
        }
        return CubicBezier(start, start + startTangent * alphaStart, end + endTangent * alphaEnd, end)
    }

    private fun reparameterize(
        points: List<ShapePoint>,
        first: Int,
        last: Int,
        parameters: DoubleArray,
        curve: CubicBezier,
    ): DoubleArray =
        DoubleArray(parameters.size) { i ->
            val u = parameters[i]
            val difference = curve.at(u) - points[first + i]
            val first1 = curve.derivative(u)
            val second = curve.secondDerivative(u)
            val denominator = first1.dot(first1) + difference.dot(second)
            if (abs(denominator) < 1e-12) u else (u - difference.dot(first1) / denominator).coerceIn(0.0, 1.0)
        }

    private fun maximumError(
        points: List<ShapePoint>,
        first: Int,
        last: Int,
        curve: CubicBezier,
        parameters: DoubleArray,
    ): Pair<Double, Int> {
        var worst = 0.0
        var split = (first + last) / 2
        for (i in first + 1 until last) {
            val distance = curve.at(parameters[i - first]).distanceTo(points[i])
            if (distance > worst) {
                worst = distance
                split = i
            }
        }
        return worst to split
    }

    fun centroid(points: List<ShapePoint>): ShapePoint {
        var x = 0.0
        var y = 0.0
        for (p in points) {
            x += p.x
            y += p.y
        }
        return ShapePoint(x / points.size, y / points.size)
    }

    fun segmentDistance(
        p: ShapePoint,
        a: ShapePoint,
        b: ShapePoint,
    ): Double {
        val segment = b - a
        val lengthSquared = segment.dot(segment)
        if (lengthSquared < 1e-18) return p.distanceTo(a)
        val t = ((p - a).dot(segment) / lengthSquared).coerceIn(0.0, 1.0)
        return p.distanceTo(a + segment * t)
    }

    fun polylineDistance(
        p: ShapePoint,
        polyline: List<ShapePoint>,
    ): Double {
        if (polyline.size == 1) return p.distanceTo(polyline[0])
        var best = Double.MAX_VALUE
        for (i in 1 until polyline.size) best = min(best, segmentDistance(p, polyline[i - 1], polyline[i]))
        return best
    }

    fun meanDistance(
        points: List<ShapePoint>,
        polyline: List<ShapePoint>,
    ): Double = points.sumOf { polylineDistance(it, polyline) } / points.size

    fun solve3(
        matrix: Array<DoubleArray>,
        right: DoubleArray,
    ): DoubleArray? {
        val inverse = invert3(matrix) ?: return null
        return DoubleArray(3) { i -> (0..2).sumOf { inverse[i][it] * right[it] } }
    }

    fun invert3(m: Array<DoubleArray>): Array<DoubleArray>? {
        val c00 = m[1][1] * m[2][2] - m[1][2] * m[2][1]
        val c01 = m[1][2] * m[2][0] - m[1][0] * m[2][2]
        val c02 = m[1][0] * m[2][1] - m[1][1] * m[2][0]
        val determinant = m[0][0] * c00 + m[0][1] * c01 + m[0][2] * c02
        val scale = m.maxOf { row -> row.maxOf { abs(it) } }
        if (abs(determinant) <= 1e-14 * scale * scale * scale || !determinant.isFinite()) return null
        val inverse = 1 / determinant
        return arrayOf(
            doubleArrayOf(
                c00 * inverse,
                (m[0][2] * m[2][1] - m[0][1] * m[2][2]) * inverse,
                (m[0][1] * m[1][2] - m[0][2] * m[1][1]) * inverse,
            ),
            doubleArrayOf(
                c01 * inverse,
                (m[0][0] * m[2][2] - m[0][2] * m[2][0]) * inverse,
                (m[0][2] * m[1][0] - m[0][0] * m[1][2]) * inverse,
            ),
            doubleArrayOf(
                c02 * inverse,
                (m[0][1] * m[2][0] - m[0][0] * m[2][1]) * inverse,
                (m[0][0] * m[1][1] - m[0][1] * m[1][0]) * inverse,
            ),
        )
    }

    fun realEigenvalues3(m: Array<DoubleArray>): List<Double> {
        val trace = m[0][0] + m[1][1] + m[2][2]
        val minors =
            m[0][0] * m[1][1] - m[0][1] * m[1][0] +
                m[0][0] * m[2][2] - m[0][2] * m[2][0] +
                m[1][1] * m[2][2] - m[1][2] * m[2][1]
        val determinant =
            m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
                m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
        val a = -trace
        val b = minors
        val c = -determinant
        val p = b - a * a / 3
        val q = 2 * a * a * a / 27 - a * b / 3 + c
        val discriminant = q * q / 4 + p * p * p / 27
        val shift = -a / 3
        return if (discriminant < 0) {
            val radius = sqrt(-p / 3)
            val angle = acos((-q / 2 / (radius * radius * radius)).coerceIn(-1.0, 1.0))
            (0..2).map { 2 * radius * cos((angle + 2 * PI * it) / 3) + shift }
        } else {
            val root = sqrt(discriminant)
            listOf(cbrt(-q / 2 + root) + cbrt(-q / 2 - root) + shift)
        }
    }

    fun eigenvector3(
        m: Array<DoubleArray>,
        eigenvalue: Double,
    ): DoubleArray? {
        val rows = Array(3) { i -> DoubleArray(3) { j -> m[i][j] - if (i == j) eigenvalue else 0.0 } }
        var best: DoubleArray? = null
        var bestNorm = 0.0
        for ((i, j) in listOf(0 to 1, 0 to 2, 1 to 2)) {
            val r = rows[i]
            val s = rows[j]
            val cross = doubleArrayOf(r[1] * s[2] - r[2] * s[1], r[2] * s[0] - r[0] * s[2], r[0] * s[1] - r[1] * s[0])
            val norm = sqrt(cross.sumOf { it * it })
            if (norm > bestNorm) {
                bestNorm = norm
                best = cross
            }
        }
        if (bestNorm < 1e-300) return null
        return best?.let { vector -> DoubleArray(3) { vector[it] / bestNorm } }
    }

    private const val GEOMETRIC_ITERATIONS = 10
    private const val TANGENT_REACH = 3
    private const val REPARAMETERIZE_FACTOR = 4.0
    private const val REPARAMETERIZE_ITERATIONS = 4
}
