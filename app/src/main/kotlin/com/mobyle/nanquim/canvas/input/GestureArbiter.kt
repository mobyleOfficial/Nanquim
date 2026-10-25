package com.mobyle.nanquim.canvas.input

import kotlin.math.abs
import kotlin.math.hypot

class GestureArbiter(
    private val repeats: (Gesture) -> Boolean = { it == Gesture.TwoFingerTap || it == Gesture.ThreeFingerTap },
    private val enabled: (Gesture) -> Boolean = { it != Gesture.FingerDrag },
    private val longPressMillis: () -> Long = { LONG_PRESS_MILLIS },
    private val repeatDelayMillis: () -> Long = { REPEAT_DELAY_MILLIS },
) {
    data class Recognized(
        val gesture: Gesture,
        val x: Float,
        val y: Float,
        val repeat: Boolean = false,
    )

    class Fingers {
        class Finger(
            val startX: Float,
            val startY: Float,
            var x: Float,
            var y: Float,
        )

        val down = mutableMapOf<Int, Finger>()
        var firstDownMillis = 0L
        var maximum = 0
        var lastX = 0f
        var lastY = 0f

        val travel: Float get() = down.values.maxOfOrNull { hypot(it.x - it.startX, it.y - it.startY) } ?: 0f

        val centroidX: Float get() =
            down.values
                .map { it.x }
                .average()
                .toFloat()
        val centroidY: Float get() =
            down.values
                .map { it.y }
                .average()
                .toFloat()
    }

    abstract class Recognizer(
        val gesture: Gesture,
    ) {
        var failed = false
            protected set

        open fun reset() {
            failed = false
        }

        open fun onDown(
            fingers: Fingers,
            timeMillis: Long,
        ): Recognized? = null

        open fun onMove(
            fingers: Fingers,
            timeMillis: Long,
        ): Recognized? = null

        open fun onUp(
            fingers: Fingers,
            timeMillis: Long,
            allUp: Boolean,
        ): Recognized? = null

        open fun onTimer(
            fingers: Fingers,
            timeMillis: Long,
        ): Recognized? = null

        open fun nextCheckMillis(fingers: Fingers): Long? = null
    }

    class Tap(
        gesture: Gesture,
        private val count: Int,
        private val repeatable: () -> Boolean,
        private val repeatDelayMillis: () -> Long = { REPEAT_DELAY_MILLIS },
    ) : Recognizer(gesture) {
        private var repetitions = 0

        override fun reset() {
            super.reset()
            repetitions = 0
        }

        override fun onDown(
            fingers: Fingers,
            timeMillis: Long,
        ): Recognized? {
            if (fingers.maximum > count) failed = true
            return null
        }

        override fun onMove(
            fingers: Fingers,
            timeMillis: Long,
        ): Recognized? {
            if (fingers.travel > TAP_SLOP_PX) failed = true
            return null
        }

        override fun onUp(
            fingers: Fingers,
            timeMillis: Long,
            allUp: Boolean,
        ): Recognized? {
            if (!allUp || failed || repetitions > 0) return null
            if (fingers.maximum != count || timeMillis - fingers.firstDownMillis > TAP_TIMEOUT_MILLIS) return null
            return Recognized(gesture, fingers.lastX, fingers.lastY)
        }

        override fun onTimer(
            fingers: Fingers,
            timeMillis: Long,
        ): Recognized? {
            if (failed || !repeatable() || fingers.down.size != count || fingers.maximum != count) return null
            if (timeMillis - fingers.firstDownMillis < nextRepeatAt()) return null
            repetitions++
            return Recognized(gesture, fingers.lastX, fingers.lastY, repeat = true)
        }

        override fun nextCheckMillis(fingers: Fingers): Long? =
            if (!failed && repeatable() && fingers.down.size == count && fingers.maximum == count) nextRepeatAt() else null

        private fun nextRepeatAt(): Long {
            var at = repeatDelayMillis()
            var interval = FIRST_REPEAT_INTERVAL_MILLIS
            repeat(repetitions) {
                at += interval
                interval = maxOf(MINIMUM_REPEAT_INTERVAL_MILLIS, (interval * REPEAT_ACCELERATION).toLong())
            }
            return at
        }
    }

    class LongPress(
        private val delayMillis: () -> Long = { LONG_PRESS_MILLIS },
    ) : Recognizer(Gesture.LongPress) {
        override fun onDown(
            fingers: Fingers,
            timeMillis: Long,
        ): Recognized? {
            if (fingers.maximum > 1) failed = true
            return null
        }

        override fun onMove(
            fingers: Fingers,
            timeMillis: Long,
        ): Recognized? {
            if (fingers.travel > TAP_SLOP_PX) failed = true
            return null
        }

        override fun onUp(
            fingers: Fingers,
            timeMillis: Long,
            allUp: Boolean,
        ): Recognized? {
            failed = true
            return null
        }

        override fun onTimer(
            fingers: Fingers,
            timeMillis: Long,
        ): Recognized? {
            if (failed || fingers.down.size != 1 || timeMillis - fingers.firstDownMillis < delayMillis()) return null
            return Recognized(Gesture.LongPress, fingers.lastX, fingers.lastY)
        }

        override fun nextCheckMillis(fingers: Fingers): Long? = if (!failed && fingers.down.size == 1) delayMillis() else null
    }

    class Scrub : Recognizer(Gesture.ThreeFingerScrub) {
        private var direction = 0
        private var extremeX = 0f
        private var reversals = 0
        private var startY = 0f
        private var startMillis = 0L

        override fun reset() {
            super.reset()
            direction = 0
            reversals = 0
        }

        override fun onDown(
            fingers: Fingers,
            timeMillis: Long,
        ): Recognized? {
            if (fingers.maximum > 3) failed = true
            if (fingers.down.size == 3) {
                extremeX = fingers.centroidX
                startY = fingers.centroidY
                startMillis = timeMillis
                direction = 0
                reversals = 0
            }
            return null
        }

        override fun onMove(
            fingers: Fingers,
            timeMillis: Long,
        ): Recognized? {
            if (failed || fingers.down.size != 3) return null
            if (timeMillis - startMillis > SCRUB_MILLIS || abs(fingers.centroidY - startY) > SCRUB_VERTICAL_LIMIT_PX) {
                failed = true
                return null
            }
            val x = fingers.centroidX
            val swing = x - extremeX
            when {
                direction == 0 && abs(swing) >= SCRUB_SWING_PX -> {
                    direction = if (swing > 0) 1 else -1
                    extremeX = x
                }
                direction != 0 && swing * direction > 0 -> extremeX = x
                direction != 0 && abs(swing) >= SCRUB_SWING_PX -> {
                    direction = -direction
                    extremeX = x
                    if (++reversals >= SCRUB_REVERSALS) return Recognized(Gesture.ThreeFingerScrub, x, fingers.centroidY)
                }
            }
            return null
        }

        override fun onUp(
            fingers: Fingers,
            timeMillis: Long,
            allUp: Boolean,
        ): Recognized? {
            failed = true
            return null
        }
    }

    class SwipeDown : Recognizer(Gesture.ThreeFingerSwipeDown) {
        private var startX = 0f
        private var startY = 0f
        private var startMillis = 0L

        override fun onDown(
            fingers: Fingers,
            timeMillis: Long,
        ): Recognized? {
            if (fingers.maximum > 3) failed = true
            if (fingers.down.size == 3) {
                startX = fingers.centroidX
                startY = fingers.centroidY
                startMillis = timeMillis
            }
            return null
        }

        override fun onMove(
            fingers: Fingers,
            timeMillis: Long,
        ): Recognized? {
            if (failed || fingers.down.size != 3) return null
            val dx = fingers.centroidX - startX
            val dy = fingers.centroidY - startY
            if (timeMillis - startMillis > SWIPE_MILLIS || dy < -TAP_SLOP_PX || abs(dx) > SWIPE_DISTANCE_PX) {
                failed = true
                return null
            }
            if (dy >= SWIPE_DISTANCE_PX &&
                abs(dx) <= dy * SWIPE_SLOPE
            ) {
                return Recognized(Gesture.ThreeFingerSwipeDown, fingers.centroidX, fingers.centroidY)
            }
            return null
        }

        override fun onUp(
            fingers: Fingers,
            timeMillis: Long,
            allUp: Boolean,
        ): Recognized? {
            failed = true
            return null
        }
    }

    class FingerDrag(
        private val waitsForLongPress: () -> Boolean,
    ) : Recognizer(Gesture.FingerDrag) {
        override fun onDown(
            fingers: Fingers,
            timeMillis: Long,
        ): Recognized? {
            if (fingers.maximum > 1) failed = true
            return null
        }

        override fun onMove(
            fingers: Fingers,
            timeMillis: Long,
        ): Recognized? {
            if (failed || fingers.down.size != 1 || fingers.travel < FINGER_DRAG_SLOP_PX) return null
            if (timeMillis - fingers.firstDownMillis < FINGER_DRAG_MINIMUM_MILLIS) return null
            return Recognized(Gesture.FingerDrag, fingers.lastX, fingers.lastY)
        }

        override fun onUp(
            fingers: Fingers,
            timeMillis: Long,
            allUp: Boolean,
        ): Recognized? {
            failed = true
            return null
        }

        override fun onTimer(
            fingers: Fingers,
            timeMillis: Long,
        ): Recognized? {
            if (failed || fingers.down.size != 1) return null
            val held = timeMillis - fingers.firstDownMillis
            val moved = fingers.travel >= FINGER_DRAG_SLOP_PX && held >= FINGER_DRAG_MINIMUM_MILLIS
            if (!moved && (waitsForLongPress() || held < FINGER_DRAG_MILLIS)) return null
            return Recognized(Gesture.FingerDrag, fingers.lastX, fingers.lastY)
        }

        override fun nextCheckMillis(fingers: Fingers): Long? =
            when {
                failed || fingers.down.size != 1 -> null
                fingers.travel >= FINGER_DRAG_SLOP_PX -> FINGER_DRAG_MINIMUM_MILLIS
                waitsForLongPress() -> null
                else -> FINGER_DRAG_MILLIS
            }
    }

    private val fingers = Fingers()
    private val recognizers: List<Recognizer> =
        listOf(
            Tap(Gesture.OneFingerTap, 1, { false }),
            Tap(Gesture.TwoFingerTap, 2, { repeats(Gesture.TwoFingerTap) }, repeatDelayMillis),
            Tap(Gesture.ThreeFingerTap, 3, { repeats(Gesture.ThreeFingerTap) }, repeatDelayMillis),
            Tap(Gesture.FourFingerTap, 4, { repeats(Gesture.FourFingerTap) }, repeatDelayMillis),
            LongPress(longPressMillis),
            Scrub(),
            SwipeDown(),
            FingerDrag { enabled(Gesture.LongPress) },
        )

    private var winner: Recognizer? = null
    private var canceled = false

    val decided: Boolean get() = winner != null

    fun fingerDown(
        pointerId: Int,
        x: Float,
        y: Float,
        timeMillis: Long,
    ): Recognized? {
        if (fingers.down.isEmpty()) {
            fingers.firstDownMillis = timeMillis
            fingers.maximum = 0
            winner = null
            canceled = false
            recognizers.forEach { it.reset() }
        }
        fingers.down[pointerId] = Fingers.Finger(x, y, x, y)
        fingers.maximum = maxOf(fingers.maximum, fingers.down.size)
        fingers.lastX = x
        fingers.lastY = y
        return dispatch { it.onDown(fingers, timeMillis) }
    }

    fun fingerMoved(
        pointerId: Int,
        x: Float,
        y: Float,
        timeMillis: Long,
    ): Recognized? {
        val finger = fingers.down[pointerId] ?: return null
        finger.x = x
        finger.y = y
        fingers.lastX = x
        fingers.lastY = y
        return dispatch { it.onMove(fingers, timeMillis) }
    }

    fun fingerUp(
        pointerId: Int,
        timeMillis: Long,
    ): Recognized? {
        if (fingers.down.remove(pointerId) == null) return null
        val allUp = fingers.down.isEmpty()
        return dispatch { it.onUp(fingers, timeMillis, allUp) }
    }

    fun cancel() {
        fingers.down.clear()
        canceled = true
    }

    fun onTimer(timeMillis: Long): Recognized? = dispatch { it.onTimer(fingers, timeMillis) }

    fun nextCheckMillis(): Long? {
        if (canceled || fingers.down.isEmpty()) return null
        val candidates = winner?.let { listOfNotNull(it as? Tap) } ?: recognizers.filter { !it.failed && enabled(it.gesture) }
        return candidates.mapNotNull { it.nextCheckMillis(fingers) }.minOrNull()?.let { fingers.firstDownMillis + it }
    }

    private fun dispatch(step: (Recognizer) -> Recognized?): Recognized? {
        if (canceled) return null
        winner?.let { return if (it is Tap) step(it) else null }
        for (recognizer in recognizers) {
            if (recognizer.failed || !enabled(recognizer.gesture)) continue
            val recognized = step(recognizer) ?: continue
            winner = recognizer
            return recognized
        }
        return null
    }

    companion object {
        const val DEFAULT_UNDO_FINGERS = 2
        const val TAP_TIMEOUT_MILLIS = 300L
        const val LONG_PRESS_MILLIS = 300L
        const val TAP_SLOP_PX = 24f
        const val REPEAT_DELAY_MILLIS = 500L
        const val FIRST_REPEAT_INTERVAL_MILLIS = 300L
        const val MINIMUM_REPEAT_INTERVAL_MILLIS = 100L
        const val REPEAT_ACCELERATION = 0.75
        const val SCRUB_SWING_PX = 40f
        const val SCRUB_REVERSALS = 3
        const val SCRUB_MILLIS = 1500L
        const val SCRUB_VERTICAL_LIMIT_PX = 150f
        const val SWIPE_DISTANCE_PX = 150f
        const val SWIPE_MILLIS = 600L
        const val SWIPE_SLOPE = 0.5f
        const val FINGER_DRAG_SLOP_PX = 10f
        const val FINGER_DRAG_MILLIS = 150L
        const val FINGER_DRAG_MINIMUM_MILLIS = 100L
    }
}
