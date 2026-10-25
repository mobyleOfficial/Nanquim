package com.mobyle.nanquim.canvas.input

enum class Gesture(
    val id: String,
    val continuous: Boolean = false,
) {
    OneFingerTap("oneFingerTap"),
    TwoFingerTap("twoFingerTap"),
    ThreeFingerTap("threeFingerTap"),
    FourFingerTap("fourFingerTap"),

    LongPress("longPress"),

    ThreeFingerScrub("threeFingerScrub"),
    ThreeFingerSwipeDown("threeFingerSwipeDown"),

    QuickPinch("quickPinch"),

    FingerDrag("fingerDrag", continuous = true),

    StylusButton("stylusButton", continuous = true),
    StylusButtonDoublePress("stylusButtonDoublePress"),

    ModifierTap("modifierTap"),

    ModifierTouch("modifierTouch", continuous = true),
    ;

    companion object {
        fun fromId(id: String?): Gesture? = entries.firstOrNull { it.id == id }
    }
}

enum class GestureAction(
    val id: String,
    val repeats: Boolean = false,
    val stroke: Boolean = false,
    val atPoint: Boolean = false,
) {
    None("none"),

    Paint("paint", stroke = true),
    Smudge("smudge", stroke = true),
    Erase("erase", stroke = true),
    Undo("undo", repeats = true),
    Redo("redo", repeats = true),
    Eyedropper("eyedropper"),
    FullScreen("fullScreen"),
    ClearLayer("clearLayer"),
    CopyPasteMenu("copyPasteMenu", atPoint = true),
    FitCanvas("fitCanvas"),
    QuickMenu("quickMenu"),
    LayerSelect("layerSelect", atPoint = true),
    ToggleEraser("toggleEraser"),

    AssistedDrawing("assistedDrawing"),

    ShortcutBrush("shortcutBrush"),
    ;

    companion object {
        fun fromId(id: String?): GestureAction? = entries.firstOrNull { it.id == id }
    }
}

data class GestureBindings(
    val actions: Map<Gesture, GestureAction>,
    val longPressMillis: Long = GestureArbiter.LONG_PRESS_MILLIS,
    val repeatDelayMillis: Long = GestureArbiter.REPEAT_DELAY_MILLIS,
    val stylusDisablesFingerStrokes: Boolean = false,
) {
    fun actionOf(gesture: Gesture): GestureAction = actions[gesture] ?: GestureAction.None

    companion object {
        fun defaults(
            undoFingers: Int = GestureArbiter.DEFAULT_UNDO_FINGERS,
            fingerPainting: Boolean = false,
        ): GestureBindings = GestureConfiguration.defaults(undoFingers, fingerPainting).bindings
    }
}

class StylusButtonGestures {
    enum class Change { Pressed, Released, DoublePressed }

    var pressed: Boolean = false
        private set

    private var lastPressMillis: Long? = null

    fun update(
        pressed: Boolean,
        timeMillis: Long,
    ): Change? {
        if (pressed == this.pressed) return null
        this.pressed = pressed
        if (!pressed) return Change.Released
        val previous = lastPressMillis
        if (previous != null && timeMillis - previous <= DOUBLE_PRESS_MILLIS) {
            lastPressMillis = null
            return Change.DoublePressed
        }
        lastPressMillis = timeMillis
        return Change.Pressed
    }

    companion object {
        const val DOUBLE_PRESS_MILLIS = 350L
    }
}
