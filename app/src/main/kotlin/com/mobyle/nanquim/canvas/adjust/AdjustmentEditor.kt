package com.mobyle.nanquim.canvas.adjust

import com.mobyle.nanquim.engine.AdjustmentFilter
import com.mobyle.nanquim.engine.LiquifyMode
import com.mobyle.nanquim.formats.gradient.GradientDefinition

enum class AdjustmentMode {
    Layer,
    Pencil,
}

data class ParameterRange(
    val minimum: Float,
    val maximum: Float,
    val default: Float,
) {
    val span: Float get() = maximum - minimum

    fun clamp(value: Float): Float = value.coerceIn(minimum, maximum)
}

fun AdjustmentFilter.ranges(): List<ParameterRange> =
    when (this) {
        AdjustmentFilter.HueSaturationBrightness -> List(3) { ParameterRange(-1f, 1f, 0f) }
        AdjustmentFilter.ColorBalance -> List(9) { ParameterRange(-1f, 1f, 0f) }
        AdjustmentFilter.Curves -> emptyList()
        AdjustmentFilter.GradientMap -> listOf(ParameterRange(0f, 1f, 1f))
        AdjustmentFilter.GaussianBlur, AdjustmentFilter.Sharpen -> listOf(ParameterRange(0f, 1f, 0f))
        AdjustmentFilter.MotionBlur -> listOf(ParameterRange(0f, 1f, 0f), ANGLE_RANGE)
        AdjustmentFilter.PerspectiveBlur ->
            listOf(
                ParameterRange(0f, 1f, 0f),
                ParameterRange(0f, 1f, 0.5f),
                ParameterRange(0f, 1f, 0.5f),
                ParameterRange(0f, 1f, 0f),
                ANGLE_RANGE,
            )
        AdjustmentFilter.Noise ->
            listOf(
                ParameterRange(0f, 1f, 0f),
                ParameterRange(0f, 1f, 0.3f),
                ParameterRange(0f, 1f, 3f / 7f),
                ParameterRange(0f, 1f, 0f),
                ParameterRange(0f, 2f, 0f),
            )
        AdjustmentFilter.Bloom -> listOf(ParameterRange(0f, 1f, 0f), ParameterRange(0f, 1f, 0.6f), ParameterRange(0f, 1f, 0.3f))
        AdjustmentFilter.Glitch ->
            listOf(
                ParameterRange(0f, 1f, 0f),
                ParameterRange(0f, 1f, 0.3f),
                ParameterRange(0f, 1f, 0.5f),
                ParameterRange(0f, 1f, 0.5f),
                ParameterRange(0f, 3f, 0f),
            )
        AdjustmentFilter.Halftone -> listOf(ParameterRange(0f, 1f, 0.3f), ParameterRange(0f, 2f, 0f))
        AdjustmentFilter.ChromaticAberration ->
            listOf(
                ParameterRange(0f, 1f, 0f),
                ParameterRange(0f, 1f, 0.5f),
                ParameterRange(0f, 1f, 0.5f),
                ParameterRange(0f, 1f, 0f),
                ANGLE_RANGE,
                ParameterRange(0f, 1f, 0.5f),
                ParameterRange(0f, 1f, 0f),
            )
        AdjustmentFilter.Liquify ->
            listOf(
                ParameterRange(0f, 1f, 0.3f),
                ParameterRange(0f, 1f, 0.6f),
                ParameterRange(0f, 1f, 0.5f),
                ParameterRange(0f, 1f, 0f),
                ParameterRange(0f, 1f, 1f),
                ParameterRange(0f, (LiquifyMode.entries.size - 1).toFloat(), 0f),
            )
    }

private val ANGLE_RANGE = ParameterRange(-1f, 1f, 0f)

object PerspectiveParameters {
    const val PUCK_X = 1
    const val PUCK_Y = 2
    const val DIRECTIONAL = 3
    const val ANGLE = 4
}

val AdjustmentFilter.hasPuck: Boolean
    get() = this == AdjustmentFilter.PerspectiveBlur || this == AdjustmentFilter.ChromaticAberration

fun AdjustmentFilter.isAngle(index: Int): Boolean = parameterNames.getOrNull(index) == "angle"

data class AdjustmentState(
    val values: List<Float>,
    val curves: List<List<CurvePoint>> = emptyList(),
    val gradientId: String? = null,
    val gradient: GradientDefinition? = null,
)

private data class Step(
    val before: AdjustmentState,
    val after: AdjustmentState,
    val maskEntry: Long? = null,
    val liquify: Boolean = false,
)

data class StepResult(
    val maskEntry: Long?,
    val liquify: Boolean = false,
)

class AdjustmentEditor(
    val filter: AdjustmentFilter,
    val mode: AdjustmentMode = AdjustmentMode.Layer,
    gradient: GradientEntry? = null,
) {
    private val ranges = filter.ranges()

    var state: AdjustmentState =
        AdjustmentState(
            values = ranges.map { it.default },
            curves = if (filter == AdjustmentFilter.Curves) CurveChannel.entries.map { CurveEditing.STRAIGHT } else emptyList(),
            gradientId = gradient?.id,
            gradient = gradient?.definition,
        )
        private set

    val values: FloatArray get() = state.values.toFloatArray()

    val hasParameters: Boolean get() = ranges.isNotEmpty()

    var mainParameter: Int = 0
        private set

    var selectedCurve: CurveChannel = CurveChannel.Master

    var comparing: Boolean = false

    val amount: Float get() = if (comparing) 0f else 1f

    private val undoSteps = ArrayDeque<Step>()
    private val redoSteps = ArrayDeque<Step>()
    private var changeStart: AdjustmentState? = null
    private var dragStartValue = 0f

    val canUndo: Boolean get() = undoSteps.isNotEmpty()
    val canRedo: Boolean get() = redoSteps.isNotEmpty()

    fun range(index: Int): ParameterRange = ranges[index]

    fun tableData(): FloatArray =
        when (filter) {
            AdjustmentFilter.Curves -> CurveEditing.tableData(state.curves)
            AdjustmentFilter.GradientMap -> state.gradient?.tableData() ?: floatArrayOf()
            else -> floatArrayOf()
        }

    fun selectMainParameter(index: Int) {
        require(index in ranges.indices) { "No parameter $index" }
        mainParameter = index
    }

    private fun change(update: (AdjustmentState) -> AdjustmentState) {
        if (changeStart == null) changeStart = state
        state = update(state)
    }

    fun preview(
        index: Int,
        value: Float,
    ) = change { current -> current.copy(values = current.values.toMutableList().apply { set(index, ranges[index].clamp(value)) }) }

    fun set(
        index: Int,
        value: Float,
    ) {
        preview(index, value)
        finishChange()
    }

    fun previewCurve(
        channel: CurveChannel,
        points: List<CurvePoint>,
    ) = change { current -> current.copy(curves = current.curves.toMutableList().apply { set(channel.ordinal, points) }) }

    fun setCurve(
        channel: CurveChannel,
        points: List<CurvePoint>,
    ) {
        previewCurve(channel, points)
        finishChange()
    }

    fun curve(channel: CurveChannel): List<CurvePoint> = state.curves[channel.ordinal]

    fun setGradient(
        id: String?,
        gradient: GradientDefinition,
    ) {
        previewGradient(id, gradient)
        finishChange()
    }

    fun previewGradient(
        id: String?,
        gradient: GradientDefinition,
    ) = change { current -> current.copy(gradientId = id, gradient = gradient) }

    fun finishChange() {
        val start = changeStart ?: return
        changeStart = null
        if (start == state) return
        push(Step(start, state))
    }

    private fun push(step: Step) {
        undoSteps.addLast(step)
        redoSteps.clear()
    }

    fun recordLiquifyStep() {
        finishChange()
        push(Step(state, state, liquify = true))
    }

    fun recordMaskStroke(entry: Long) {
        finishChange()
        push(Step(state, state, entry))
    }

    fun beginDrag() {
        if (!hasParameters) return
        dragStartValue = state.values[mainParameter]
        preview(mainParameter, dragStartValue)
    }

    fun dragBy(fraction: Float) {
        if (!hasParameters) return
        preview(mainParameter, dragStartValue + fraction * ranges[mainParameter].span)
    }

    fun dragAlong(
        fraction: Float,
        radians: Double,
    ) {
        preview(0, fraction)
        preview(1, (radians / Math.PI).toFloat())
    }

    val puck: Pair<Float, Float>?
        get() =
            if (filter.hasPuck) {
                state.values[PerspectiveParameters.PUCK_X] to state.values[PerspectiveParameters.PUCK_Y]
            } else {
                null
            }

    fun movePuck(
        x: Float,
        y: Float,
    ) {
        preview(PerspectiveParameters.PUCK_X, x)
        preview(PerspectiveParameters.PUCK_Y, y)
    }

    fun cancelChange() {
        state = changeStart ?: return
        changeStart = null
    }

    fun reset(maskClearEntry: Long? = null) {
        finishChange()
        val before = state
        state =
            state.copy(
                values = ranges.map { it.default },
                curves = state.curves.map { CurveEditing.STRAIGHT },
            )
        if (before != state || maskClearEntry != null) push(Step(before, state, maskClearEntry))
    }

    fun undo(): StepResult? {
        val step = undoSteps.removeLastOrNull() ?: return null
        redoSteps.addLast(step)
        state = step.before
        return StepResult(step.maskEntry, step.liquify)
    }

    fun redo(): StepResult? {
        val step = redoSteps.removeLastOrNull() ?: return null
        undoSteps.addLast(step)
        state = step.after
        return StepResult(step.maskEntry, step.liquify)
    }
}
