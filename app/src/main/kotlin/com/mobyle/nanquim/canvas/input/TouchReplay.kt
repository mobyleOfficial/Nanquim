package com.mobyle.nanquim.canvas.input

class TouchReplay(
    private val fingers: FingerInput = FingerInput(),
) {
    data class Outcome(
        val strokes: Int = 0,
        val navigations: Int = 0,
        val cameraMoves: Int = 0,
        val canceledMoves: Int = 0,
        val undos: Int = 0,
        val redos: Int = 0,
        val eyedroppers: Int = 0,
        val actions: Map<GestureAction, Int> = emptyMap(),
        val fingerStrokes: Int = 0,
    ) {
        val gestureEffects: Int get() = cameraMoves + undos + redos + eyedroppers + actions.values.sum() + fingerStrokes
    }

    private var outcome = Outcome()
    private var pendingMoves = 0
    private var strokePointer: Int? = null
    private var eyedropperPointer: Int? = null

    fun replay(events: List<TouchEvent>): Outcome {
        for (event in events) {
            runTimers(event.timeMillis)
            dispatch(event)
        }
        events.lastOrNull()?.let { runTimers(it.timeMillis + SETTLE_MILLIS) }
        return outcome
    }

    private fun dispatch(event: TouchEvent) {
        val pointer = event.actionPointer
        val stylus = pointer.tool == PointerTool.Stylus || pointer.tool == PointerTool.StylusEraser
        when (event.action) {
            TouchAction.HoverEnter, TouchAction.HoverMove, TouchAction.HoverExit -> {
                if (stylus) fingers.observe(event).forEach(::count)
                return
            }
            else -> if (stylus) fingers.observe(event).forEach(::count)
        }
        strokePointer?.let { id ->
            val lifted = event.action == TouchAction.Up || (event.action == TouchAction.PointerUp && pointer.id == id)
            if (lifted || event.action == TouchAction.Cancel) strokePointer = null
            return
        }
        eyedropperPointer?.let { id ->
            val lifted = (event.action == TouchAction.Up || event.action == TouchAction.PointerUp) && pointer.id == id
            if (lifted || event.action == TouchAction.Cancel) {
                eyedropperPointer = null
                fingers.cancelTaps()
            }
            return
        }
        fingers.onTouch(event).forEach(::count)
        if (eyedropperPointer != null) return
        if (event.action == TouchAction.Down || (event.action == TouchAction.PointerDown && stylus)) {
            val route = routePointer(pointer.tool, CanvasTool.Brush)
            if (route == PointerRoute.Paint || route == PointerRoute.Erase || route == PointerRoute.Smudge) {
                strokePointer = pointer.id
                outcome = outcome.copy(strokes = outcome.strokes + 1)
            }
        }
    }

    private fun runTimers(untilMillis: Long) {
        while (true) {
            val due = fingers.nextCheckMillis() ?: return
            if (due > untilMillis) return
            val effects = fingers.onTimer(due)
            effects.forEach(::count)
            if (effects.isEmpty() && fingers.nextCheckMillis() == due) return
        }
    }

    private fun count(effect: FingerEffect) {
        outcome =
            when (effect) {
                is FingerEffect.BeginNavigation -> {
                    pendingMoves = 0
                    outcome.copy(navigations = outcome.navigations + 1)
                }
                is FingerEffect.UpdateNavigation -> {
                    pendingMoves++
                    outcome
                }
                is FingerEffect.EndNavigation -> outcome.copy(cameraMoves = outcome.cameraMoves + pendingMoves).also { pendingMoves = 0 }
                FingerEffect.CancelNavigation ->
                    outcome
                        .copy(
                            canceledMoves = outcome.canceledMoves + pendingMoves,
                        ).also { pendingMoves = 0 }
                FingerEffect.Undo -> outcome.copy(undos = outcome.undos + 1)
                FingerEffect.Redo -> outcome.copy(redos = outcome.redos + 1)
                is FingerEffect.EyedropperStart -> {
                    eyedropperPointer = effect.pointerId
                    outcome.copy(eyedroppers = outcome.eyedroppers + 1)
                }
                is FingerEffect.Gesture ->
                    outcome.copy(
                        actions =
                            outcome.actions + (effect.action to (outcome.actions[effect.action] ?: 0) + 1),
                    )
                is FingerEffect.BeginFingerStroke -> {
                    strokePointer = effect.pointerId
                    fingers.cancelTaps()
                    outcome.copy(strokes = outcome.strokes + 1, fingerStrokes = outcome.fingerStrokes + 1)
                }
            }
    }

    private companion object {
        const val SETTLE_MILLIS = 2_000L
    }
}
