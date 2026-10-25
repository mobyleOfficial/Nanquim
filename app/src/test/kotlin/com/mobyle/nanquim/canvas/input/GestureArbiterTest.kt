package com.mobyle.nanquim.canvas.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GestureArbiterTest {
    private val everything: (Gesture) -> Boolean = { true }

    private fun arbiter(enabled: (Gesture) -> Boolean = everything) = GestureArbiter(enabled = enabled)

    private fun GestureArbiter.timersUntil(untilMillis: Long): List<GestureArbiter.Recognized> {
        val recognized = mutableListOf<GestureArbiter.Recognized>()
        while (true) {
            val due = nextCheckMillis() ?: break
            if (due > untilMillis) break
            val result = onTimer(due) ?: break
            recognized += result
        }
        return recognized
    }

    @Test
    fun tapsOfOneToFourFingers() {
        for ((count, gesture) in listOf(
            1 to Gesture.OneFingerTap,
            2 to Gesture.TwoFingerTap,
            3 to Gesture.ThreeFingerTap,
            4 to Gesture.FourFingerTap,
        )) {
            val arbiter = arbiter()
            for (finger in 0 until count) assertNull(arbiter.fingerDown(finger, 100f + finger * 80f, 300f, finger * 10L))
            val results = (0 until count).map { arbiter.fingerUp(it, 120L + it) }
            assertEquals(listOf(gesture), results.filterNotNull().map { it.gesture }, "$count fingers")
        }
    }

    @Test
    fun aSlowOrMovedTapIsNoTap() {
        val slow = arbiter()
        slow.fingerDown(0, 100f, 100f, 0)
        slow.fingerDown(1, 200f, 100f, 5)
        slow.fingerUp(0, GestureArbiter.TAP_TIMEOUT_MILLIS + 50)
        assertNull(slow.fingerUp(1, GestureArbiter.TAP_TIMEOUT_MILLIS + 60))

        val moved = arbiter()
        moved.fingerDown(0, 100f, 100f, 0)
        moved.fingerDown(1, 200f, 100f, 5)
        moved.fingerMoved(1, 260f, 100f, 50)
        moved.fingerUp(0, 80)
        assertNull(moved.fingerUp(1, 90))
    }

    @Test
    fun longPressAndRepeatDelaysAreConfigurable() {
        val slow = GestureArbiter(enabled = everything, longPressMillis = { 800L }, repeatDelayMillis = { 1000L })
        slow.fingerDown(0, 100f, 100f, 0)
        assertEquals(800L, slow.nextCheckMillis())
        assertEquals(emptyList<GestureArbiter.Recognized>(), slow.timersUntil(799))
        assertEquals(listOf(Gesture.LongPress), slow.timersUntil(800).map { it.gesture })

        val repeating = GestureArbiter(enabled = everything, repeats = { true }, repeatDelayMillis = { 1000L })
        repeating.fingerDown(0, 100f, 100f, 0)
        repeating.fingerDown(1, 200f, 100f, 5)
        assertEquals(emptyList<GestureArbiter.Recognized>(), repeating.timersUntil(999))
        assertEquals(Gesture.TwoFingerTap, repeating.timersUntil(1000).single().gesture)
    }

    @Test
    fun heldTapsRepeatAndAccelerate() {
        val arbiter = arbiter()
        arbiter.fingerDown(0, 100f, 100f, 0)
        arbiter.fingerDown(1, 200f, 100f, 0)
        val repeats = arbiter.timersUntil(2_000)
        assertTrue(repeats.all { it.gesture == Gesture.TwoFingerTap && it.repeat })
        assertEquals(11, repeats.size)
        arbiter.fingerUp(0, 2_001)
        assertNull(arbiter.fingerUp(1, 2_002))
    }

    @Test
    fun aHeldFingerIsTheLongPress() {
        val arbiter = arbiter()
        arbiter.fingerDown(0, 100f, 100f, 0)
        val recognized = arbiter.timersUntil(1_000)
        assertEquals(listOf(Gesture.LongPress), recognized.map { it.gesture })
        assertTrue(arbiter.decided)
    }

    @Test
    fun threeFingerScrubAndSwipeDown() {
        val scrub = arbiter()
        for (finger in 0 until 3) scrub.fingerDown(finger, 300f + finger * 60f, 400f, 0)
        var result: GestureArbiter.Recognized? = null
        for (step in 1..8) {
            val offset = if (step % 2 == 1) 60f else 0f
            for (finger in 0 until 3) result = scrub.fingerMoved(finger, 300f + finger * 60f + offset, 400f, step * 80L) ?: result
        }
        assertEquals(Gesture.ThreeFingerScrub, result?.gesture)

        val swipe = arbiter()
        for (finger in 0 until 3) swipe.fingerDown(finger, 300f + finger * 60f, 200f, 0)
        var swiped: GestureArbiter.Recognized? = null
        for (step in 1..6) {
            for (finger in 0 until 3) swiped = swipe.fingerMoved(finger, 300f + finger * 60f, 200f + step * 40f, step * 40L) ?: swiped
        }
        assertEquals(Gesture.ThreeFingerSwipeDown, swiped?.gesture)
        for (finger in 0 until 3) assertNull(swipe.fingerUp(finger, 300))
    }

    @Test
    fun aFingerPaintsAfterTenPixelsAndOnlyAlone() {
        val arbiter = arbiter()
        arbiter.fingerDown(0, 100f, 100f, 0)
        assertNull(arbiter.fingerMoved(0, 106f, 100f, 16))
        assertNull(arbiter.fingerMoved(0, 120f, 100f, 50))
        assertEquals(Gesture.FingerDrag, arbiter.fingerMoved(0, 130f, 100f, 110)?.gesture)

        val tap = arbiter()
        tap.fingerDown(0, 100f, 100f, 0)
        tap.fingerMoved(0, 104f, 100f, 16)
        tap.fingerDown(1, 300f, 100f, 30)
        assertNull(tap.fingerMoved(0, 110f, 100f, 48))
        tap.fingerUp(0, 90)
        assertEquals(Gesture.TwoFingerTap, tap.fingerUp(1, 100)?.gesture)
    }

    @Test
    fun aStillFingerPaintsAfter150MillisecondsWithoutALongPress() {
        val noLongPress = arbiter { it != Gesture.LongPress }
        noLongPress.fingerDown(0, 100f, 100f, 0)
        val recognized = noLongPress.timersUntil(1_000)
        assertEquals(listOf(Gesture.FingerDrag), recognized.map { it.gesture })
        assertEquals(GestureArbiter.FINGER_DRAG_MILLIS, 150L)

        val withLongPress = arbiter()
        withLongPress.fingerDown(0, 100f, 100f, 0)
        assertEquals(listOf(Gesture.LongPress), withLongPress.timersUntil(1_000).map { it.gesture })
    }

    @Test
    fun disabledGesturesAreNotRecognized() {
        val arbiter = arbiter { it != Gesture.FourFingerTap && it != Gesture.FingerDrag }
        for (finger in 0 until 4) arbiter.fingerDown(finger, 100f + finger * 80f, 300f, 0)
        assertTrue((0 until 4).mapNotNull { arbiter.fingerUp(it, 100) }.isEmpty())

        val defaults = GestureArbiter()
        defaults.fingerDown(0, 100f, 100f, 0)
        assertNull(defaults.fingerMoved(0, 200f, 100f, 50))
    }

    @Test
    fun cancelDropsEverything() {
        val arbiter = arbiter()
        arbiter.fingerDown(0, 100f, 100f, 0)
        arbiter.fingerDown(1, 200f, 100f, 0)
        arbiter.cancel()
        assertNull(arbiter.nextCheckMillis())
        assertNull(arbiter.fingerUp(0, 50))
        assertFalse(arbiter.decided)
    }

    @Test
    fun stylusButtonPressesAndDoublePresses() {
        val button = StylusButtonGestures()
        assertEquals(StylusButtonGestures.Change.Pressed, button.update(true, 0))
        assertNull(button.update(true, 10))
        assertEquals(StylusButtonGestures.Change.Released, button.update(false, 100))
        assertEquals(StylusButtonGestures.Change.DoublePressed, button.update(true, 200))
        assertTrue(button.pressed)
        button.update(false, 260)
        assertEquals(StylusButtonGestures.Change.Pressed, button.update(true, 320))
        button.update(false, 400)
        assertEquals(StylusButtonGestures.Change.Pressed, button.update(true, 1_000))
    }
}
