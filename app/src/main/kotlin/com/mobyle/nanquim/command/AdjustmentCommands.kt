package com.mobyle.nanquim.command

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.canvas.adjust.AdjustmentEditor
import com.mobyle.nanquim.canvas.adjust.AdjustmentMode
import com.mobyle.nanquim.canvas.adjust.CurveChannel
import com.mobyle.nanquim.canvas.adjust.CurveEditing
import com.mobyle.nanquim.canvas.adjust.CurvePoint
import com.mobyle.nanquim.canvas.adjust.ranges
import com.mobyle.nanquim.document.command.Availability
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.engine.AdjustmentFilter
import java.util.Locale

object AdjustmentCommandIds {
    fun preview(filter: AdjustmentFilter) = "adjust.${filter.commandName}.preview"

    fun apply(filter: AdjustmentFilter) = "adjust.${filter.commandName}.apply"

    const val SET_PARAMETER = "adjust.setParameter"
    const val SELECT_PARAMETER = "adjust.selectParameter"
    const val COMPARE = "adjust.compare"
    const val RESET = "adjust.reset"
    const val SET_CURVE = "adjust.curves.setPoints"
    const val SET_GRADIENT = "adjust.gradientMap.setGradient"
    const val UNDO = "adjust.undo"
    const val REDO = "adjust.redo"
    const val COMMIT = "adjust.commit"
    const val CANCEL = "adjust.cancel"
}

private val MODES = AdjustmentMode.entries.map { it.name.lowercase(Locale.ROOT) }

const val LIQUIFY_STROKE_PATH = "liquify.strokePath"

private val requiresDocument: (com.mobyle.nanquim.document.command.AppState) -> Availability = { state ->
    if (state.hasOpenDocument) Availability.Enabled else Availability.Disabled("No open document")
}

private fun AdjustmentFilter.previewTitle(): Int =
    when (this) {
        AdjustmentFilter.HueSaturationBrightness -> R.string.command_adjust_hue_saturation_brightness_preview
        AdjustmentFilter.ColorBalance -> R.string.command_adjust_color_balance_preview
        AdjustmentFilter.Curves -> R.string.command_adjust_curves_preview
        AdjustmentFilter.GradientMap -> R.string.command_adjust_gradient_map_preview
        AdjustmentFilter.GaussianBlur -> R.string.command_adjust_gaussian_blur_preview
        AdjustmentFilter.MotionBlur -> R.string.command_adjust_motion_blur_preview
        AdjustmentFilter.PerspectiveBlur -> R.string.command_adjust_perspective_blur_preview
        AdjustmentFilter.Sharpen -> R.string.command_adjust_sharpen_preview
        AdjustmentFilter.Noise -> R.string.command_adjust_noise_preview
        AdjustmentFilter.Bloom -> R.string.command_adjust_bloom_preview
        AdjustmentFilter.Glitch -> R.string.command_adjust_glitch_preview
        AdjustmentFilter.Halftone -> R.string.command_adjust_halftone_preview
        AdjustmentFilter.ChromaticAberration -> R.string.command_adjust_chromatic_aberration_preview
        AdjustmentFilter.Liquify -> R.string.command_liquify_open
    }

private fun AdjustmentFilter.applyTitle(): Int =
    when (this) {
        AdjustmentFilter.HueSaturationBrightness -> R.string.command_adjust_hue_saturation_brightness_apply
        AdjustmentFilter.ColorBalance -> R.string.command_adjust_color_balance_apply
        AdjustmentFilter.Curves -> R.string.command_adjust_curves_apply
        AdjustmentFilter.GradientMap -> R.string.command_adjust_gradient_map_apply
        AdjustmentFilter.GaussianBlur -> R.string.command_adjust_gaussian_blur_apply
        AdjustmentFilter.MotionBlur -> R.string.command_adjust_motion_blur_apply
        AdjustmentFilter.PerspectiveBlur -> R.string.command_adjust_perspective_blur_apply
        AdjustmentFilter.Sharpen -> R.string.command_adjust_sharpen_apply
        AdjustmentFilter.Noise -> R.string.command_adjust_noise_apply
        AdjustmentFilter.Bloom -> R.string.command_adjust_bloom_apply
        AdjustmentFilter.Glitch -> R.string.command_adjust_glitch_apply
        AdjustmentFilter.Halftone -> R.string.command_adjust_halftone_apply
        AdjustmentFilter.ChromaticAberration -> R.string.command_adjust_chromatic_aberration_apply
        AdjustmentFilter.Liquify -> R.string.command_liquify_apply
    }

private val CURVE_NAMES = CurveChannel.entries.map { it.name.lowercase(Locale.ROOT) }

private fun JsonElement.curvePoints(): List<CurvePoint> {
    val points =
        jsonArray
            .map { point ->
                val values = point.jsonArray.map { it.jsonPrimitive.double.toFloat() }
                require(values.size == 2) { "Curve points are [x, y]" }
                CurvePoint(values[0].coerceIn(0f, 1f), values[1].coerceIn(0f, 1f))
            }.sortedBy { it.x }
    require(points.size in 2..CurveEditing.MAXIMUM_POINTS) { "A curve has 2-${CurveEditing.MAXIMUM_POINTS} points" }
    require(points.zipWithNext().all { (first, second) -> second.x > first.x }) { "Curve points need distinct x" }
    return points
}

fun registerAdjustmentCommands(
    registry: CommandRegistry,
    canvas: CanvasSession,
) {
    val gradients = canvas.gradients

    fun open(
        filter: AdjustmentFilter,
        arguments: JsonObject,
    ): CommandResult? {
        val mode =
            (arguments["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { name ->
                AdjustmentMode.entries.first { it.name.lowercase(Locale.ROOT) == name }
            } ?: AdjustmentMode.Layer
        val current = canvas.adjustmentEditor
        if (current == null || current.filter != filter || current.mode != mode) {
            if (!canvas.startAdjustment(filter, mode)) {
                return CommandResult.Failure(CommandErrorCode.HandlerFailed, "The active layer cannot be adjusted")
            }
        }
        val editor = canvas.adjustmentEditor ?: return CommandResult.Failure(CommandErrorCode.HandlerFailed, "No adjustment")
        filter.parameterNames.forEachIndexed { index, name ->
            (arguments[name] as? JsonPrimitive)?.doubleOrNull?.let { editor.preview(index, it.toFloat()) }
        }
        (arguments["curves"] as? JsonObject)?.forEach { (name, points) ->
            val channel = CurveChannel.entries.getOrNull(CURVE_NAMES.indexOf(name))
            if (channel == null) {
                editor.cancelChange()
                return CommandResult.Failure(CommandErrorCode.InvalidArguments, "Unknown curve $name")
            }
            try {
                editor.previewCurve(channel, points.curvePoints())
            } catch (exception: IllegalArgumentException) {
                editor.cancelChange()
                return CommandResult.Failure(CommandErrorCode.InvalidArguments, exception.message ?: "Invalid curve")
            }
        }
        (arguments["gradientId"] as? JsonPrimitive)?.contentOrNull?.let { id ->
            val entry = gradients.entry(id)
            if (entry == null) {
                editor.cancelChange()
                return CommandResult.Failure(CommandErrorCode.InvalidArguments, "Unknown gradient $id")
            }
            editor.previewGradient(entry.id, entry.definition)
        }
        editor.finishChange()
        canvas.adjustmentChanged()
        return null
    }

    for (filter in AdjustmentFilter.entries) {
        val ranges = filter.ranges()
        val parameters =
            filter.parameterNames.mapIndexed { index, name ->
                ParameterSpec(name, ParameterType.Number, JsonNull, ranges[index].minimum.toDouble(), ranges[index].maximum.toDouble())
            } + ParameterSpec("mode", ParameterType.Enumeration, JsonNull, allowedValues = MODES) +
                when (filter) {
                    AdjustmentFilter.Curves -> listOf(ParameterSpec("curves", ParameterType.Object, JsonNull))
                    AdjustmentFilter.GradientMap -> listOf(ParameterSpec("gradientId", ParameterType.String, JsonNull))
                    else -> emptyList()
                }
        registry.register(
            CommandSpec(
                id = AdjustmentCommandIds.preview(filter),
                titleRes = filter.previewTitle(),
                category = CommandCategory.Adjust,
                parameters = parameters,
                undoable = false,
                availability = requiresDocument,
            ),
        ) { _, arguments -> open(filter, arguments) ?: CommandResult.Success() }
        registry.register(
            CommandSpec(
                id = AdjustmentCommandIds.apply(filter),
                titleRes = filter.applyTitle(),
                category = CommandCategory.Adjust,
                parameters = parameters,
                undoable = true,
                availability = requiresDocument,
            ),
        ) { _, arguments ->
            open(filter, arguments)?.let { return@register it }
            canvas.finishAdjustment(commit = true)
            CommandResult.Success()
        }
    }

    fun register(
        id: String,
        titleRes: Int,
        parameters: List<ParameterSpec> = emptyList(),
        undoable: Boolean = false,
        action: (AdjustmentEditor, JsonObject) -> Unit,
    ) = registry.register(
        CommandSpec(
            id = id,
            titleRes = titleRes,
            category = CommandCategory.Adjust,
            parameters = parameters,
            undoable = undoable,
            availability = requiresDocument,
        ),
    ) { _, arguments ->
        val editor = canvas.adjustmentEditor ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No adjustment is open")
        val index = (arguments["name"] as? JsonPrimitive)?.content?.let(editor.filter.parameterNames::indexOf)
        if (index == -1) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Unknown parameter of ${editor.filter.commandName}")
        }
        try {
            action(editor, arguments)
        } catch (exception: IllegalArgumentException) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, exception.message ?: "Invalid arguments")
        }
        canvas.adjustmentChanged()
        CommandResult.Success()
    }

    register(
        AdjustmentCommandIds.SET_PARAMETER,
        R.string.command_adjust_set_parameter,
        listOf(ParameterSpec("name", ParameterType.String), ParameterSpec("value", ParameterType.Number)),
    ) { editor, arguments ->
        val index = editor.filter.parameterNames.indexOf(arguments.getValue("name").jsonPrimitive.content)
        if (editor.filter == AdjustmentFilter.Liquify && editor.filter.parameterNames[index] == "adjust") {
            canvas.adjustLiquify(
                arguments
                    .getValue("value")
                    .jsonPrimitive.doubleOrNull!!
                    .toFloat(),
            )
            return@register
        }
        editor.set(
            index,
            arguments
                .getValue("value")
                .jsonPrimitive.doubleOrNull!!
                .toFloat(),
        )
    }
    register(
        AdjustmentCommandIds.SELECT_PARAMETER,
        R.string.command_adjust_select_parameter,
        listOf(ParameterSpec("name", ParameterType.String)),
    ) { editor, arguments ->
        editor.selectMainParameter(editor.filter.parameterNames.indexOf(arguments.getValue("name").jsonPrimitive.content))
    }
    register(
        AdjustmentCommandIds.COMPARE,
        R.string.adjust_preview,
        listOf(ParameterSpec("enabled", ParameterType.Boolean)),
    ) { editor, arguments -> editor.comparing = arguments.getValue("enabled").jsonPrimitive.boolean }
    register(AdjustmentCommandIds.RESET, R.string.adjust_reset) { _, _ -> canvas.resetAdjustment() }
    register(
        AdjustmentCommandIds.SET_CURVE,
        R.string.command_adjust_set_curve,
        listOf(
            ParameterSpec("curve", ParameterType.Enumeration, allowedValues = CURVE_NAMES),
            ParameterSpec("points", ParameterType.Array),
        ),
    ) { editor, arguments ->
        require(editor.filter == AdjustmentFilter.Curves) { "The open adjustment is not Curves" }
        val channel = CurveChannel.entries[CURVE_NAMES.indexOf(arguments.getValue("curve").jsonPrimitive.content)]
        editor.setCurve(channel, arguments.getValue("points").curvePoints())
    }
    register(
        AdjustmentCommandIds.SET_GRADIENT,
        R.string.command_adjust_set_gradient,
        listOf(ParameterSpec("gradientId", ParameterType.String)),
    ) { editor, arguments ->
        require(editor.filter == AdjustmentFilter.GradientMap) { "The open adjustment is not Gradient Map" }
        val id = arguments.getValue("gradientId").jsonPrimitive.content
        val entry = requireNotNull(gradients.entry(id)) { "Unknown gradient $id" }
        editor.setGradient(entry.id, entry.definition)
    }
    register(
        LIQUIFY_STROKE_PATH,
        R.string.command_liquify_stroke_path,
        listOf(ParameterSpec("samples", ParameterType.Array)),
    ) { editor, arguments ->
        require(editor.filter == AdjustmentFilter.Liquify) { "The open adjustment is not Liquify" }
        val samples =
            arguments.getValue("samples").jsonArray.flatMap { sample ->
                val values = sample.jsonArray.map { it.jsonPrimitive.doubleOrNull!!.toFloat() }
                require(values.size >= 2) { "A sample needs x and y" }
                listOf(values[0], values[1], values.getOrElse(2) { 1f }, values.getOrElse(3) { 0f })
            }
        require(samples.isNotEmpty()) { "No samples" }
        canvas.liquifyPath(samples.toFloatArray())
    }
    register(AdjustmentCommandIds.UNDO, R.string.adjust_undo) { _, _ -> canvas.undoAdjustment() }
    register(AdjustmentCommandIds.REDO, R.string.adjust_redo) { _, _ -> canvas.redoAdjustment() }
    registry.register(
        CommandSpec(
            id = AdjustmentCommandIds.COMMIT,
            titleRes = R.string.adjust_apply,
            category = CommandCategory.Adjust,
            undoable = true,
            availability = requiresDocument,
        ),
    ) { _, _ ->
        if (canvas.adjustmentEditor == null) return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No adjustment is open")
        canvas.finishAdjustment(commit = true)
        CommandResult.Success()
    }
    registry.register(
        CommandSpec(
            id = AdjustmentCommandIds.CANCEL,
            titleRes = R.string.adjust_cancel,
            category = CommandCategory.Adjust,
            undoable = false,
            availability = requiresDocument,
        ),
    ) { _, _ ->
        if (canvas.adjustmentEditor == null) return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No adjustment is open")
        canvas.finishAdjustment(commit = false)
        CommandResult.Success()
    }
}
