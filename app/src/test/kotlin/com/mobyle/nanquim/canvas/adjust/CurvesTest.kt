package com.mobyle.nanquim.canvas.adjust

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CurvesTest {
    @Test
    fun insertsInOrderAndRefusesCrowdedOrFullCurves() {
        val (points, index) = CurveEditing.insert(CurveEditing.STRAIGHT, 0.4f, 0.7f)!!
        assertEquals(1, index)
        assertEquals(listOf(0f, 0.4f, 1f), points.map { it.x })
        assertNull(CurveEditing.insert(points, 0.41f, 0.2f))
        var full = CurveEditing.STRAIGHT
        for (step in 1 until CurveEditing.MAXIMUM_POINTS - 1) full = CurveEditing.insert(full, step / 16f, 0.5f)!!.first
        assertEquals(CurveEditing.MAXIMUM_POINTS, full.size)
        assertNull(CurveEditing.insert(full, 0.97f, 0.5f))
    }

    @Test
    fun movedPointsStayBetweenTheirNeighbours() {
        val points = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.5f), CurvePoint(1f, 1f))
        val moved = CurveEditing.move(points, 1, 1.2f, -0.3f)
        assertEquals(1f - CurveEditing.MINIMUM_GAP, moved[1].x, 1e-6f)
        assertEquals(0f, moved[1].y)
        assertEquals(0.3f, CurveEditing.move(points, 0, 0.3f, 0.2f)[0].x, 1e-6f)
    }

    @Test
    fun keepsAtLeastTwoPoints() {
        assertEquals(CurveEditing.STRAIGHT, CurveEditing.remove(CurveEditing.STRAIGHT, 0))
        val three = CurveEditing.insert(CurveEditing.STRAIGHT, 0.5f, 0.9f)!!.first
        assertEquals(CurveEditing.STRAIGHT, CurveEditing.remove(three, 1))
    }

    @Test
    fun findsTheNearestPointWithinTheRadius() {
        val points = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.5f), CurvePoint(1f, 1f))
        assertEquals(1, CurveEditing.pointNear(points, 0.52f, 0.49f, 0.05f))
        assertNull(CurveEditing.pointNear(points, 0.3f, 0.6f, 0.05f))
    }

    @Test
    fun evaluatesLikeTheEngine() {
        val points = listOf(CurvePoint(0f, 0f), CurvePoint(0.25f, 0.6f), CurvePoint(0.5f, 0.65f), CurvePoint(1f, 1f))
        for (point in points) assertEquals(point.y, CurveEditing.evaluate(points, point.x), 1e-6f)
        var previous = 0f
        for (step in 0..200) {
            val value = CurveEditing.evaluate(points, step / 200f)
            assertTrue(value >= previous - 1e-6f)
            previous = value
        }
        assertEquals(0.3f, CurveEditing.evaluate(listOf(CurvePoint(0.2f, 0.3f), CurvePoint(0.8f, 0.9f)), 0.1f))
    }

    @Test
    fun tableDataListsEachCurve() {
        assertArrayEquals(
            floatArrayOf(2f, 0f, 0f, 1f, 1f, 1f, 0.5f, 0.5f),
            CurveEditing.tableData(listOf(CurveEditing.STRAIGHT, listOf(CurvePoint(0.5f, 0.5f)))),
        )
    }

    @Test
    fun histogramsSkipTransparentPixels() {
        val pixels =
            byteArrayOf(
                255.toByte(),
                0,
                0,
                255.toByte(),
                0,
                255.toByte(),
                0,
                128.toByte(),
                10,
                20,
                30,
                0,
            )
        val histogram = Histogram.of(pixels)
        assertEquals(1, histogram.red[255])
        assertEquals(1, histogram.red[0])
        assertEquals(2, histogram.red.sum())
        assertEquals(1, histogram.alpha[128])
        assertEquals(1, histogram.luminance[77])
        assertEquals(1, histogram.luminance[150])
        assertEquals(histogram.luminance, histogram.of(CurveChannel.Master))
    }
}
