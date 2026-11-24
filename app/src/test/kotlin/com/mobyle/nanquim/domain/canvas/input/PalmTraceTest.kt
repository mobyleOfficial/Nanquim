package com.mobyle.nanquim.domain.canvas.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class PalmTraceTest {
    private fun set(name: String) = TouchSessionSet.decode(File("src/test/resources/palm-traces/$name").readText())

    private fun replay(
        set: TouchSessionSet,
        session: TouchSession,
        undoFingers: Int = 2,
    ) = TouchReplay(FingerInput(undoFingers = { undoFingers }, millimetresPerPixel = set.millimetres(1f))).replay(session.events)

    @Test
    fun palmsRestingWhileDrawingCauseNoGestures() {
        val set = set("tab-s7-fe-palm-1.json")
        val (first, second) = set.sessions
        assertEquals(
            TouchReplay.Outcome(strokes = 83),
            replay(set, first),
        )
        assertEquals(TouchReplay.Outcome(strokes = 22), replay(set, second))
        assertEquals(0, set.sessions.sumOf { replay(set, it, undoFingers = 1).undos })
    }

    @Test
    fun palmsDoNotPaintOrTriggerGesturesWithFingerPainting() {
        val set = set("tab-s7-fe-palm-1.json")
        val defaults = GestureBindings.defaults(fingerPainting = true)
        val bindings = defaults.copy(actions = defaults.actions + (Gesture.OneFingerTap to GestureAction.QuickMenu))
        val outcomes =
            set.sessions.map {
                TouchReplay(FingerInput(millimetresPerPixel = set.millimetres(1f), bindings = { bindings })).replay(it.events)
            }
        assertEquals(listOf(0, 0), outcomes.map { it.fingerStrokes })
        assertEquals(listOf(0, 0), outcomes.map { it.actions.values.sum() + it.undos + it.redos + it.eyedroppers })
        assertEquals(listOf(0, 0), outcomes.map { it.cameraMoves })
    }
}
