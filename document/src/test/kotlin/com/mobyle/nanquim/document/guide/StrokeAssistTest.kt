package com.mobyle.nanquim.document.guide

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class StrokeAssistTest {
    private val grid = GuideSettings(type = GuideType.Grid, visible = true)

    private fun assertAngle(
        expected: Double,
        actual: Double,
    ) = assertEquals(0.0, AssistGeometry.lineAngle(expected, actual), 1e-9, "expected $expected, was $actual")

    @Test
    fun samplesAreHeldUntilTwentyPxThenProjected() {
        val assist = StrokeAssist(grid, 100.0, 100.0)
        assertNull(assist.place(105.0, 103.0))
        assertNull(assist.place(115.0, 106.0))
        val (x, y) = assist.place(130.0, 108.0)!!
        assertEquals(130.0, x, 1e-9)
        assertEquals(100.0, y, 1e-9)
        assertEquals(115.0 to 100.0, assist.release(115.0, 106.0))
        val (laterX, laterY) = assist.place(300.0, 160.0)!!
        assertEquals(300.0, laterX, 1e-9)
        assertEquals(100.0, laterY, 1e-9)
    }

    @Test
    fun gridDirectionsFollowTheRotationAndIsometricAngles() {
        val turned = grid.copy(grid = GridGuide(rotation = 0.3))
        val line = AssistGeometry.choose(turned, 0.0, 0.0, 10 * cos(0.35), 10 * sin(0.35))!!
        assertAngle(0.3, line.direction)
        val iso = grid.copy(type = GuideType.Isometric)
        for ((movement, expected) in listOf(40.0 to 30.0, 80.0 to 90.0, 140.0 to 150.0, 220.0 to 30.0)) {
            val radians = movement * PI / 180
            val chosen = AssistGeometry.choose(iso, 0.0, 0.0, cos(radians), sin(radians))!!
            assertAngle(expected * PI / 180, chosen.direction)
        }
    }

    @Test
    fun perspectiveFollowsTheNearestVanishingPointOrParallel() {
        val two =
            GuideSettings(
                type = GuideType.Perspective,
                visible = true,
                perspective = PerspectiveGuide(listOf(VanishingPoint(0.0, 500.0), VanishingPoint(2000.0, 500.0))),
            )
        val toLeft = AssistGeometry.choose(two, 1000.0, 300.0, 990.0, 302.5)!!
        assertAngle(atan2(200.0, -1000.0), toLeft.direction)
        val (x, y) = toLeft.project(0.0, 500.0)
        assertEquals(0.0, x, 1e-6)
        assertEquals(500.0, y, 1e-6)
        assertAngle(PI / 2, AssistGeometry.choose(two, 1000.0, 300.0, 1001.0, 330.0)!!.direction)
        val one = two.copy(perspective = PerspectiveGuide(listOf(VanishingPoint(0.0, 500.0))))
        assertAngle(0.0, AssistGeometry.choose(one, 1000.0, 300.0, 1030.0, 301.0)!!.direction)
        val three = two.copy(perspective = PerspectiveGuide(two.perspective.points + VanishingPoint(1000.0, 9000.0)))
        assertEquals(3, AssistGeometry.directions(three, 1000.0, 300.0).size)
    }

    @Test
    fun whichGuidesConstrain() {
        assertTrue(AssistGeometry.constrains(grid))
        assertFalse(AssistGeometry.constrains(grid.copy(visible = false)))
        assertFalse(AssistGeometry.constrains(grid.copy(type = GuideType.Perspective)))
        assertFalse(AssistGeometry.constrains(grid.copy(type = GuideType.Symmetry)))
    }

    @Test
    fun symmetryCopiesOnlyWhileTheSymmetryIsShown() {
        val radial = grid.copy(type = GuideType.Symmetry, symmetry = SymmetryGuide(kind = SymmetryKind.Radial, segments = 8))
        val copies = AssistGeometry.symmetry(radial)
        assertEquals(8, copies.size)
        assertEquals(GuideTransform.IDENTITY, copies.first())
        assertTrue(AssistGeometry.symmetry(radial.copy(visible = false)).isEmpty())
        assertTrue(AssistGeometry.symmetry(grid).isEmpty())
    }

    @Test
    fun aStrokeWithNoDirectionStaysFree() {
        val symmetry = grid.copy(type = GuideType.Symmetry)
        val assist = StrokeAssist(symmetry, 0.0, 0.0)
        assertEquals(40.0 to 30.0, assist.place(40.0, 30.0))
        assertTrue(assist.free)
        assertEquals(1.0 to 2.0, assist.release(1.0, 2.0))
    }

    @Test
    fun recordedStrokesPickTheirLine() {
        val points = listOf(0.0 to 0.0, 3.0 to 5.0, 6.0 to 18.0, 9.0 to 40.0, 20.0 to 90.0)
        val line = StrokeAssist.lineFor(grid, points)
        assertNotNull(line)
        assertAngle(PI / 2, line!!.direction)
        assertNull(StrokeAssist.lineFor(grid, listOf(0.0 to 0.0, 5.0 to 5.0)))
    }
}
