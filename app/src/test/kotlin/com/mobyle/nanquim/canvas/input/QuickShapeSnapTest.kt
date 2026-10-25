package com.mobyle.nanquim.canvas.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.mobyle.nanquim.document.shape.LineShape
import com.mobyle.nanquim.document.shape.PolygonShape
import com.mobyle.nanquim.document.shape.QuickShapeStroke
import com.mobyle.nanquim.document.shape.ShapeKind
import com.mobyle.nanquim.engine.StrokeSample
import com.mobyle.nanquim.engine.StrokeSampleBuffer
import kotlin.math.hypot

class QuickShapeSnapTest {
    private val stylus = 2

    private fun stroke(corners: List<Pair<Double, Double>>): List<StrokeSample> {
        val points =
            corners.zipWithNext().flatMap { (a, b) ->
                (0 until 40).map { step ->
                    val t = step / 40.0
                    Pair(a.first + (b.first - a.first) * t, a.second + (b.second - a.second) * t + if (step % 2 == 0) 1.0 else -1.0)
                }
            } + corners.last()
        return points.mapIndexed { index, (x, y) ->
            StrokeSample(
                x,
                y,
                0.2f + 0.8f * index / (points.size - 1),
                tilt = 0.5f,
                eventTimeNanos = index * 4_000_000L,
                toolType = stylus,
                pointerId = 3,
            )
        }
    }

    private val rectangle = listOf(100.0 to 100.0, 500.0 to 100.0, 500.0 to 350.0, 100.0 to 350.0, 100.0 to 100.0)

    private fun StrokeSampleBuffer.samples() = (0 until count).map(::sampleAt)

    @Test
    fun aRecognizedStrokeMorphsIntoItsShapeWithTheStrokesProfile() {
        val samples = stroke(rectangle)
        val snap = QuickShapeSnap.recognize(samples, canvasPerScreenPixel = 1.0)!!
        assertEquals(ShapeKind.Rectangle, snap.shape.kind)
        val morph = snap.morph(constrain = false)!!
        assertEquals(QuickShapeSnap.MORPH_FRAMES, morph.frames.size)
        assertEquals(snap.shape, morph.shape)
        val outline = QuickShapeStroke.strokeOutline(snap.shape)
        assertEquals(outline.last(), morph.end)
        val shape = morph.frames.last().samples()
        assertEquals(outline.map { it.x.toFloat() }, shape.map { it.x.toFloat() })
        assertEquals(outline.map { it.y.toFloat() }, shape.map { it.y.toFloat() })
        assertEquals(0.2f, shape.first().pressure, 1e-6f)
        assertEquals(1f, shape.last().pressure, 1e-6f)
        assertTrue(shape.zipWithNext().all { (a, b) -> b.pressure >= a.pressure && b.eventTimeNanos >= a.eventTimeNanos })
        assertEquals(samples.last().eventTimeNanos, shape.last().eventTimeNanos)
        assertTrue(shape.all { it.tilt == 0.5f && it.toolType == stylus && it.pointerId == 3 && it.flags == 0 })
        val first = morph.frames.first().samples()
        assertEquals(samples.first().x, first.first().x, 1.0)
        assertEquals(samples.first().y, first.first().y, 1.0)
        assertNull(snap.morph(constrain = false))
    }

    @Test
    fun aFingerConstrainsTheShapeFromWhatIsShown() {
        val snap = QuickShapeSnap.recognize(stroke(rectangle), 1.0)!!
        val free = snap.morph(constrain = false)!!
        val square = snap.morph(constrain = true)!!
        assertNull(snap.morph(constrain = true))
        assertTrue(snap.constrained)
        val vertices = (square.shape as PolygonShape).vertices
        val sides = vertices.indices.map { vertices[it].distanceTo(vertices[(it + 1) % 4]) }
        sides.forEach { assertEquals(sides.first(), it, 1e-9) }
        val rectangleStart =
            free.frames
                .last()
                .samples()
                .first()
        val squareStart =
            square.frames
                .first()
                .samples()
                .first()
        assertTrue(hypot(rectangleStart.x - squareStart.x, rectangleStart.y - squareStart.y) < 10.0)
        assertEquals(QuickShapeStroke.strokeOutline(square.shape).size, square.frames.last().count)
        val back = snap.morph(constrain = false)!!
        assertEquals(ShapeKind.Rectangle, back.shape.kind)
        assertEquals(snap.shape, back.shape)
        val once = QuickShapeSnap.recognize(stroke(rectangle), 1.0)!!.morph(constrain = false, frameCount = 1)!!
        assertEquals(free.frames.last().samples(), once.frames.single().samples())
        assertEquals(free.frames.last().samples(), snap.samplesFor(snap.shape).samples())
    }

    @Test
    fun theMinimumSizeIsOnScreen() {
        val samples =
            (0..20).map {
                StrokeSample(
                    100 + it * 0.5,
                    100.0 + (it % 2) * 0.05,
                    1f,
                    eventTimeNanos = it * 1_000_000L,
                    toolType = stylus,
                )
            }
        assertNull(QuickShapeSnap.recognize(samples, canvasPerScreenPixel = 1.0))
        val line = QuickShapeSnap.recognize(samples, canvasPerScreenPixel = 0.1)!!.shape as LineShape
        assertEquals(10.0, hypot(line.end.x - line.start.x, line.end.y - line.start.y), 0.1)
        assertNull(QuickShapeSnap.recognize(samples.take(1), 0.1))
    }
}
