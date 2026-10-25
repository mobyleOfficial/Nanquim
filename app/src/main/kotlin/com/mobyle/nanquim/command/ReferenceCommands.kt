package com.mobyle.nanquim.command

import android.content.ContentResolver
import androidx.core.net.toUri
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
import com.mobyle.nanquim.document.reference.ReferenceCorner
import com.mobyle.nanquim.document.reference.ReferenceMode
import com.mobyle.nanquim.document.reference.ReferenceSettings
import com.mobyle.nanquim.document.reference.ReferenceView

object ReferenceCommandIds {
    const val TOGGLE = "reference.toggle"
    const val SET = "reference.set"
    const val IMPORT_IMAGE = "reference.importImage"
    const val PICK_COLOR = "reference.pickColor"
}

private val requiresDocument: (AppState) -> Availability = { state ->
    if (state.hasOpenDocument) Availability.Enabled else Availability.Disabled("No open document")
}

fun registerReferenceCommands(
    registry: CommandRegistry,
    canvas: CanvasSession,
    contentResolver: ContentResolver,
) {
    val reference = canvas.reference

    fun spec(
        id: String,
        titleRes: Int,
        parameters: List<ParameterSpec> = emptyList(),
    ) = CommandSpec(id, titleRes, CommandCategory.View, parameters, undoable = false, availability = requiresDocument)

    fun state() = CommandResult.Success(reference.settings.toJson(reference.image != null))

    registry.register(
        spec(
            ReferenceCommandIds.TOGGLE,
            R.string.command_reference_toggle,
            listOf(ParameterSpec("visible", ParameterType.Boolean, JsonNull)),
        ),
    ) { _, arguments ->
        val visible = (arguments["visible"] as? JsonPrimitive)?.booleanOrNull ?: !reference.settings.visible
        reference.change(reference.settings.copy(visible = visible))
        state()
    }
    registry.register(
        spec(
            ReferenceCommandIds.SET,
            R.string.command_reference_set,
            listOf(
                ParameterSpec("mode", ParameterType.Enumeration, JsonNull, allowedValues = ReferenceMode.entries.map { it.id }),
                ParameterSpec("opacity", ParameterType.Number, JsonNull, ReferenceSettings.MINIMUM_OPACITY.toDouble(), 1.0),
                ParameterSpec("x", ParameterType.Number, JsonNull, 0.0, 1.0),
                ParameterSpec("y", ParameterType.Number, JsonNull, 0.0, 1.0),
                ParameterSpec("width", ParameterType.Number, JsonNull, ReferenceSettings.MINIMUM_SIDE),
                ParameterSpec("height", ParameterType.Number, JsonNull, ReferenceSettings.MINIMUM_SIDE),
                ParameterSpec(
                    "docked",
                    ParameterType.Enumeration,
                    JsonNull,
                    allowedValues = ReferenceCorner.entries.map { it.id } + NOT_DOCKED,
                ),
                ParameterSpec("zoom", ParameterType.Number, JsonNull, ReferenceView.MINIMUM_ZOOM, ReferenceView.MAXIMUM_ZOOM),
                ParameterSpec("centerX", ParameterType.Number, JsonNull, 0.0, 1.0),
                ParameterSpec("centerY", ParameterType.Number, JsonNull, 0.0, 1.0),
            ),
        ),
    ) { _, arguments ->
        fun number(name: String) = (arguments[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.doubleOrNull

        fun text(name: String) = (arguments[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
        var settings = reference.settings
        text("mode")?.let { settings = settings.copy(mode = ReferenceMode.fromId(it)!!) }
        number("opacity")?.let { settings = settings.copy(opacity = it.toFloat()) }
        number("x")?.let { settings = settings.copy(x = it, docked = null) }
        number("y")?.let { settings = settings.copy(y = it, docked = null) }
        number("width")?.let { settings = settings.copy(width = it) }
        number("height")?.let { settings = settings.copy(height = it) }
        text("docked")?.let { settings = settings.copy(docked = ReferenceCorner.fromId(it)) }
        val view = settings.view
        settings =
            settings.withView(
                ReferenceView(number("zoom") ?: view.zoom, number("centerX") ?: view.centerX, number("centerY") ?: view.centerY),
            )
        reference.change(settings)
        state()
    }
    registry.register(
        spec(ReferenceCommandIds.IMPORT_IMAGE, R.string.command_reference_import_image, listOf(ParameterSpec("uri", ParameterType.String))),
    ) { _, arguments ->
        val uri = (arguments.getValue("uri") as JsonPrimitive).content.toUri()
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
        }
        reference.importImage(contentResolver, uri).fold(
            onSuccess = { state() },
            onFailure = { CommandResult.Failure(CommandErrorCode.InvalidArguments, "Cannot read the image: ${it.message}") },
        )
    }
    registry.register(
        spec(
            ReferenceCommandIds.PICK_COLOR,
            R.string.command_reference_pick_color,
            listOf(ParameterSpec("x", ParameterType.Number), ParameterSpec("y", ParameterType.Number)),
        ),
    ) { _, arguments ->
        fun number(name: String) = (arguments.getValue(name) as JsonPrimitive).doubleOrNull ?: 0.0
        val color =
            reference.imageColor(number("x"), number("y"))
                ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No reference image pixel there")
        canvas.primaryColor = color
        CommandResult.Success(
            buildJsonObject { put("color", JsonArray(listOf(color.red, color.green, color.blue).map { JsonPrimitive(it) })) },
        )
    }
}

private const val NOT_DOCKED = "none"

private fun ReferenceSettings.toJson(imageLoaded: Boolean): JsonObject =
    buildJsonObject {
        put("visible", visible)
        put("mode", mode.id)
        put("opacity", opacity)
        put("x", x)
        put("y", y)
        put("width", width)
        put("height", height)
        put("docked", docked?.id ?: NOT_DOCKED)
        put("zoom", view.zoom)
        put("centerX", view.centerX)
        put("centerY", view.centerY)
        put("image", imageLoaded)
    }
