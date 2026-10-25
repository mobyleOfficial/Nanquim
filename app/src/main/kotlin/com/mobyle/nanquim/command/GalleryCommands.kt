package com.mobyle.nanquim.command

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import com.mobyle.nanquim.R
import com.mobyle.nanquim.color.ColorProfileLibrary
import com.mobyle.nanquim.color.isPrintProfile
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.gallery.CanvasSize
import com.mobyle.nanquim.gallery.Gallery
import com.mobyle.nanquim.gallery.GalleryException
import com.mobyle.nanquim.gallery.GallerySort
import com.mobyle.nanquim.navigation.AppDestination
import com.mobyle.nanquim.navigation.AppNavigator
import com.mobyle.nanquim.document.command.AppState
import com.mobyle.nanquim.document.command.Availability
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.document.layer.Background
import com.mobyle.nanquim.document.layer.PixelFormat
import com.mobyle.nanquim.document.layer.RgbaColor
import com.mobyle.nanquim.document.layer.maximumLayers
import com.mobyle.nanquim.formats.nanquim.CanvasUnit

object GalleryCommandIds {
    const val NEW_CANVAS = "gallery.newCanvas"
    const val NEW_CANVAS_SCREEN = "gallery.newCanvas.open"
    const val OPEN = "gallery.open"
    const val CLOSE = "gallery.close"
    const val SAVE = "document.save"
    const val DUPLICATE = "gallery.duplicate"
    const val DELETE = "gallery.delete"
    const val RENAME = "gallery.rename"
    const val STACK = "gallery.stack"
    const val UNSTACK = "gallery.unstack"
    const val MOVE_TO_STACK = "gallery.moveToStack"
    const val RENAME_STACK = "gallery.renameStack"
    const val OPEN_STACK = "gallery.openStack"
    const val SELECT_MODE = "gallery.select"
    const val TOGGLE_SELECTED = "gallery.toggleSelected"
    const val SORT = "gallery.sort"
    const val SEARCH = "gallery.search"
    const val PREVIEW = "gallery.preview"
    const val PRESET_SAVE = "gallery.preset.save"
    const val PRESET_DELETE = "gallery.preset.delete"
}

private val unitIds = CanvasUnit.entries.map { it.id }

const val MAXIMUM_CANVAS_SIDE_PX = 16_384

private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

private fun JsonObject.optionalString(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.ids(name: String): List<String> = (getValue(name) as JsonArray).map { it.jsonPrimitive.content }

private fun failure(error: GalleryException) = CommandResult.Failure(CommandErrorCode.HandlerFailed, "${error.error}: ${error.message}")

private val requiresCanvas: (AppState) -> Availability = { state ->
    if (state.hasOpenDocument) Availability.Enabled else Availability.Disabled("No open document")
}

fun registerGalleryCommands(
    registry: CommandRegistry,
    gallery: Gallery,
    canvasSession: CanvasSession,
    navigator: AppNavigator,
    memoryBudgetBytes: () -> Long,
    copyName: (String) -> String,
    stackName: () -> String,
) {
    fun register(
        id: String,
        titleRes: Int,
        parameters: List<ParameterSpec> = emptyList(),
        availability: (AppState) -> Availability = { Availability.Enabled },
        handler: suspend (JsonObject) -> CommandResult,
    ) = registry.register(CommandSpec(id, titleRes, CommandCategory.Gallery, parameters, undoable = false, availability = availability)) { _, arguments ->
        try {
            handler(arguments)
        } catch (error: GalleryException) {
            failure(error)
        }
    }

    val idsParameter = ParameterSpec("ids", ParameterType.Array)

    register(GalleryCommandIds.NEW_CANVAS_SCREEN, R.string.command_gallery_new_canvas_open) {
        navigator.open(AppDestination.NewCanvas)
        CommandResult.Success()
    }

    register(
        GalleryCommandIds.NEW_CANVAS,
        R.string.command_gallery_new_canvas,
        listOf(
            ParameterSpec("width", ParameterType.Number, minimum = 0.001),
            ParameterSpec("height", ParameterType.Number, minimum = 0.001),
            ParameterSpec("unit", ParameterType.Enumeration, JsonPrimitive(CanvasUnit.Pixels.id), allowedValues = unitIds),
            ParameterSpec("dpi", ParameterType.Number, JsonPrimitive(132.0), minimum = 1.0, maximum = 4800.0),
            ParameterSpec("name", ParameterType.String, JsonNull),
            ParameterSpec("background", ParameterType.Array, JsonNull),
            ParameterSpec("backgroundHidden", ParameterType.Boolean, JsonPrimitive(false)),
            ParameterSpec("bitsPerChannel", ParameterType.Integer, JsonPrimitive(8), minimum = 8.0, maximum = 16.0),
            ParameterSpec("profileId", ParameterType.String, JsonPrimitive(ColorProfileLibrary.SRGB_ID)),
        ),
    ) { arguments ->
        val profiles = canvasSession.colorProfiles
        val profileEntry =
            profiles.entry(arguments.string("profileId"))
                ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "No profile ${arguments.string("profileId")}")
        val (colorProfile, cmykProfile) =
            when {
                profileEntry.info.isDocumentProfile -> profiles.documentProfile(profileEntry.id) to null
                profileEntry.isPrintProfile ->
                    profiles.documentProfile(ColorProfileLibrary.PRINT_WORKING_PROFILE_ID) to profiles.printProfile(profileEntry.id)
                else ->
                    return@register CommandResult.Failure(
                        CommandErrorCode.InvalidArguments,
                        "${profileEntry.name} cannot be a canvas profile",
                    )
            }
        val unit = CanvasUnit.fromId(arguments.string("unit")) ?: CanvasUnit.Pixels
        val pixelFormat =
            PixelFormat.fromBits(arguments.getValue("bitsPerChannel").jsonPrimitive.int)
                ?: return@register CommandResult.Failure(
                    CommandErrorCode.InvalidArguments,
                    "bitsPerChannel is 8 or 16",
                )
        val dpi = arguments.getValue("dpi").jsonPrimitive.double
        val size = CanvasSize(arguments.getValue("width").jsonPrimitive.double, arguments.getValue("height").jsonPrimitive.double, unit, dpi)
        if (size.widthPx > MAXIMUM_CANVAS_SIDE_PX || size.heightPx > MAXIMUM_CANVAS_SIDE_PX) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Canvas larger than $MAXIMUM_CANVAS_SIDE_PX px")
        }
        val layerLimit = maximumLayers(memoryBudgetBytes(), size.widthPx, size.heightPx, pixelFormat.bytesPerPixel)
        if (layerLimit < MINIMUM_LAYERS_FOR_NEW_CANVAS) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Canvas too large for this device's memory")
        }
        val color =
            (arguments["background"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.doubleOrNull?.toFloat() }
                ?.takeIf { it.size >= 3 }
                ?.let { RgbaColor(it[0], it[1], it[2], it.getOrElse(3) { 1f }) } ?: RgbaColor.WHITE
        val hidden = (arguments["backgroundHidden"] as? JsonPrimitive)?.contentOrNull == "true"
        val id =
            gallery.newCanvas(
                size.widthPx,
                size.heightPx,
                arguments.optionalString("name"),
                dpi,
                unit,
                Background(color, hidden),
                pixelFormat,
                colorProfile = colorProfile,
                cmykProfile = cmykProfile,
            )
        CommandResult.Success(buildJsonObject { put("id", id) })
    }

    register(GalleryCommandIds.OPEN, R.string.command_gallery_open, listOf(ParameterSpec("id", ParameterType.String))) { arguments ->
        gallery.open(arguments.string("id"))
        CommandResult.Success()
    }

    register(GalleryCommandIds.CLOSE, R.string.command_gallery_close) {
        gallery.closeCanvas()
        CommandResult.Success()
    }

    registry.register(
        CommandSpec(GalleryCommandIds.SAVE, R.string.command_document_save, CommandCategory.Canvas, undoable = false, availability = requiresCanvas),
    ) { _, _ ->
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        canvasSession.persistence.saveNow(withThumbnail = true) { ok -> done.complete(ok) }
        if (done.await()) CommandResult.Success() else CommandResult.Failure(CommandErrorCode.HandlerFailed, canvasSession.persistence.lastError ?: "Save failed")
    }

    register(GalleryCommandIds.DUPLICATE, R.string.command_gallery_duplicate, listOf(idsParameter)) { arguments ->
        val created = gallery.duplicate(arguments.ids("ids"), copyName)
        CommandResult.Success(buildJsonObject { putJsonArray("ids") { created.forEach { add(JsonPrimitive(it)) } } })
    }

    register(GalleryCommandIds.DELETE, R.string.command_gallery_delete, listOf(idsParameter)) { arguments ->
        gallery.delete(arguments.ids("ids"))
        CommandResult.Success()
    }

    register(
        GalleryCommandIds.RENAME,
        R.string.command_gallery_rename,
        listOf(ParameterSpec("id", ParameterType.String), ParameterSpec("name", ParameterType.String)),
    ) { arguments ->
        gallery.rename(arguments.string("id"), arguments.string("name"))
        CommandResult.Success()
    }

    register(GalleryCommandIds.STACK, R.string.command_gallery_stack, listOf(idsParameter, ParameterSpec("name", ParameterType.String, JsonNull))) { arguments ->
        val stackId =
            gallery.stack(arguments.ids("ids"), arguments.optionalString("name") ?: stackName())
                ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "A stack needs at least two artworks")
        CommandResult.Success(buildJsonObject { put("stackId", stackId) })
    }

    register(GalleryCommandIds.UNSTACK, R.string.command_gallery_unstack, listOf(idsParameter)) { arguments ->
        gallery.moveToStack(arguments.ids("ids"), null)
        CommandResult.Success()
    }

    register(
        GalleryCommandIds.MOVE_TO_STACK,
        R.string.command_gallery_move_to_stack,
        listOf(idsParameter, ParameterSpec("stackId", ParameterType.String)),
    ) { arguments ->
        gallery.moveToStack(arguments.ids("ids"), arguments.string("stackId"))
        CommandResult.Success()
    }

    register(
        GalleryCommandIds.RENAME_STACK,
        R.string.command_gallery_rename_stack,
        listOf(ParameterSpec("stackId", ParameterType.String), ParameterSpec("name", ParameterType.String)),
    ) { arguments ->
        gallery.renameStack(arguments.string("stackId"), arguments.string("name"))
        CommandResult.Success()
    }

    register(GalleryCommandIds.OPEN_STACK, R.string.command_gallery_open_stack, listOf(ParameterSpec("stackId", ParameterType.String, JsonNull))) { arguments ->
        gallery.openStackId = arguments.optionalString("stackId")
        gallery.changeSelecting(false)
        CommandResult.Success()
    }

    register(GalleryCommandIds.SELECT_MODE, R.string.command_gallery_select, listOf(ParameterSpec("enabled", ParameterType.Boolean))) { arguments ->
        gallery.changeSelecting(arguments.getValue("enabled").jsonPrimitive.content == "true")
        CommandResult.Success()
    }

    register(GalleryCommandIds.TOGGLE_SELECTED, R.string.command_gallery_toggle_selected, listOf(ParameterSpec("id", ParameterType.String))) { arguments ->
        if (!gallery.selecting) gallery.changeSelecting(true)
        gallery.toggleSelected(arguments.string("id"))
        CommandResult.Success()
    }

    register(
        GalleryCommandIds.SORT,
        R.string.command_gallery_sort,
        listOf(ParameterSpec("order", ParameterType.Enumeration, allowedValues = GallerySort.entries.map { it.id })),
    ) { arguments ->
        gallery.changeSort(GallerySort.fromId(arguments.string("order")) ?: GallerySort.Date)
        CommandResult.Success()
    }

    register(GalleryCommandIds.SEARCH, R.string.command_gallery_search, listOf(ParameterSpec("query", ParameterType.String))) { arguments ->
        gallery.searchQuery = arguments.string("query")
        CommandResult.Success()
    }

    register(GalleryCommandIds.PREVIEW, R.string.command_gallery_preview, listOf(ParameterSpec("id", ParameterType.String, JsonNull))) { arguments ->
        gallery.previewId = arguments.optionalString("id")
        CommandResult.Success()
    }

    register(
        GalleryCommandIds.PRESET_SAVE,
        R.string.command_gallery_preset_save,
        listOf(
            ParameterSpec("id", ParameterType.String, JsonNull),
            ParameterSpec("name", ParameterType.String),
            ParameterSpec("width", ParameterType.Number, minimum = 0.001),
            ParameterSpec("height", ParameterType.Number, minimum = 0.001),
            ParameterSpec("unit", ParameterType.Enumeration, JsonPrimitive(CanvasUnit.Pixels.id), allowedValues = unitIds),
            ParameterSpec("dpi", ParameterType.Number, JsonPrimitive(132.0), minimum = 1.0, maximum = 4800.0),
        ),
    ) { arguments ->
        val size =
            CanvasSize(
                arguments.getValue("width").jsonPrimitive.double,
                arguments.getValue("height").jsonPrimitive.double,
                CanvasUnit.fromId(arguments.string("unit")) ?: CanvasUnit.Pixels,
                arguments.getValue("dpi").jsonPrimitive.double,
            )
        gallery.savePreset(arguments.optionalString("id"), arguments.string("name"), size)
        CommandResult.Success()
    }

    register(GalleryCommandIds.PRESET_DELETE, R.string.command_gallery_preset_delete, listOf(ParameterSpec("id", ParameterType.String))) { arguments ->
        gallery.deletePreset(arguments.string("id"))
        CommandResult.Success()
    }
}

const val MINIMUM_LAYERS_FOR_NEW_CANVAS = 8
