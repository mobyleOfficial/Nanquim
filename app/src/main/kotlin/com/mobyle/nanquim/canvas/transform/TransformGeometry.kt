package com.mobyle.nanquim.canvas.transform

import com.mobyle.nanquim.engine.Point
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

object TransformGeometry {
    const val MARGIN_PX = 2.0

    const val WARP_SUBDIVISIONS = 12

    fun homography(
        source: List<Point>,
        target: List<Point>,
    ): DoubleArray? {
        require(source.size == 4 && target.size == 4)
        val matrix = Array(8) { DoubleArray(9) }
        for (index in 0 until 4) {
            val (u, v) = source[index].x to source[index].y
            val (x, y) = target[index].x to target[index].y
            matrix[2 * index] = doubleArrayOf(u, v, 1.0, 0.0, 0.0, 0.0, -u * x, -v * x, x)
            matrix[2 * index + 1] = doubleArrayOf(0.0, 0.0, 0.0, u, v, 1.0, -u * y, -v * y, y)
        }
        for (column in 0 until 8) {
            val pivot = (column until 8).maxBy { abs(matrix[it][column]) }
            if (abs(matrix[pivot][column]) < 1e-12) return null
            matrix[column] = matrix[pivot].also { matrix[pivot] = matrix[column] }
            for (row in 0 until 8) {
                if (row == column) continue
                val factor = matrix[row][column] / matrix[column][column]
                for (k in column..8) matrix[row][k] -= factor * matrix[column][k]
            }
        }
        return DoubleArray(9) { index -> if (index == 8) 1.0 else matrix[index][8] / matrix[index][index] }
    }

    fun project(
        h: DoubleArray,
        point: Point,
    ): Pair<Point, Double> {
        val x = h[0] * point.x + h[1] * point.y + h[2]
        val y = h[3] * point.x + h[4] * point.y + h[5]
        val w = h[6] * point.x + h[7] * point.y + h[8]
        return Point(x / w, y / w) to w
    }

    fun grown(
        quad: List<Point>,
        margin: Double,
    ): List<Point> {
        fun unit(
            from: Point,
            to: Point,
        ): Point {
            val length = distance(from, to)
            return if (length == 0.0) Point(0.0, 0.0) else Point((to.x - from.x) / length, (to.y - from.y) / length)
        }
        val across = unit(quad[0], quad[1])
        val down = unit(quad[0], quad[3])
        val signs = listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0)
        return quad.mapIndexed { index, corner ->
            val (alongAcross, alongDown) = signs[index]
            Point(
                corner.x + margin * (alongAcross * across.x + alongDown * down.x),
                corner.y + margin * (alongAcross * across.y + alongDown * down.y),
            )
        }
    }

    fun quadMesh(
        sourceQuad: List<Point>,
        targetQuad: List<Point>,
    ): FloatArray? {
        val h = homography(sourceQuad, targetQuad) ?: return null
        val corners = grown(sourceQuad, MARGIN_PX)
        val projected = corners.map { project(h, it) }
        if (projected.any { it.second <= 1e-6 }) return null
        val order = intArrayOf(0, 1, 2, 0, 2, 3)
        val mesh = FloatArray(order.size * 5)
        order.forEachIndexed { index, corner ->
            val (position, w) = projected[corner]
            val source = corners[corner]
            mesh[index * 5] = position.x.toFloat()
            mesh[index * 5 + 1] = position.y.toFloat()
            mesh[index * 5 + 2] = w.toFloat()
            mesh[index * 5 + 3] = source.x.toFloat()
            mesh[index * 5 + 4] = source.y.toFloat()
        }
        return mesh
    }

    fun warpGrid(
        sourceQuad: List<Point>,
        targetQuad: List<Point>,
        size: Int,
    ): List<Point> {
        val h = homography(sourceQuad, targetQuad) ?: return List(size * size) { bilinear(targetQuad, (it % size) / (size - 1.0), (it / size) / (size - 1.0)) }
        return List(size * size) { index ->
            project(h, bilinear(sourceQuad, (index % size) / (size - 1.0), (index / size) / (size - 1.0))).first
        }
    }

    fun warpMesh(
        sourceQuad: List<Point>,
        grid: List<Point>,
        size: Int,
    ): FloatArray {
        val cells = (size - 1) * WARP_SUBDIVISIONS
        val sourceWidth = distance(sourceQuad[0], sourceQuad[1])
        val sourceHeight = distance(sourceQuad[0], sourceQuad[3])
        val marginS = if (sourceWidth > 0) MARGIN_PX / sourceWidth else 0.0
        val marginT = if (sourceHeight > 0) MARGIN_PX / sourceHeight else 0.0
        val points = cells + 1
        val positions = Array(points * points) { Point(0.0, 0.0) }
        val sources = Array(points * points) { Point(0.0, 0.0) }
        for (j in 0..cells) {
            for (i in 0..cells) {
                val s = -marginS + (1 + 2 * marginS) * i / cells
                val t = -marginT + (1 + 2 * marginT) * j / cells
                positions[j * points + i] = surface(grid, size, s, t)
                sources[j * points + i] = bilinear(sourceQuad, s, t)
            }
        }
        val mesh = FloatArray(cells * cells * 6 * 5)
        var offset = 0
        for (j in 0 until cells) {
            for (i in 0 until cells) {
                val a = j * points + i
                val b = a + 1
                val c = a + points + 1
                val d = a + points
                for (vertex in intArrayOf(a, b, c, a, c, d)) {
                    mesh[offset++] = positions[vertex].x.toFloat()
                    mesh[offset++] = positions[vertex].y.toFloat()
                    mesh[offset++] = 1f
                    mesh[offset++] = sources[vertex].x.toFloat()
                    mesh[offset++] = sources[vertex].y.toFloat()
                }
            }
        }
        return mesh
    }

    fun surface(
        grid: List<Point>,
        size: Int,
        s: Double,
        t: Double,
    ): Point {
        val cells = size - 1
        val fs = s * cells
        val ft = t * cells
        val column = fs.toInt().coerceIn(0, cells - 1)
        val row = ft.toInt().coerceIn(0, cells - 1)
        val ls = fs - column
        val lt = ft - row
        fun at(
            i: Int,
            j: Int,
        ): Point {
            val ci = i.coerceIn(0, cells)
            val cj = j.coerceIn(0, cells)
            val base = grid[cj * size + ci]
            if (ci == i && cj == j) return base
            val ni = if (i < 0) 1 else if (i > cells) cells - 1 else ci
            val nj = if (j < 0) 1 else if (j > cells) cells - 1 else cj
            val inner = grid[nj * size + ni]
            return Point(2 * base.x - inner.x, 2 * base.y - inner.y)
        }
        val rows =
            (-1..2).map { dj ->
                catmullRom(at(column - 1, row + dj), at(column, row + dj), at(column + 1, row + dj), at(column + 2, row + dj), ls)
            }
        return catmullRom(rows[0], rows[1], rows[2], rows[3], lt)
    }

    private fun catmullRom(
        p0: Point,
        p1: Point,
        p2: Point,
        p3: Point,
        t: Double,
    ): Point {
        val t2 = t * t
        val t3 = t2 * t
        fun axis(
            a: Double,
            b: Double,
            c: Double,
            d: Double,
        ) = 0.5 * ((2 * b) + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3)
        return Point(axis(p0.x, p1.x, p2.x, p3.x), axis(p0.y, p1.y, p2.y, p3.y))
    }

    fun bilinear(
        quad: List<Point>,
        s: Double,
        t: Double,
    ): Point {
        val top = Point(quad[0].x + (quad[1].x - quad[0].x) * s, quad[0].y + (quad[1].y - quad[0].y) * s)
        val bottom = Point(quad[3].x + (quad[2].x - quad[3].x) * s, quad[3].y + (quad[2].y - quad[3].y) * s)
        return Point(top.x + (bottom.x - top.x) * t, top.y + (bottom.y - top.y) * t)
    }

    fun center(quad: List<Point>): Point = Point(quad.sumOf { it.x } / quad.size, quad.sumOf { it.y } / quad.size)

    fun distance(
        a: Point,
        b: Point,
    ): Double = hypot(a.x - b.x, a.y - b.y)

    fun rotate(
        point: Point,
        pivot: Point,
        angle: Double,
    ): Point {
        val dx = point.x - pivot.x
        val dy = point.y - pivot.y
        return Point(pivot.x + dx * cos(angle) - dy * sin(angle), pivot.y + dx * sin(angle) + dy * cos(angle))
    }

}
