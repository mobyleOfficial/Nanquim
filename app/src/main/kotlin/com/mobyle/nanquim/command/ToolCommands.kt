package com.mobyle.nanquim.command

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.canvas.ColorPanelMode
import com.mobyle.nanquim.canvas.input.CanvasTool
import com.mobyle.nanquim.document.color.CmykConversion
import com.mobyle.nanquim.document.color.CmykInks
import com.mobyle.nanquim.document.color.InkChannel
import com.mobyle.nanquim.document.color.ColorHarmony
import com.mobyle.nanquim.document.color.ColorValue
import com.mobyle.nanquim.document.command.AppState
import com.mobyle.nanquim.document.command.Availability
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.engine.StrokeSampleBuffer
import com.mobyle.nanquim.engine.StrokeTool

object ToolCommandIds {
    const val TOOL_SELECT = "tool.select"
    const val TOOL_PAINT_STROKE_PATH = "tool.paint.strokePath"
    const val TOOL_ERASE_STROKE_PATH = "tool.erase.strokePath"
    const val TOOL_SMUDGE_STROKE_PATH = "tool.smudge.strokePath"
    const val BRUSH_SET_SIZE = "brush.setSize"
    const val BRUSH_SET_OPACITY = "brush.setOpacity"
    const val BRUSH_STEP_SIZE = "brush.stepSize"
    const val COLOR_SET_PRIMARY = "color.setPrimary"
    const val COLOR_SET_SECONDARY = "color.setSecondary"
    const val COLOR_SWAP = "color.swap"
    const val COLOR_HISTORY_LIST = "color.history.list"
    const val COLOR_HISTORY_CLEAR = "color.history.clear"
    const val COLOR_PANEL_OPEN = "color.panel.open"
    const val COLOR_PANEL_CLOSE = "color.panel.close"
    const val COLOR_PANEL_TOGGLE = "color.panel.toggle"
    const val COLOR_PANEL_SET_MODE = "color.panel.setMode"
    const val COLOR_PANEL_SET_HARMONY = "color.panel.setHarmony"
}

private const val SIZE_STEP = 0.05f

private val requiresDocument: (AppState) -> Availability = { state ->
    if (state.hasOpenDocument) Availability.Enabled else Availability.Disabled("No open document")
}

private const val TOOL_BRUSH = "brush"
private const val TOOL_ERASER = "eraser"
private const val TOOL_SMUDGE = "smudge"
private const val TOOL_SELECTION = "selection"
private const val TOOL_TRANSFORM = "transform"
private const val TOOL_CLONE = "clone"
private const val TOOL_CROP = "crop"
private const val TOOL_GUIDE = "guide"
private const val TOOL_TEXT = "text"
private val TOOLS = listOf(TOOL_BRUSH, TOOL_SMUDGE, TOOL_ERASER, TOOL_SELECTION, TOOL_TRANSFORM, TOOL_CLONE, TOOL_CROP, TOOL_GUIDE, TOOL_TEXT)
private const val SAMPLE_FIELDS = 4
private const val NANOS_PER_MILLI = 1_000_000.0
private const val TOOL_TYPE_STYLUS = 2

private const val MAXIMUM_BRUSH_SIZE_PX = 10_000.0

fun registerToolCommands(
    registry: CommandRegistry,
    session: CanvasSession,
) {
    registry.register(
        CommandSpec(
            id = ToolCommandIds.TOOL_SELECT,
            titleRes = R.string.command_tool_select,
            category = CommandCategory.Brush,
            parameters = listOf(ParameterSpec("tool", ParameterType.Enumeration, allowedValues = TOOLS)),
            undoable = false,
        ),
    ) { _, arguments ->
        session.canvasTool =
            when (arguments.string("tool")) {
                TOOL_ERASER -> CanvasTool.Eraser
                TOOL_SMUDGE -> CanvasTool.Smudge
                TOOL_SELECTION -> CanvasTool.Selection
                TOOL_TRANSFORM -> CanvasTool.Transform
                TOOL_CLONE -> CanvasTool.Clone
                TOOL_CROP -> CanvasTool.Crop
                TOOL_GUIDE -> CanvasTool.Guide
                TOOL_TEXT -> CanvasTool.Text
                else -> CanvasTool.Brush
            }
        CommandResult.Success()
    }

    registry.register(
        CommandSpec(
            id = ToolCommandIds.BRUSH_SET_SIZE,
            titleRes = R.string.command_brush_set_size,
            category = CommandCategory.Brush,
            parameters =
                listOf(
                    ParameterSpec("sizePx", ParameterType.Number, minimum = 0.0, maximum = MAXIMUM_BRUSH_SIZE_PX),
                ),
            undoable = false,
        ),
    ) { _, arguments ->
        val sizePx = arguments.getValue("sizePx").jsonPrimitive.double
        val settings = session.currentToolSettings
        session.updateCurrentToolSettings(settings.copy(sizeSlider = settings.limits.sliderForSize(sizePx.toFloat())))
        CommandResult.Success()
    }
    registry.register(
        CommandSpec(
            id = ToolCommandIds.BRUSH_STEP_SIZE,
            titleRes = R.string.command_brush_step_size,
            category = CommandCategory.Brush,
            parameters = listOf(ParameterSpec("steps", ParameterType.Integer, minimum = -20.0, maximum = 20.0)),
            undoable = false,
        ),
    ) { _, arguments ->
        val settings = session.currentToolSettings
        val slider = (settings.sizeSlider + arguments.getValue("steps").jsonPrimitive.int * SIZE_STEP).coerceIn(0f, 1f)
        session.updateCurrentToolSettings(settings.copy(sizeSlider = slider))
        CommandResult.Success(JsonObject(mapOf("sizePx" to JsonPrimitive(settings.limits.sizeAt(slider)))))
    }
    registry.register(
        CommandSpec(
            id = ToolCommandIds.BRUSH_SET_OPACITY,
            titleRes = R.string.command_brush_set_opacity,
            category = CommandCategory.Brush,
            parameters = listOf(ParameterSpec("opacity", ParameterType.Number, minimum = 0.0, maximum = 1.0)),
            undoable = false,
        ),
    ) { _, arguments ->
        val opacity =
            arguments
                .getValue("opacity")
                .jsonPrimitive.double
                .toFloat()
        val settings = session.currentToolSettings
        session.updateCurrentToolSettings(settings.copy(opacitySlider = settings.limits.sliderForOpacity(opacity)))
        CommandResult.Success()
    }
    val colorParameters =
        listOf("red", "green", "blue", "saturation", "brightness", "alpha", "cyan", "magenta", "yellow", "black").map {
            ParameterSpec(it, ParameterType.Number, defaultValue = JsonNull, minimum = 0.0, maximum = 1.0)
        } +
            ParameterSpec("hue", ParameterType.Number, defaultValue = JsonNull, minimum = 0.0, maximum = 360.0) +
            ParameterSpec("hex", ParameterType.String, defaultValue = JsonNull)
    for ((id, titleRes, secondary) in listOf(
        Triple(ToolCommandIds.COLOR_SET_PRIMARY, R.string.command_color_set_primary, false),
        Triple(ToolCommandIds.COLOR_SET_SECONDARY, R.string.command_color_set_secondary, true),
    )) {
        registry.register(
            CommandSpec(
                id = id,
                titleRes = titleRes,
                category = CommandCategory.Color,
                parameters = colorParameters,
                undoable = false,
            ),
        ) { _, arguments ->
            val previous = if (secondary) session.colors.secondary else session.colors.primary
            val inks = arguments.inks()
            val conversion = if (inks != null) session.awaitCmykConversion() else session.cmykConversion
            val color = if (inks == null) arguments.colorValue(previous, conversion) else null
            when {
                inks != null && conversion != null -> session.setInks(inks, secondary)
                color != null -> if (secondary) session.setSecondaryColor(color) else session.setPrimaryColor(color)
                else ->
                    return@register CommandResult.Failure(
                        CommandErrorCode.InvalidArguments,
                        "Give red, green and blue; or hue, saturation and brightness; or hex (RRGGBB)" +
                            if (conversion != null) "; or cyan, magenta, yellow and black" else " (cyan, magenta, yellow and black need a CMYK canvas)",
                    )
            }
            CommandResult.Success((if (secondary) session.colors.secondary else session.colors.primary).toJson(session::inksOf))
        }
    }
    registry.register(
        CommandSpec(
            id = ToolCommandIds.COLOR_SWAP,
            titleRes = R.string.command_color_swap,
            category = CommandCategory.Color,
            undoable = false,
        ),
    ) { _, _ ->
        session.swapColors()
        CommandResult.Success()
    }
    registry.register(
        CommandSpec(
            id = ToolCommandIds.COLOR_HISTORY_LIST,
            titleRes = R.string.command_color_history_list,
            category = CommandCategory.Color,
            undoable = false,
        ),
    ) { _, _ ->
        CommandResult.Success(
            buildJsonObject { putJsonArray("colors") { session.colors.history.forEach { add(it.toJson(session::inksOf)) } } },
        )
    }
    registry.register(
        CommandSpec(
            id = ToolCommandIds.COLOR_HISTORY_CLEAR,
            titleRes = R.string.command_color_history_clear,
            category = CommandCategory.Color,
            undoable = false,
        ),
    ) { _, _ ->
        session.clearColorHistory()
        CommandResult.Success()
    }
    val panelModes = ColorPanelMode.entries.filter { it.available }.map { it.id }
    registry.register(
        CommandSpec(
            id = ToolCommandIds.COLOR_PANEL_OPEN,
            titleRes = R.string.command_color_panel_open,
            category = CommandCategory.Color,
            parameters = listOf(ParameterSpec("mode", ParameterType.Enumeration, defaultValue = JsonNull, allowedValues = panelModes)),
            undoable = false,
        ),
    ) { _, arguments ->
        val mode = (arguments["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        mode?.let(ColorPanelMode::fromId)?.let(session::selectColorPanelMode)
        session.showColorPanel(true)
        CommandResult.Success()
    }
    registry.register(
        CommandSpec(
            id = ToolCommandIds.COLOR_PANEL_CLOSE,
            titleRes = R.string.command_color_panel_close,
            category = CommandCategory.Color,
            undoable = false,
        ),
    ) { _, _ ->
        session.showColorPanel(false)
        CommandResult.Success()
    }
    registry.register(
        CommandSpec(
            id = ToolCommandIds.COLOR_PANEL_TOGGLE,
            titleRes = R.string.command_color_panel_toggle,
            category = CommandCategory.Color,
            undoable = false,
        ),
    ) { _, _ ->
        session.showColorPanel(!session.colorPanelVisible)
        CommandResult.Success()
    }
    registry.register(
        CommandSpec(
            id = ToolCommandIds.COLOR_PANEL_SET_MODE,
            titleRes = R.string.command_color_panel_set_mode,
            category = CommandCategory.Color,
            parameters = listOf(ParameterSpec("mode", ParameterType.Enumeration, allowedValues = panelModes)),
            undoable = false,
        ),
    ) { _, arguments ->
        session.selectColorPanelMode(ColorPanelMode.fromId(arguments.getValue("mode").jsonPrimitive.content)!!)
        CommandResult.Success()
    }
    registry.register(
        CommandSpec(
            id = ToolCommandIds.COLOR_PANEL_SET_HARMONY,
            titleRes = R.string.command_color_panel_set_harmony,
            category = CommandCategory.Color,
            parameters =
                listOf(ParameterSpec("harmony", ParameterType.Enumeration, allowedValues = ColorHarmony.entries.map { it.id })),
            undoable = false,
        ),
    ) { _, arguments ->
        session.selectColorHarmony(ColorHarmony.fromId(arguments.getValue("harmony").jsonPrimitive.content)!!)
        CommandResult.Success()
    }

    for ((id, tool, titleRes) in listOf(
        Triple(ToolCommandIds.TOOL_PAINT_STROKE_PATH, StrokeTool.Paint, R.string.command_tool_paint_stroke_path),
        Triple(ToolCommandIds.TOOL_ERASE_STROKE_PATH, StrokeTool.Erase, R.string.command_tool_erase_stroke_path),
        Triple(ToolCommandIds.TOOL_SMUDGE_STROKE_PATH, StrokeTool.Smudge, R.string.command_tool_smudge_stroke_path),
    )) {
        registry.register(
            CommandSpec(
                id = id,
                titleRes = titleRes,
                category = CommandCategory.Brush,
                parameters = listOf(ParameterSpec("samples", ParameterType.Array)),
                undoable = false,
                availability = requiresDocument,
            ),
        ) { _, arguments ->
            val samples =
                parseSamples(arguments.getValue("samples").jsonArray)
                    ?: return@register CommandResult.Failure(
                        CommandErrorCode.InvalidArguments,
                        "samples must be a non-empty array of [x, y, pressure, timeMilliseconds]",
                    )
            if (session.strokePath(tool, samples)) {
                CommandResult.Success()
            } else {
                CommandResult.Failure(CommandErrorCode.Unavailable, "No layer to paint on")
            }
        }
    }
}

private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

internal fun parseSamples(array: JsonArray): StrokeSampleBuffer? {
    if (array.isEmpty()) return null
    val samples = StrokeSampleBuffer(array.size)
    for (element in array) {
        val fields = (element as? JsonArray)?.takeIf { it.size == SAMPLE_FIELDS } ?: return null
        val values = fields.map { (it as? JsonPrimitive)?.doubleOrNull ?: return null }
        samples.add(
            x = values[0].toFloat(),
            y = values[1].toFloat(),
            pressure = values[2].toFloat().coerceIn(0f, 1f),
            eventTimeNanos = (values[3] * NANOS_PER_MILLI).toLong(),
            toolType = TOOL_TYPE_STYLUS,
        )
    }
    return samples
}

internal fun JsonObject.colorValue(
    previous: ColorValue,
    cmyk: CmykConversion? = null,
): ColorValue? {
    fun number(name: String): Float? = (this[name] as? JsonPrimitive)?.doubleOrNull?.toFloat()
    val rgb = listOf("red", "green", "blue").map(::number)
    val hsb = listOf("hue", "saturation", "brightness").map(::number)
    val inks = InkChannel.entries.map { number(it.id) }
    val hex = (this["hex"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val given = listOf(rgb.any { it != null }, hsb.any { it != null }, inks.any { it != null }, hex != null).count { it }
    if (given != 1) return null
    val alpha = number("alpha") ?: previous.alpha
    return when {
        hex != null -> ColorValue.parseHex(hex, previous)?.copy(alpha = alpha)
        rgb.all { it != null } -> ColorValue.fromRgb(rgb[0]!!, rgb[1]!!, rgb[2]!!, alpha, previous)
        hsb.all { it != null } -> ColorValue.fromHsb(hsb[0]!!, hsb[1]!!, hsb[2]!!, alpha)
        inks.all { it != null } && cmyk != null -> cmyk.colorOf(CmykInks(inks[0]!!, inks[1]!!, inks[2]!!, inks[3]!!), alpha, previous)
        else -> null
    }
}

private fun JsonObject.inks(): CmykInks? {
    val values = InkChannel.entries.map { (this[it.id] as? JsonPrimitive)?.doubleOrNull?.toFloat() }
    if (values.any { it == null }) return null
    if (listOf("red", "green", "blue", "hue", "saturation", "brightness", "hex").any { this[it] != null && this[it] != JsonNull }) return null
    return CmykInks(values[0]!!, values[1]!!, values[2]!!, values[3]!!)
}

private fun ColorValue.toJson(inksOf: (ColorValue) -> CmykInks? = { null }): JsonObject =
    buildJsonObject {
        putJsonArray("rgba") { listOf(red, green, blue, alpha).forEach { add(JsonPrimitive(it)) } }
        putJsonArray("hsb") { listOf(hue, saturation, brightness).forEach { add(JsonPrimitive(it)) } }
        put("hex", toHex())
        inksOf(this@toJson)?.let { inks ->
            putJsonArray("cmyk") { InkChannel.entries.forEach { add(JsonPrimitive(it.of(inks))) } }
        }
    }
