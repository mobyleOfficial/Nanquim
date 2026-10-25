package com.mobyle.nanquim.canvas.input

enum class PointerTool {
    Stylus,
    StylusEraser,
    Finger,
    Mouse,

    Palm,
    Unknown,
    ;

    companion object {
        private const val TOOL_TYPE_FINGER = 1
        private const val TOOL_TYPE_STYLUS = 2
        private const val TOOL_TYPE_MOUSE = 3
        private const val TOOL_TYPE_ERASER = 4
        private const val TOOL_TYPE_PALM = 5

        fun fromToolType(toolType: Int): PointerTool =
            when (toolType) {
                TOOL_TYPE_FINGER -> Finger
                TOOL_TYPE_STYLUS -> Stylus
                TOOL_TYPE_MOUSE -> Mouse
                TOOL_TYPE_ERASER -> StylusEraser
                TOOL_TYPE_PALM -> Palm
                else -> Unknown
            }
    }
}

enum class CanvasTool {
    Brush,

    Smudge,
    Eraser,

    Selection,

    Transform,

    Clone,

    Crop,

    Adjust,

    Guide,

    Text,
}

enum class PointerRoute {
    Paint,
    Erase,
    Smudge,
    Navigate,

    Select,
    Ignore,
}

fun routePointer(
    pointer: PointerTool,
    currentTool: CanvasTool,
    paintsSelection: Boolean = false,
    adjustmentPencil: Boolean = false,
): PointerRoute =
    when (pointer) {
        PointerTool.Stylus, PointerTool.Mouse ->
            when {
                currentTool == CanvasTool.Adjust && adjustmentPencil -> PointerRoute.Paint
                currentTool == CanvasTool.Transform || currentTool == CanvasTool.Adjust -> PointerRoute.Ignore
                currentTool == CanvasTool.Guide || currentTool == CanvasTool.Text -> PointerRoute.Ignore
                currentTool == CanvasTool.Crop -> PointerRoute.Select
                currentTool == CanvasTool.Selection && !paintsSelection -> PointerRoute.Select
                currentTool == CanvasTool.Eraser -> PointerRoute.Erase
                currentTool == CanvasTool.Smudge -> PointerRoute.Smudge
                else -> PointerRoute.Paint
            }
        PointerTool.StylusEraser -> if (currentTool == CanvasTool.Guide) PointerRoute.Ignore else PointerRoute.Erase
        PointerTool.Finger -> {
            val drawsShapes = (currentTool == CanvasTool.Selection && !paintsSelection) || currentTool == CanvasTool.Crop
            if (drawsShapes) PointerRoute.Select else PointerRoute.Navigate
        }
        PointerTool.Palm, PointerTool.Unknown -> PointerRoute.Ignore
    }

class PalmRejection(
    private val graceMillis: Long = DEFAULT_GRACE_MILLIS,
) {
    private var stylusHovering = false
    private var stylusTouching = false
    private var stylusLeftAtMillis: Long? = null

    var stylusUsed = false
        private set

    fun onStylusHover(
        hovering: Boolean,
        timeMillis: Long,
    ) {
        if (stylusHovering && !hovering) stylusLeftAtMillis = timeMillis
        stylusHovering = hovering
    }

    fun onStylusTouch(
        touching: Boolean,
        timeMillis: Long,
    ) {
        if (stylusTouching && !touching) stylusLeftAtMillis = timeMillis
        stylusTouching = touching
        if (touching) stylusUsed = true
    }

    val stylusPresent: Boolean get() = stylusHovering || stylusTouching

    fun fingersAllowed(timeMillis: Long): Boolean {
        if (stylusHovering || stylusTouching) return false
        val leftAt = stylusLeftAtMillis ?: return true
        return timeMillis - leftAt >= graceMillis
    }

    companion object {
        const val DEFAULT_GRACE_MILLIS = 300L
    }
}
