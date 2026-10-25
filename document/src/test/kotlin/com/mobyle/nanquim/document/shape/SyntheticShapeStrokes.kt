package com.mobyle.nanquim.document.shape

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

object SyntheticShapeStrokes {
    data class LabelledStroke(
        val label: String,
        val points: List<ShapePoint>,
    )

    fun generate(
        count: Int,
        seed: Int,
        noise: Double = 1.0,
    ): List<LabelledStroke> {
        val random = Random(seed)
        return (0 until count).map { index ->
            val label = ShapeTraceSet.LABELS[index % ShapeTraceSet.LABELS.size]
            LabelledStroke(label, stroke(label, random, noise))
        }
    }

    fun stroke(
        label: String,
        random: Random,
        noise: Double = 1.0,
    ): List<ShapePoint> {
        val size = random.range(150.0, 1500.0)
        val ideal =
            when (label) {
                "line" -> line(size, random)
                "arc" -> arc(size, random)
                "ellipse" -> ellipse(size, random)
                "rectangle" -> rectangle(size, random)
                "polygon" -> polygon(size, random)
                "curve" -> curve(size, random)
                else -> error("unknown label $label")
            }
        val rotation = random.range(0.0, 2 * PI)
        val offset = ShapePoint(random.range(500.0, 3500.0), random.range(500.0, 3000.0))
        val placed =
            ideal.map {
                ShapePoint(it.x * cos(rotation) - it.y * sin(rotation), it.x * sin(rotation) + it.y * cos(rotation)) + offset
            }
        return handDrawn(placed, size, random, noise)
    }

    private fun handDrawn(
        path: List<ShapePoint>,
        size: Double,
        random: Random,
        noise: Double,
    ): List<ShapePoint> {
        val dense = densify(path, 0.5)
        val length = (1 until dense.size).sumOf { dense[it].distanceTo(dense[it - 1]) }
        val wobbles =
            (0 until 3).map {
                Triple(random.range(0.001, 0.004) * size * noise, random.range(0.5, 4.0), random.range(0.0, 2 * PI))
            }
        val baseSpacing = random.range(1.5, 6.0)
        val result = mutableListOf<ShapePoint>()
        var walked = 0.0
        var next = 0.0
        for (i in dense.indices) {
            if (i > 0) walked += dense[i].distanceTo(dense[i - 1])
            if (walked < next && i != dense.lastIndex) continue
            val before = dense[max(0, i - 2)]
            val after = dense[min(dense.lastIndex, i + 2)]
            val normal = (after - before).normalized().let { ShapePoint(-it.y, it.x) }
            val s = walked / length
            val wobble = wobbles.sumOf { (amplitude, frequency, phase) -> amplitude * sin(2 * PI * frequency * s + phase) }
            val jitter = ShapePoint(random.gaussian(), random.gaussian()) * (JITTER * noise)
            result += dense[i] + normal * wobble + jitter
            next = walked + baseSpacing * random.range(0.6, 1.4)
        }
        return result
    }

    private fun line(
        size: Double,
        random: Random,
    ): List<ShapePoint> {
        val bow = random.range(-0.005, 0.005) * size
        return (0..40).map {
            val t = it / 40.0
            ShapePoint((t - 0.5) * size, bow * 4 * t * (1 - t))
        }
    }

    private fun arc(
        size: Double,
        random: Random,
    ): List<ShapePoint> {
        val radius = size / 2
        val sweep = random.range(60.0, 270.0) * PI / 180 * random.sign()
        val start = random.range(0.0, 2 * PI)
        return (0..200).map {
            val angle = start + sweep * it / 200
            ShapePoint(radius * cos(angle), radius * sin(angle))
        }
    }

    private fun ellipse(
        size: Double,
        random: Random,
    ): List<ShapePoint> {
        val radiusX = size / 2
        val radiusY = radiusX * random.range(0.35, 1.0)
        val start = random.range(0.0, 2 * PI)
        val turn = 2 * PI * (1 + random.range(-0.05, 0.12)) * random.sign()
        return (0..400).map {
            val angle = start + turn * it / 400
            ShapePoint(radiusX * cos(angle), radiusY * sin(angle))
        }
    }

    private fun rectangle(
        size: Double,
        random: Random,
    ): List<ShapePoint> {
        val width = size
        val height = size * random.range(0.3, 1.0)
        val corners = listOf(ShapePoint(0.0, 0.0), ShapePoint(width, 0.0), ShapePoint(width, height), ShapePoint(0.0, height))
        val centered = corners.map { it - ShapePoint(width / 2, height / 2) }
        return closedPolygon(centered, random.range(0.0, 0.02) * min(width, height), random)
    }

    private fun polygon(
        size: Double,
        random: Random,
    ): List<ShapePoint> {
        val radius = size / 2
        val vertices =
            when (random.nextInt(4)) {
                0 -> regular(3, radius, 0.15, random)
                1 -> regular(5, radius, 0.08, random)
                2 -> regular(6, radius, 0.06, random)
                else -> quadrilateral(radius, random)
            }
        return closedPolygon(vertices, random.range(0.0, 0.015) * radius, random)
    }

    private fun regular(
        sides: Int,
        radius: Double,
        jitter: Double,
        random: Random,
    ): List<ShapePoint> =
        (0 until sides).map {
            val angle = 2 * PI * it / sides + random.range(-jitter, jitter)
            val r = radius * (1 + random.range(-jitter, jitter))
            ShapePoint(r * cos(angle), r * sin(angle))
        }

    private fun quadrilateral(
        radius: Double,
        random: Random,
    ): List<ShapePoint> {
        while (true) {
            val vertices = regular(4, radius, 0.6, random)
            val turns =
                vertices.indices.map { i ->
                    val incoming = vertices[i] - vertices[(i + 3) % 4]
                    val outgoing = vertices[(i + 1) % 4] - vertices[i]
                    kotlin.math.atan2(incoming.x * outgoing.y - incoming.y * outgoing.x, incoming.dot(outgoing))
                }
            val convex = turns.all { it > 0 }
            val notSquare = turns.any { abs(it - PI / 2) > 25 * PI / 180 }
            val noFlatCorner = turns.all { it > 35 * PI / 180 }
            if (convex && notSquare && noFlatCorner) return vertices
        }
    }

    private fun closedPolygon(
        vertices: List<ShapePoint>,
        cornerRadius: Double,
        random: Random,
    ): List<ShapePoint> {
        val rounded = mutableListOf<ShapePoint>()
        for (i in vertices.indices) {
            val previous = vertices[(i + vertices.size - 1) % vertices.size]
            val corner = vertices[i]
            val next = vertices[(i + 1) % vertices.size]
            val inward = min(cornerRadius, min(corner.distanceTo(previous), corner.distanceTo(next)) / 4)
            val a = corner + (previous - corner).normalized() * inward
            val b = corner + (next - corner).normalized() * inward
            for (step in 0..6) {
                val t = step / 6.0
                rounded += a * ((1 - t) * (1 - t)) + corner * (2 * t * (1 - t)) + b * (t * t)
            }
        }
        val loop = densify(rounded + rounded.first(), 1.0)
        val perimeter = (1 until loop.size).sumOf { loop[it].distanceTo(loop[it - 1]) }
        val start = random.nextInt(loop.size - 1)
        val extra = random.range(-0.03, 0.08)
        val total = ((loop.size - 1) * (1 + extra)).toInt()
        val walked = (0..total).map { loop[(start + it) % (loop.size - 1)] }
        return if (random.nextBoolean()) walked else walked.reversed().also { check(perimeter > 0) }
    }

    private fun curve(
        size: Double,
        random: Random,
    ): List<ShapePoint> =
        if (random.nextBoolean()) {
            val bend = random.range(0.3, 0.6) * size
            val curve =
                CubicBezier(
                    ShapePoint(-size / 2, 0.0),
                    ShapePoint(-size / 6, bend),
                    ShapePoint(size / 6, -bend),
                    ShapePoint(size / 2, 0.0),
                )
            (0..200).map { curve.at(it / 200.0) }
        } else {
            val periods = random.range(1.0, 2.0)
            val amplitude = random.range(0.12, 0.3) * size
            (0..300).map {
                val t = it / 300.0
                ShapePoint((t - 0.5) * size, amplitude * sin(2 * PI * periods * t))
            }
        }

    private fun densify(
        path: List<ShapePoint>,
        step: Double,
    ): List<ShapePoint> {
        val result = mutableListOf(path.first())
        for (i in 1 until path.size) {
            val a = path[i - 1]
            val b = path[i]
            val count = max(1, (a.distanceTo(b) / step).toInt())
            for (j in 1..count) result += a + (b - a) * (j.toDouble() / count)
        }
        return result
    }

    private fun Random.range(
        from: Double,
        until: Double,
    ): Double = from + nextDouble() * (until - from)

    private fun Random.sign(): Double = if (nextBoolean()) 1.0 else -1.0

    private fun Random.gaussian(): Double {
        val u = max(nextDouble(), 1e-12)
        val v = nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * cos(2 * PI * v)
    }

    private const val JITTER = 0.4
}
