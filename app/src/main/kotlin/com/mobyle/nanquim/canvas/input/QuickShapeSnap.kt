package com.mobyle.nanquim.canvas.input

import com.mobyle.nanquim.document.shape.QuickShape
import com.mobyle.nanquim.document.shape.QuickShapeSettings
import com.mobyle.nanquim.document.shape.QuickShapeStroke
import com.mobyle.nanquim.document.shape.RecognizedShape
import com.mobyle.nanquim.document.shape.ShapePoint
import com.mobyle.nanquim.engine.StrokeSample
import com.mobyle.nanquim.engine.StrokeSampleBuffer
import kotlin.math.floor

class QuickShapeMorph(
    val shape: RecognizedShape,
    val frames: List<StrokeSampleBuffer>,
    val end: ShapePoint,
)

class QuickShapeSnap(
    private val samples: List<StrokeSample>,
    val shape: RecognizedShape,
) {
    private val strokePoints = samples.map { ShapePoint(it.x, it.y) }

    var constrained: Boolean = false
        private set

    val current: RecognizedShape get() = if (constrained) QuickShapeStroke.constrained(shape) else shape

    private var shown: List<ShapePoint> = strokePoints

    fun morph(
        constrain: Boolean,
        frameCount: Int = MORPH_FRAMES,
    ): QuickShapeMorph? {
        val first = shown === strokePoints
        if (!first && constrain == constrained) return null
        constrained = constrain
        val target = QuickShapeStroke.strokeOutline(current)
        if (!first && target == shown) return null
        val from = QuickShapeStroke.matching(shown, target)
        val profile = profileOn(target)
        val frames =
            (1..frameCount).map { frame ->
                val t = frame.toDouble() / frameCount
                val eased = t * t * (3 - 2 * t)
                val points = if (frame == frameCount) target else target.indices.map { from[it] + (target[it] - from[it]) * eased }
                samplesAlong(points, profile)
            }
        shown = target
        return QuickShapeMorph(current, frames, target.last())
    }

    fun samplesFor(shape: RecognizedShape): StrokeSampleBuffer {
        val outline = QuickShapeStroke.strokeOutline(shape)
        return samplesAlong(outline, profileOn(outline))
    }

    private class Profile(
        val pressure: FloatArray,
        val tilt: FloatArray,
        val orientation: FloatArray,
        val time: LongArray,
    )

    private fun profileOn(outline: List<ShapePoint>): Profile {
        val positions = QuickShapeStroke.profile(strokePoints, outline)
        val profile = Profile(FloatArray(outline.size), FloatArray(outline.size), FloatArray(outline.size), LongArray(outline.size))
        for (index in outline.indices) {
            val position = positions[index]
            val below = floor(position).toInt().coerceIn(0, samples.size - 1)
            val above = (below + 1).coerceAtMost(samples.size - 1)
            val within = (position - below).toFloat()
            val a = samples[below]
            val b = samples[above]
            profile.pressure[index] = a.pressure + (b.pressure - a.pressure) * within
            profile.tilt[index] = a.tilt + (b.tilt - a.tilt) * within
            profile.orientation[index] = a.orientation + (b.orientation - a.orientation) * within
            profile.time[index] = a.eventTimeNanos + ((b.eventTimeNanos - a.eventTimeNanos) * within.toDouble()).toLong()
        }
        return profile
    }

    private fun samplesAlong(
        points: List<ShapePoint>,
        profile: Profile,
    ): StrokeSampleBuffer {
        val buffer = StrokeSampleBuffer(points.size)
        val first = samples.first()
        for ((index, point) in points.withIndex()) {
            buffer.add(
                x = point.x.toFloat(),
                y = point.y.toFloat(),
                pressure = profile.pressure[index],
                tilt = profile.tilt[index],
                orientation = profile.orientation[index],
                eventTimeNanos = profile.time[index],
                toolType = first.toolType,
                buttonState = first.buttonState,
                pointerId = first.pointerId,
            )
        }
        return buffer
    }

    companion object {
        const val MORPH_MILLIS = 150L
        const val MORPH_FRAMES = 9

        const val STILL_DISTANCE_PX = 4.0

        const val MINIMUM_SIZE_PX = 12.0

        fun recognize(
            samples: List<StrokeSample>,
            canvasPerScreenPixel: Double,
        ): QuickShapeSnap? {
            if (samples.size < 2) return null
            val settings = QuickShapeSettings(minimumSize = MINIMUM_SIZE_PX * canvasPerScreenPixel)
            val shape = QuickShape.recognize(samples.map { ShapePoint(it.x, it.y) }, settings).shape ?: return null
            return QuickShapeSnap(samples, shape)
        }
    }
}
