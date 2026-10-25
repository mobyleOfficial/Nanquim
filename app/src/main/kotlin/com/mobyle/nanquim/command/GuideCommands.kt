package com.mobyle.nanquim.command

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.document.command.AppState
import com.mobyle.nanquim.document.command.Availability
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.document.guide.GridGuide
import com.mobyle.nanquim.document.guide.GuideSettings
import com.mobyle.nanquim.document.guide.GuideType
import com.mobyle.nanquim.document.guide.PerspectiveGuide
import com.mobyle.nanquim.document.guide.SymmetryGuide
import com.mobyle.nanquim.document.guide.SymmetryKind
import com.mobyle.nanquim.document.guide.VanishingPoint

object GuideCommandIds {
    const val SET = "guide.set"
    const val TOGGLE = "guide.toggle"
    const val PERSPECTIVE_ADD = "guide.perspective.addPoint"
    const val PERSPECTIVE_MOVE = "guide.perspective.movePoint"
    const val PERSPECTIVE_REMOVE = "guide.perspective.removePoint"
}

private val requiresDocument: (AppState) -> Availability = { state ->
    if (state.hasOpenDocument) Availability.Enabled else Availability.Disabled("No open document")
}

fun registerGuideCommands(
    registry: CommandRegistry,
    canvas: CanvasSession,
) {
    fun number(
        name: String,
        minimum: Double? = null,
        maximum: Double? = null,
    ) = ParameterSpec(name, ParameterType.Number, JsonNull, minimum, maximum)
    registry.register(
        CommandSpec(
            id = GuideCommandIds.SET,
            titleRes = R.string.command_guide_set,
            category = CommandCategory.View,
            parameters =
                listOf(
                    ParameterSpec("type", ParameterType.Enumeration, JsonNull, allowedValues = GuideType.entries.map { it.id }),
                    ParameterSpec("visible", ParameterType.Boolean, JsonNull),
                    number("hue", 0.0, 360.0),
                    number("opacity", 0.0, 1.0),
                    number("thickness", GuideSettings.MINIMUM_THICKNESS.toDouble(), GuideSettings.MAXIMUM_THICKNESS.toDouble()),
                    number("gridSize", GridGuide.MINIMUM_SIZE, GridGuide.MAXIMUM_SIZE),
                    number("rotation"),
                    number("originX"),
                    number("originY"),
                    ParameterSpec("symmetry", ParameterType.Enumeration, JsonNull, allowedValues = SymmetryKind.entries.map { it.id }),
                    ParameterSpec(
                        "segments",
                        ParameterType.Integer,
                        JsonNull,
                        SymmetryGuide.MINIMUM_SEGMENTS.toDouble(),
                        SymmetryGuide.MAXIMUM_SEGMENTS.toDouble(),
                    ),
                    ParameterSpec("rotational", ParameterType.Boolean, JsonNull),
                    number("centerX"),
                    number("centerY"),
                    number("symmetryRotation"),
                ),
            undoable = false,
            availability = requiresDocument,
        ),
    ) { _, arguments ->
        fun value(name: String): Double? = (arguments[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.doubleOrNull
        val current = canvas.guides
        val grid =
            current.grid.copy(
                size = value("gridSize") ?: current.grid.size,
                rotation = value("rotation") ?: current.grid.rotation,
                originX = value("originX") ?: current.grid.originX,
                originY = value("originY") ?: current.grid.originY,
            )
        val symmetry =
            current.symmetry.copy(
                kind = (arguments["symmetry"] as? JsonPrimitive)?.contentOrNull?.let(SymmetryKind::fromId) ?: current.symmetry.kind,
                segments = value("segments")?.toInt() ?: current.symmetry.segments,
                rotational = (arguments["rotational"] as? JsonPrimitive)?.booleanOrNull ?: current.symmetry.rotational,
                centerX = value("centerX") ?: current.symmetry.centerX,
                centerY = value("centerY") ?: current.symmetry.centerY,
                rotation = value("symmetryRotation") ?: current.symmetry.rotation,
            )
        canvas.changeGuides(
            current.copy(
                type = (arguments["type"] as? JsonPrimitive)?.contentOrNull?.let(GuideType::fromId) ?: current.type,
                visible = (arguments["visible"] as? JsonPrimitive)?.booleanOrNull ?: current.visible,
                hue = value("hue")?.toFloat()?.let { if (it >= 360f) 0f else it } ?: current.hue,
                opacity = value("opacity")?.toFloat() ?: current.opacity,
                thickness = value("thickness")?.toFloat() ?: current.thickness,
                grid = grid,
                symmetry = symmetry,
            ),
        )
        CommandResult.Success(canvas.guides.toJson())
    }
    registerPerspectiveCommands(registry, canvas)
    registry.register(
        CommandSpec(
            id = GuideCommandIds.TOGGLE,
            titleRes = R.string.command_guide_toggle,
            category = CommandCategory.View,
            parameters = listOf(ParameterSpec("visible", ParameterType.Boolean, JsonNull)),
            undoable = false,
            availability = requiresDocument,
        ),
    ) { _, arguments ->
        val visible = (arguments["visible"] as? JsonPrimitive)?.booleanOrNull ?: !canvas.guides.visible
        canvas.changeGuides(canvas.guides.copy(visible = visible))
        CommandResult.Success(canvas.guides.toJson())
    }
}

private fun registerPerspectiveCommands(
    registry: CommandRegistry,
    canvas: CanvasSession,
) {
    val coordinates = listOf(ParameterSpec("x", ParameterType.Number), ParameterSpec("y", ParameterType.Number))
    val index = ParameterSpec("index", ParameterType.Integer, minimum = 0.0, maximum = (PerspectiveGuide.MAXIMUM_POINTS - 1).toDouble())

    fun number(
        arguments: Map<String, Any?>,
        name: String,
    ) = (arguments[name] as JsonPrimitive).double

    fun apply(change: PerspectiveGuide?): CommandResult {
        change ?: return CommandResult.Failure(CommandErrorCode.InvalidArguments, "No such vanishing point, or already three")
        canvas.changeGuides(canvas.guides.copy(type = GuideType.Perspective, perspective = change))
        return CommandResult.Success(canvas.guides.toJson())
    }
    fun spec(
        id: String,
        titleRes: Int,
        parameters: List<ParameterSpec>,
    ) = CommandSpec(id, titleRes, CommandCategory.View, parameters, undoable = false, availability = requiresDocument)
    registry.register(spec(GuideCommandIds.PERSPECTIVE_ADD, R.string.command_guide_perspective_add, coordinates)) { _, arguments ->
        val result = apply(canvas.guides.perspective.adding(VanishingPoint(number(arguments, "x"), number(arguments, "y"))))
        if (result is CommandResult.Success) canvas.selectVanishingPoint(canvas.guides.perspective.points.lastIndex)
        result
    }
    registry.register(spec(GuideCommandIds.PERSPECTIVE_MOVE, R.string.command_guide_perspective_move, listOf(index) + coordinates)) { _, arguments ->
        val point = VanishingPoint(number(arguments, "x"), number(arguments, "y"))
        apply(canvas.guides.perspective.moving(number(arguments, "index").toInt(), point))
    }
    registry.register(spec(GuideCommandIds.PERSPECTIVE_REMOVE, R.string.command_guide_perspective_remove, listOf(index))) { _, arguments ->
        val result = apply(canvas.guides.perspective.removing(number(arguments, "index").toInt()))
        if (result is CommandResult.Success) canvas.selectVanishingPoint(null)
        result
    }
}

fun GuideSettings.toJson(): JsonObject =
    buildJsonObject {
        put("type", type.id)
        put("visible", visible)
        put("hue", hue)
        put("opacity", opacity)
        put("thickness", thickness)
        put("gridSize", grid.size)
        put("rotation", grid.rotation)
        put("originX", grid.originX)
        put("originY", grid.originY)
        put("symmetry", symmetry.kind.id)
        put("segments", symmetry.segments)
        put("rotational", symmetry.rotational)
        put("centerX", symmetry.centerX)
        put("centerY", symmetry.centerY)
        put("symmetryRotation", symmetry.rotation)
        put("points", JsonArray(perspective.points.map { JsonArray(listOf(JsonPrimitive(it.x), JsonPrimitive(it.y))) }))
    }
