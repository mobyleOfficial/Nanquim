package com.mobyle.nanquim.canvas.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TouchReplayTest {
    private class Script {
        val events = mutableListOf<TouchEvent>()
        private val down = linkedMapOf<Int, TouchPointer>()

        fun down(
            time: Long,
            pointer: TouchPointer,
        ) {
            down[pointer.id] = pointer
            val pointers = down.values.toList()
            events += TouchEvent(if (pointers.size == 1) TouchAction.Down else TouchAction.PointerDown, pointers.lastIndex, time, pointers)
        }

        fun move(
            time: Long,
            vararg moved: TouchPointer,
        ) {
            moved.forEach { down[it.id] = it }
            events += TouchEvent(TouchAction.Move, 0, time, down.values.toList())
        }

        fun up(
            time: Long,
            id: Int,
            canceled: Boolean = false,
        ) {
            val pointers = down.values.toList()
            val index = pointers.indexOfFirst { it.id == id }
            events += TouchEvent(if (pointers.size == 1) TouchAction.Up else TouchAction.PointerUp, index, time, pointers, canceled)
            down.remove(id)
        }

        fun cancel(time: Long) {
            events += TouchEvent(TouchAction.Cancel, 0, time, down.values.toList())
            down.clear()
        }

        fun hover(
            time: Long,
            action: TouchAction,
            x: Float,
            y: Float,
        ) {
            events += TouchEvent(action, 0, time, listOf(TouchPointer(STYLUS_ID, PointerTool.Stylus, x, y)))
        }
    }

    private fun replay(
        events: List<TouchEvent>,
        support: PalmSupport = PalmSupport.Medium,
        undoFingers: Int = 2,
    ) = TouchReplay(
        FingerInput(support = { support }, undoFingers = { undoFingers }, millimetresPerPixel = MILLIMETRES_PER_INCH / XDPI),
    ).replay(events)

    private fun finger(
        id: Int,
        x: Float,
        y: Float,
        major: Float = 60f,
    ) = TouchPointer(id, PointerTool.Finger, x, y, major, major * 0.8f)

    private fun stylus(
        x: Float,
        y: Float,
    ) = TouchPointer(STYLUS_ID, PointerTool.Stylus, x, y)

    @Test
    fun intentionalGesturesAreRecognized() {
        val tap =
            Script().apply {
                down(0, finger(0, 500f, 500f))
                down(20, finger(1, 700f, 500f))
                up(120, 0)
                up(130, 1)
                down(1000, finger(2, 500f, 500f))
                down(1010, finger(3, 600f, 500f))
                down(1020, finger(4, 700f, 500f))
                up(1100, 2)
                up(1110, 3)
                up(1120, 4)
            }
        assertEquals(TouchReplay.Outcome(navigations = 2, undos = 1, redos = 1), replay(tap.events))
        val pinch =
            Script().apply {
                down(0, finger(0, 500f, 500f))
                down(15, finger(1, 700f, 500f))
                for (step in 1..10) move(15L + step * 16, finger(0, 500f - step * 10, 500f), finger(1, 700f + step * 10, 500f))
                up(200, 0)
                up(210, 1)
            }
        assertEquals(TouchReplay.Outcome(navigations = 1, cameraMoves = 10), replay(pinch.events))
        val hold =
            Script().apply {
                down(0, finger(0, 500f, 500f))
                up(800, 0)
            }
        assertEquals(TouchReplay.Outcome(eyedroppers = 1), replay(hold.events))
    }

    @Test
    fun undoCanBeAOneFingerTap() {
        val oneTap =
            Script().apply {
                down(0, finger(0, 500f, 500f))
                up(120, 0)
            }
        assertEquals(TouchReplay.Outcome(undos = 1), replay(oneTap.events, undoFingers = 1))
        assertEquals(TouchReplay.Outcome(), replay(oneTap.events, undoFingers = 2))
        val twoTap =
            Script().apply {
                down(0, finger(0, 500f, 500f))
                down(20, finger(1, 700f, 500f))
                up(120, 0)
                up(130, 1)
            }
        assertEquals(0, replay(twoTap.events, undoFingers = 1).undos)
        val threeTap =
            Script().apply {
                down(0, finger(0, 500f, 500f))
                down(10, finger(1, 600f, 500f))
                down(20, finger(2, 700f, 500f))
                up(100, 0)
                up(110, 1)
                up(120, 2)
            }
        assertEquals(1, replay(threeTap.events, undoFingers = 1).redos)
        val hold =
            Script().apply {
                down(0, finger(0, 500f, 500f))
                up(800, 0)
            }
        assertEquals(TouchReplay.Outcome(eyedroppers = 1), replay(hold.events, undoFingers = 1))
        val slide =
            Script().apply {
                down(0, finger(0, 500f, 500f))
                move(60, finger(0, 560f, 500f))
                up(120, 0)
            }
        assertEquals(0, replay(slide.events, undoFingers = 1).undos)
        val afterPen =
            Script().apply {
                down(0, stylus(300f, 300f))
                up(200, STYLUS_ID)
                down(350, finger(0, 500f, 500f))
                up(420, 0)
            }
        assertEquals(TouchReplay.Outcome(strokes = 1), replay(afterPen.events, undoFingers = 1))
    }

    @Test
    fun aPalmRestingWhileThePenIsNearIsIgnored() {
        val session =
            Script().apply {
                hover(0, TouchAction.HoverEnter, 900f, 900f)
                hover(40, TouchAction.HoverExit, 905f, 905f)
                down(50, stylus(910f, 910f))
                down(60, finger(0, 1200f, 1300f, major = 220f))
                for (step in 1..20) move(60L + step * 8, stylus(910f + step * 5, 910f), finger(0, 1200f, 1300f, major = 220f))
                up(400, STYLUS_ID)
                up(900, 0)
            }
        assertEquals(TouchReplay.Outcome(strokes = 1), replay(session.events))
    }

    @Test
    fun aPenAfterARestingPalmDraws() {
        val session =
            Script().apply {
                hover(0, TouchAction.HoverEnter, 900f, 900f)
                down(60, finger(0, 1200f, 1300f, major = 220f))
                hover(80, TouchAction.HoverExit, 910f, 910f)
                down(90, stylus(910f, 910f))
                for (step in 1..20) move(90L + step * 8, stylus(910f + step * 5, 910f), finger(0, 1200f, 1300f, major = 220f))
                up(400, STYLUS_ID)
                up(900, 0)
            }
        assertEquals(TouchReplay.Outcome(strokes = 1), replay(session.events))
    }

    @Test
    fun aLargeContactBeforeThePenIsAPalm() {
        val session =
            Script().apply {
                down(0, finger(0, 1200f, 1300f, major = 220f))
                move(150, finger(0, 1203f, 1301f, major = 230f))
                hover(350, TouchAction.HoverEnter, 900f, 900f)
            }
        assertEquals(TouchReplay.Outcome(), replay(session.events))
        assertEquals(1, replay(session.events, PalmSupport.Off).eyedroppers)
        val finger = Script().apply { down(0, finger(0, 500f, 500f, major = 77f)) }
        assertEquals(1, replay(finger.events, PalmSupport.High).eyedroppers)
        val unreported = Script().apply { down(0, finger(0, 500f, 500f, major = 0f)) }
        assertEquals(1, replay(unreported.events).eyedroppers)
    }

    @Test
    fun aContactGrowingToPalmSizeCancelsItsNavigation() {
        val session =
            Script().apply {
                down(0, finger(0, 500f, 500f, major = 70f))
                down(10, finger(1, 700f, 500f, major = 70f))
                move(26, finger(0, 510f, 500f, major = 70f), finger(1, 710f, 500f, major = 70f))
                move(42, finger(0, 520f, 500f, major = 70f), finger(1, 720f, 500f, major = 70f))
                move(58, finger(0, 530f, 500f, major = 70f), finger(1, 730f, 500f, major = 240f))
                up(300, 1)
                up(310, 0)
            }
        assertEquals(TouchReplay.Outcome(navigations = 1, canceledMoves = 2), replay(session.events))
    }

    @Test
    fun theSystemsPalmsAndCanceledPointersCancelTheirGesture() {
        val system =
            Script().apply {
                down(0, finger(0, 500f, 500f))
                down(10, finger(1, 700f, 500f))
                move(26, finger(0, 520f, 500f), finger(1, 720f, 500f))
                move(42, finger(0, 540f, 500f), TouchPointer(1, PointerTool.Palm, 740f, 500f))
                cancel(60)
            }
        assertEquals(TouchReplay.Outcome(navigations = 1, canceledMoves = 1), replay(system.events))
        val canceled =
            Script().apply {
                down(0, finger(0, 500f, 500f))
                down(20, finger(1, 700f, 500f))
                up(100, 1, canceled = true)
                up(120, 0)
            }
        assertEquals(0, replay(canceled.events).undos)
    }

    @Test
    fun aLateStylusUndoesWhatTheFingersJustBegan() {
        val session =
            Script().apply {
                down(0, finger(0, 500f, 500f))
                down(10, finger(1, 700f, 500f))
                for (step in 1..10) move(10L + step * 16, finger(0, 500f + step * 5, 500f), finger(1, 700f + step * 5, 500f))
                hover(200, TouchAction.HoverEnter, 300f, 300f)
                up(400, 0)
                up(410, 1)
            }
        assertEquals(TouchReplay.Outcome(navigations = 1, canceledMoves = 10), replay(session.events))
        assertEquals(TouchReplay.Outcome(navigations = 1, cameraMoves = 10), replay(session.events, PalmSupport.Off))
        val older =
            Script().apply {
                down(0, finger(0, 500f, 500f))
                down(10, finger(1, 700f, 500f))
                for (step in 1..30) move(10L + step * 16, finger(0, 500f + step * 5, 500f), finger(1, 700f + step * 5, 500f))
                hover(600, TouchAction.HoverEnter, 300f, 300f)
                up(700, 0)
                up(710, 1)
            }
        assertEquals(TouchReplay.Outcome(navigations = 1, cameraMoves = 30), replay(older.events))
        val tap =
            Script().apply {
                down(0, finger(0, 500f, 500f))
                down(20, finger(1, 700f, 500f))
                hover(60, TouchAction.HoverEnter, 300f, 300f)
                up(100, 0)
                up(110, 1)
            }
        assertEquals(0, replay(tap.events).undos)
    }

    @Test
    fun sessionsRoundTripThroughJson() {
        val script =
            Script().apply {
                hover(0, TouchAction.HoverEnter, 10f, 20f)
                down(5, finger(0, 100.25f, 200f, major = 61.37f))
                down(9, stylus(300f, 400f))
                up(30, 0, canceled = true)
                up(40, STYLUS_ID)
            }
        val set =
            TouchSessionSet(
                "Tab S7 FE",
                xdpi = 274.5f,
                sessions = listOf(TouchSession(TouchSessionSet.LABEL_PALM, "A", script.events)),
            )
        val read = TouchSessionSet.decode(TouchSessionSet.encode(set))
        assertEquals("Tab S7 FE", read.device)
        assertEquals(274.5f, read.xdpi)
        val events = read.sessions.single().events
        assertEquals(script.events.map { it.action }, events.map { it.action })
        assertEquals(script.events.map { it.timeMillis }, events.map { it.timeMillis })
        assertEquals(script.events.map { it.canceled }, events.map { it.canceled })
        assertEquals(100.3f, events[1].pointers[0].x, 1e-4f)
        assertEquals(61.4f, events[1].pointers[0].touchMajor, 1e-4f)
        assertEquals(PointerTool.Stylus, events[2].actionPointer.tool)
        assertEquals(5.66f, read.millimetres(61.2f), 0.01f)
    }

    private fun replayWith(
        events: List<TouchEvent>,
        bindings: GestureBindings,
    ) = TouchReplay(FingerInput(millimetresPerPixel = MILLIMETRES_PER_INCH / XDPI, bindings = { bindings })).replay(events)

    @Test
    fun quickPinchFourFingersAndThreeFingerGestures() {
        val quickPinch =
            Script().apply {
                down(0, finger(0, 300f, 500f))
                down(10, finger(1, 900f, 500f))
                for (step in 1..8) move(10L + step * 16, finger(0, 300f + step * 30, 500f), finger(1, 900f - step * 30, 500f))
                up(160, 0)
                up(170, 1)
            }
        val outcome = replayWith(quickPinch.events, GestureBindings.defaults())
        assertEquals(mapOf(GestureAction.FitCanvas to 1), outcome.actions)
        assertEquals(0, outcome.cameraMoves)
        val fourTap =
            Script().apply {
                for (finger in 0 until 4) down(finger * 10L, finger(finger, 300f + finger * 100, 500f))
                for (finger in 0 until 4) up(120L + finger * 5, finger)
            }
        assertEquals(mapOf(GestureAction.FullScreen to 1), replayWith(fourTap.events, GestureBindings.defaults()).actions)
        val swipeDown =
            Script().apply {
                for (finger in 0 until 3) down(finger * 5L, finger(finger, 400f + finger * 100, 300f))
                for (step in 1..8) move(10L + step * 16, *Array(3) { finger(it, 400f + it * 100, 300f + step * 30) })
                for (finger in 0 until 3) up(200L + finger, finger)
            }
        val swiped = replayWith(swipeDown.events, GestureBindings.defaults())
        assertEquals(mapOf(GestureAction.CopyPasteMenu to 1), swiped.actions)
        assertEquals(0, swiped.redos)
        val scrub =
            Script().apply {
                for (finger in 0 until 3) down(finger * 5L, finger(finger, 400f + finger * 100, 300f))
                for (step in 1..8) {
                    val offset = if (step % 2 == 1) 60f else 0f
                    move(10L + step * 60, *Array(3) { finger(it, 400f + it * 100 + offset, 300f) })
                }
                for (finger in 0 until 3) up(600L + finger, finger)
            }
        assertEquals(mapOf(GestureAction.ClearLayer to 1), replayWith(scrub.events, GestureBindings.defaults()).actions)
    }

    @Test
    fun aFingerPaintsOnlyWhenAllowedAndAlone() {
        val drag =
            Script().apply {
                down(0, finger(0, 500f, 500f))
                for (step in 1..10) move(step * 16L, finger(0, 500f + step * 8, 500f))
                up(200, 0)
            }
        assertEquals(
            TouchReplay.Outcome(strokes = 1, fingerStrokes = 1),
            replayWith(drag.events, GestureBindings.defaults(fingerPainting = true)),
        )
        assertEquals(TouchReplay.Outcome(), replayWith(drag.events, GestureBindings.defaults()))
        val tap =
            Script().apply {
                down(0, finger(0, 500f, 500f))
                move(30, finger(0, 508f, 500f))
                down(40, finger(1, 700f, 500f))
                up(120, 0)
                up(130, 1)
            }
        assertEquals(
            TouchReplay.Outcome(navigations = 1, undos = 1),
            replayWith(tap.events, GestureBindings.defaults(fingerPainting = true)),
        )
    }

    @Test
    fun thePenCanStopFingerStrokes() {
        val penThenFinger =
            Script().apply {
                down(0, stylus(300f, 300f))
                up(200, STYLUS_ID)
                down(1000, finger(0, 500f, 500f))
                for (step in 1..10) move(1000L + step * 16, finger(0, 500f + step * 8, 500f))
                up(1200, 0)
            }
        val painting = GestureConfiguration.defaults(fingerPainting = true)
        assertEquals(1, replayWith(penThenFinger.events, painting.bindings).fingerStrokes)
        val stopped = painting.copy(stylusDisablesFingerStrokes = true)
        assertEquals(0, replayWith(penThenFinger.events, stopped.bindings).fingerStrokes)
    }

    private companion object {
        const val STYLUS_ID = 9
        const val XDPI = 244.8f
        const val MILLIMETRES_PER_INCH = 25.4f
    }
}
