package com.mobyle.nanquim.command

import android.content.ContentResolver
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import com.mobyle.nanquim.R
import com.mobyle.nanquim.brush.ActiveBrushes
import com.mobyle.nanquim.brush.BrushAbout
import com.mobyle.nanquim.brush.BrushEditorSection
import com.mobyle.nanquim.brush.BrushFileKind
import com.mobyle.nanquim.brush.BrushFiles
import com.mobyle.nanquim.brush.BrushUiState
import com.mobyle.nanquim.brush.ProcreateBrushImport
import com.mobyle.nanquim.brush.StampImport
import com.mobyle.nanquim.brush.StampMode
import com.mobyle.nanquim.brush.TextureImport
import com.mobyle.nanquim.brush.TextureSlot
import com.mobyle.nanquim.brush.library.BrushGroup
import com.mobyle.nanquim.brush.library.BrushLibrary
import com.mobyle.nanquim.brush.library.BrushTool
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.gallery.ArtworkTransfer
import com.mobyle.nanquim.navigation.AppDestination
import com.mobyle.nanquim.navigation.AppNavigator
import com.mobyle.nanquim.document.brush.InvalidBrushException
import com.mobyle.nanquim.document.command.AppState
import com.mobyle.nanquim.document.command.Availability
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandHandler
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.canvas.input.CanvasTool
import com.mobyle.nanquim.canvas.input.GestureConfiguration
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.document.layer.LayerOperationException
import com.mobyle.nanquim.engine.BrushPad
import com.mobyle.nanquim.formats.NanquimFileType
import com.mobyle.nanquim.formats.interchange.ImportJsonException
import com.mobyle.nanquim.formats.interchange.ImportWarning
import java.io.File
import java.io.IOException
import java.time.Instant

object BrushCommandIds {
    const val BRUSH_SELECT = "brush.select"
    const val SHORTCUT_SET = "brush.shortcut.set"
    const val SHORTCUT_TOGGLE = "brush.shortcut.toggle"
    const val BRUSH_RESET = "brush.reset"
    const val BRUSH_DUPLICATE = "brush.duplicate"
    const val BRUSH_DELETE = "brush.delete"
    const val BRUSH_SET_PINNED = "brush.setPinned"
    const val BRUSH_MOVE = "brush.move"
    const val BRUSH_IMPORT = "brush.import"
    const val BRUSH_EXPORT = "brush.export"
    const val BRUSH_CREATE_STAMP = "brush.createStamp"
    const val LIBRARY_OPEN = "brush.library.open"
    const val LIBRARY_CLOSE = "brush.library.close"
    const val LIBRARY_SHOW_GROUP = "brush.library.showGroup"
    const val LIBRARY_SHOW_PREVIEW = "brush.library.showPreview"
    const val GROUP_CREATE = "brush.group.create"
    const val GROUP_RENAME = "brush.group.rename"
    const val GROUP_DELETE = "brush.group.delete"
    const val GROUP_MOVE = "brush.group.move"
    const val EDITOR_OPEN = "brush.editor.open"
    const val EDITOR_SHOW_SECTION = "brush.editor.showSection"
    const val EDITOR_SET_VALUE = "brush.editor.setValue"
    const val EDITOR_REMOVE_VALUE = "brush.editor.removeValue"
    const val EDITOR_IMPORT_TEXTURE = "brush.editor.importTexture"
    const val EDITOR_REMOVE_TEXTURE = "brush.editor.removeTexture"
    const val EDITOR_CLEAR_PAD = "brush.editor.clearPad"
    const val EDITOR_SET_PAD_DARK = "brush.editor.setPadDark"
    const val EDITOR_SET_PAD_COLOR = "brush.editor.setPadColor"
    const val EDITOR_AUTO_REPEAT = "brush.editor.autoRepeat"
    const val EDITOR_CANCEL_AUTO_REPEAT = "brush.editor.cancelAutoRepeat"
    const val EDITOR_UNDO_TEXTURE = "brush.editor.undoTexture"
    const val EDITOR_PASTE_TEXTURE = "brush.editor.pasteTexture"
    const val EDITOR_USE_LIBRARY_TEXTURE = "brush.editor.useLibraryTexture"
    const val EDITOR_IMPORT_AUTHOR_PICTURE = "brush.editor.importAuthorPicture"
    const val EDITOR_REMOVE_AUTHOR_PICTURE = "brush.editor.removeAuthorPicture"
    const val EDITOR_SET_SIGNATURE = "brush.editor.setSignature"
    const val EDITOR_CREATE_RESET_POINT = "brush.editor.createResetPoint"
    const val EDITOR_RESTORE_RESET_POINT = "brush.editor.restoreResetPoint"
    const val EDITOR_RESET_ALL = "brush.editor.resetAll"
    const val EDITOR_SET_PAD_SIZE = "brush.editor.setPadSize"
    const val EDITOR_DONE = "brush.editor.done"
    const val EDITOR_CANCEL = "brush.editor.cancel"
    const val SLIDER_PRESET_ADD = "brush.sliderPreset.add"
    const val SLIDER_PRESET_REMOVE = "brush.sliderPreset.remove"
    const val SETTINGS_PRESSURE_CURVE = "settings.pressure.setCurve"
    const val SETTINGS_SMOOTHING = "settings.pressure.setSmoothing"
}

private const val MINIMUM_PATCH_SCALE = 0.05f
private const val MAXIMUM_SEAM_OVERLAP = 0.5f

const val STAMP_SOURCE_IMAGE = "image"
const val STAMP_SOURCE_SELECTION = "selection"

object SliderNames {
    const val SIZE = "size"
    const val OPACITY = "opacity"
}

private val brushIdParameter = ParameterSpec("brushId", ParameterType.String)
private val groupIdParameter = ParameterSpec("groupId", ParameterType.String)
private val toolParameter =
    ParameterSpec(
        "tool",
        ParameterType.Enumeration,
        allowedValues = ActiveBrushes.PAINTING_TOOLS.map { it.key },
        defaultValue = JsonPrimitive(BrushTool.Paint.key),
    )
private val sliderParameter = ParameterSpec("slider", ParameterType.Enumeration, allowedValues = listOf(SliderNames.SIZE, SliderNames.OPACITY))
private val textureSlotParameter =
    ParameterSpec("slot", ParameterType.Enumeration, allowedValues = TextureSlot.entries.map { it.name.lowercase() })

fun registerBrushCommands(
    registry: CommandRegistry,
    session: CanvasSession,
    ui: BrushUiState,
    navigator: AppNavigator,
    contentResolver: ContentResolver,
    shareSheet: ShareSheet,
    cacheDirectory: File,
    groupName: (BrushGroup) -> String,
    onBrushImportReport: (List<ImportWarning>) -> Unit,
    stampName: () -> String,
    clipboardImage: () -> Uri? = { null },
) {
    val library = ui.library
    val brushes = ui.brushes

    fun register(
        id: String,
        titleRes: Int,
        parameters: List<ParameterSpec> = emptyList(),
        category: CommandCategory = CommandCategory.Brush,
        availability: (AppState) -> Availability = { Availability.Enabled },
        handler: CommandHandler,
    ) = registry.register(CommandSpec(id, titleRes, category, parameters, undoable = false, availability = availability), handler)

    val editorOpen: (AppState) -> Availability = {
        if (ui.editor != null) Availability.Enabled else Availability.Disabled("The brush editor is not open")
    }

    register(
        BrushCommandIds.BRUSH_IMPORT,
        R.string.command_brush_import,
        listOf(ParameterSpec("uri", ParameterType.String)),
    ) { _, arguments ->
        val uri = arguments.getValue("uri").jsonPrimitive.content.toUri()
        if (uri.scheme != "content") {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
        }
        importBrushFile(ui, contentResolver, uri, onBrushImportReport)
    }

    register(
        BrushCommandIds.BRUSH_EXPORT,
        R.string.command_brush_export,
        listOf(
            ParameterSpec("brushId", ParameterType.String, JsonNull),
            ParameterSpec("groupId", ParameterType.String, JsonNull),
            ParameterSpec("uri", ParameterType.String, JsonNull),
        ),
    ) { _, arguments ->
        val brushId = (arguments["brushId"] as? JsonPrimitive)?.contentOrNull
        val groupId = (arguments["groupId"] as? JsonPrimitive)?.contentOrNull
        val uri = (arguments["uri"] as? JsonPrimitive)?.contentOrNull?.toUri()
        if ((brushId == null) == (groupId == null)) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Give either brushId or groupId")
        }
        if (uri != null && uri.scheme != "content") {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
        }
        val (bytes, fileName, mimeType) =
            if (brushId != null) {
                val entry = library.entry(brushId) ?: return@register unknownBrush(brushId)
                val fileName = BrushFiles.fileName(entry.name, NanquimFileType.Brush.extension)
                Triple(library.exportBrush(brushId), fileName, NanquimFileType.Brush.mimeType)
            } else {
                val group =
                    library.groups.first().firstOrNull { it.id == groupId }
                        ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Unknown group $groupId")
                val name = groupName(group)
                val fileName = BrushFiles.fileName(name, NanquimFileType.BrushSet.extension)
                Triple(library.exportGroup(group.id, name), fileName, NanquimFileType.BrushSet.mimeType)
            }
        try {
            withContext(Dispatchers.IO) {
                if (uri != null) {
                    val output = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write $uri")
                    output.use { it.write(bytes) }
                } else {
                    val folder = File(cacheDirectory, ArtworkTransfer.EXPORT_FOLDER).apply { mkdirs() }
                    val file = File(folder, fileName)
                    file.writeBytes(bytes)
                    withContext(Dispatchers.Main) { shareSheet.share(listOf(file), mimeType) }
                }
            }
            CommandResult.Success(JsonObject(mapOf("fileName" to JsonPrimitive(fileName))))
        } catch (error: IOException) {
            CommandResult.Failure(CommandErrorCode.HandlerFailed, "WriteFailed: ${error.message}")
        }
    }

    register(
        BrushCommandIds.BRUSH_CREATE_STAMP,
        R.string.command_brush_create_stamp,
        listOf(
            ParameterSpec("source", ParameterType.Enumeration, allowedValues = listOf(STAMP_SOURCE_IMAGE, STAMP_SOURCE_SELECTION)),
            ParameterSpec("uri", ParameterType.String, JsonNull),
            ParameterSpec("mode", ParameterType.Enumeration, JsonNull, allowedValues = StampMode.entries.map { it.key }),
            ParameterSpec("groupId", ParameterType.String, JsonNull),
            ParameterSpec("name", ParameterType.String, JsonNull),
        ),
    ) { _, arguments ->
        val fromImage = arguments.getValue("source").jsonPrimitive.content == STAMP_SOURCE_IMAGE
        val mode =
            (arguments["mode"] as? JsonPrimitive)?.contentOrNull?.let(StampMode::of)
                ?: if (fromImage) StampMode.Luminance else StampMode.Alpha
        val image =
            if (fromImage) {
                val uri =
                    (arguments["uri"] as? JsonPrimitive)?.contentOrNull?.toUri()
                        ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "An image source needs a uri")
                if (uri.scheme != "content") {
                    return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
                }
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let(StampImport::decode)
                } ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "The image cannot be read")
            } else {
                try {
                    session.readSelectedPixels()
                } catch (error: LayerOperationException) {
                    return@register CommandResult.Failure(CommandErrorCode.Unavailable, error.message ?: "No pixels to read")
                } ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No open document")
            }
        val (pixels, width, height) = image
        val png =
            withContext(Dispatchers.Default) {
                StampImport.squareTip(pixels, width, height, mode)?.let(StampImport::png)
            } ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Nothing to stamp: the image paints no pixel")
        val name = (arguments["name"] as? JsonPrimitive)?.contentOrNull ?: stampName()
        val groupId =
            (arguments["groupId"] as? JsonPrimitive)?.contentOrNull
                ?: ui.shownGroupId
                ?: brushes[BrushTool.Paint]?.brushId?.let { library.entry(it)?.groupId }
        val brushId = library.addBrush(StampImport.brush(name, png, mode), groupId)
        brushes.select(BrushTool.Paint, brushId).join()
        CommandResult.Success(JsonObject(mapOf("brushId" to JsonPrimitive(brushId))))
    }

    register(BrushCommandIds.BRUSH_SELECT, R.string.command_brush_select, listOf(brushIdParameter, toolParameter)) { _, arguments ->
        val brushId = arguments.brushId()
        if (library.entry(brushId) == null) return@register unknownBrush(brushId)
        brushes.select(arguments.tool(), brushId).join()
        CommandResult.Success()
    }
    var brushBeforeShortcut: String? = null
    register(
        BrushCommandIds.SHORTCUT_SET,
        R.string.command_brush_shortcut_set,
        listOf(
            ParameterSpec("brushId", ParameterType.String, JsonNull),
            ParameterSpec("clear", ParameterType.Boolean, JsonPrimitive(false)),
        ),
    ) { _, arguments ->
        val settings = ui.inputSettings
        if ((arguments["clear"] as? JsonPrimitive)?.booleanOrNull == true) {
            settings.updateGestures(settings.gestures.copy(shortcutBrush = null))
            return@register CommandResult.Success()
        }
        val brushId =
            (arguments["brushId"] as? JsonPrimitive)?.contentOrNull
                ?: brushes[BrushTool.Paint]?.brushId
                ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No brush is selected")
        val entry = library.entry(brushId) ?: return@register unknownBrush(brushId)
        settings.updateGestures(settings.gestures.copy(shortcutBrush = GestureConfiguration.ShortcutBrush(brushId, entry.name)))
        CommandResult.Success(JsonObject(mapOf("brushId" to JsonPrimitive(brushId), "name" to JsonPrimitive(entry.name))))
    }
    register(BrushCommandIds.SHORTCUT_TOGGLE, R.string.command_brush_shortcut_toggle) { context, _ ->
        val assigned = ui.inputSettings.gestures.shortcutBrush
        val shortcut =
            assigned?.id
                ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No shortcut brush is assigned")
        if (library.entry(shortcut) == null) return@register unknownBrush(shortcut)
        val current = brushes[BrushTool.Paint]?.brushId
        val target =
            if (current == shortcut && session.canvasTool == CanvasTool.Brush) {
                val previous = brushBeforeShortcut?.takeIf { library.entry(it) != null }
                previous ?: return@register CommandResult.Success()
            } else {
                brushBeforeShortcut = current?.takeIf { it != shortcut } ?: brushBeforeShortcut
                shortcut
            }
        brushes.select(BrushTool.Paint, target).join()
        registry.execute(context, ToolCommandIds.TOOL_SELECT, JsonObject(mapOf("tool" to JsonPrimitive("brush"))))
        CommandResult.Success(JsonObject(mapOf("brushId" to JsonPrimitive(target))))
    }
    register(BrushCommandIds.BRUSH_RESET, R.string.command_brush_reset, listOf(brushIdParameter)) { _, arguments ->
        val brushId = arguments.brushId()
        if (!library.reset(brushId)) {
            return@register CommandResult.Failure(CommandErrorCode.Unavailable, "The brush has no reset point and is not built in")
        }
        brushes.reload(brushId).join()
        CommandResult.Success()
    }
    register(BrushCommandIds.BRUSH_DUPLICATE, R.string.command_brush_duplicate, listOf(brushIdParameter)) { _, arguments ->
        val brushId = arguments.brushId()
        if (library.entry(brushId) == null) return@register unknownBrush(brushId)
        CommandResult.Success(JsonObject(mapOf("brushId" to JsonPrimitive(library.duplicate(brushId)))))
    }
    register(BrushCommandIds.BRUSH_DELETE, R.string.command_brush_delete, listOf(brushIdParameter)) { _, arguments ->
        val brushId = arguments.brushId()
        if (library.entry(brushId) == null) return@register unknownBrush(brushId)
        library.delete(brushId)
        for (tool in ActiveBrushes.PAINTING_TOOLS) {
            if (brushes[tool]?.brushId == brushId) library.brushFor(tool)?.let { brushes.select(tool, it).join() }
        }
        CommandResult.Success()
    }
    register(
        BrushCommandIds.BRUSH_SET_PINNED,
        R.string.command_brush_set_pinned,
        listOf(brushIdParameter, ParameterSpec("pinned", ParameterType.Boolean)),
    ) { _, arguments ->
        library.setPinned(arguments.brushId(), arguments.getValue("pinned").jsonPrimitive.boolean)
        CommandResult.Success()
    }
    register(
        BrushCommandIds.BRUSH_MOVE,
        R.string.command_brush_move,
        listOf(brushIdParameter, groupIdParameter, ParameterSpec("position", ParameterType.Integer, minimum = 0.0)),
    ) { _, arguments ->
        val brushId = arguments.brushId()
        if (library.entry(brushId) == null) return@register unknownBrush(brushId)
        try {
            library.move(brushId, arguments.text("groupId"), arguments.number("position").toInt())
        } catch (exception: NoSuchElementException) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, exception.message ?: "No such group")
        }
        CommandResult.Success()
    }
    register(BrushCommandIds.LIBRARY_OPEN, R.string.command_brush_library_open, listOf(toolParameter)) { _, arguments ->
        ui.libraryTool = arguments.tool()
        session.showLayersPanel(false)
        if (session.panelsOneAtATime) session.showColorPanel(false)
        CommandResult.Success()
    }
    register(BrushCommandIds.LIBRARY_CLOSE, R.string.command_brush_library_close) { _, _ ->
        ui.libraryTool = null
        ui.previewBrushId = null
        CommandResult.Success()
    }
    register(
        BrushCommandIds.LIBRARY_SHOW_PREVIEW,
        R.string.command_brush_library_show_preview,
        listOf(ParameterSpec("brushId", ParameterType.String, defaultValue = JsonPrimitive(""))),
    ) { _, arguments ->
        val brushId = arguments.brushId().ifEmpty { null }
        if (brushId != null && library.entry(brushId) == null) return@register unknownBrush(brushId)
        ui.previewBrushId = brushId
        CommandResult.Success()
    }
    register(
        BrushCommandIds.LIBRARY_SHOW_GROUP,
        R.string.command_brush_library_show_group,
        listOf(ParameterSpec("groupId", ParameterType.String, defaultValue = JsonPrimitive(""))),
    ) { _, arguments ->
        ui.shownGroupId = arguments.getValue("groupId").jsonPrimitive.content.ifEmpty { null }
        CommandResult.Success()
    }
    register(BrushCommandIds.GROUP_CREATE, R.string.command_brush_group_create, listOf(ParameterSpec("name", ParameterType.String))) { _, arguments ->
        val name = arguments.getValue("name").jsonPrimitive.content.trim()
        if (name.isEmpty()) return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "A group needs a name")
        val groupId = library.createGroup(name)
        ui.shownGroupId = groupId
        CommandResult.Success(JsonObject(mapOf("groupId" to JsonPrimitive(groupId))))
    }
    register(
        BrushCommandIds.GROUP_RENAME,
        R.string.command_brush_group_rename,
        listOf(groupIdParameter, ParameterSpec("name", ParameterType.String)),
    ) { _, arguments ->
        val name = arguments.getValue("name").jsonPrimitive.content.trim()
        if (name.isEmpty()) return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "A group needs a name")
        library.renameGroup(arguments.getValue("groupId").jsonPrimitive.content, name)
        CommandResult.Success()
    }
    register(
        BrushCommandIds.GROUP_MOVE,
        R.string.command_brush_group_move,
        listOf(groupIdParameter, ParameterSpec("position", ParameterType.Integer, minimum = 0.0)),
    ) { _, arguments ->
        library.moveGroup(arguments.text("groupId"), arguments.number("position").toInt())
        CommandResult.Success()
    }
    register(BrushCommandIds.GROUP_DELETE, R.string.command_brush_group_delete, listOf(groupIdParameter)) { _, arguments ->
        val groupId = arguments.getValue("groupId").jsonPrimitive.content
        val inUse = ActiveBrushes.PAINTING_TOOLS.mapNotNull { brushes[it]?.brushId }.toSet()
        library.deleteGroup(groupId)
        if (ui.shownGroupId == groupId) ui.shownGroupId = null
        for (tool in ActiveBrushes.PAINTING_TOOLS) {
            val current = brushes[tool]?.brushId ?: continue
            if (current in inUse && library.entry(current) == null) library.brushFor(tool)?.let { brushes.select(tool, it).join() }
        }
        CommandResult.Success()
    }

    register(BrushCommandIds.SLIDER_PRESET_ADD, R.string.command_brush_slider_preset_add, listOf(sliderParameter)) { _, arguments ->
        val tool = session.currentBrushTool
        val brush = brushes[tool] ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No brush")
        val slider = arguments.getValue("slider").jsonPrimitive.content
        val value = if (slider == SliderNames.SIZE) brush.sliders.sizeSlider else brush.sliders.opacitySlider
        val presets = library.sliderPresets(brush.brushId, tool, slider)
        if (presets.size >= BrushLibrary.MAXIMUM_SLIDER_PRESETS) {
            return@register CommandResult.Failure(CommandErrorCode.Unavailable, "At most ${BrushLibrary.MAXIMUM_SLIDER_PRESETS} presets")
        }
        library.saveSliderPresets(brush.brushId, tool, slider, (presets + value).sorted())
        session.sliderPresetsChanged()
        CommandResult.Success()
    }
    register(
        BrushCommandIds.SLIDER_PRESET_REMOVE,
        R.string.command_brush_slider_preset_remove,
        listOf(sliderParameter, ParameterSpec("index", ParameterType.Integer, minimum = 0.0)),
    ) { _, arguments ->
        val tool = session.currentBrushTool
        val brush = brushes[tool] ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No brush")
        val slider = arguments.getValue("slider").jsonPrimitive.content
        val presets = library.sliderPresets(brush.brushId, tool, slider).toMutableList()
        val index = arguments.getValue("index").jsonPrimitive.int
        if (index !in presets.indices) return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "No preset $index")
        presets.removeAt(index)
        library.saveSliderPresets(brush.brushId, tool, slider, presets)
        session.sliderPresetsChanged()
        CommandResult.Success()
    }

    register(BrushCommandIds.EDITOR_OPEN, R.string.command_brush_editor_open, listOf(brushIdParameter, toolParameter)) { _, arguments ->
        val brushId = arguments.brushId()
        if (library.entry(brushId) == null) return@register unknownBrush(brushId)
        ui.openEditor(brushId, arguments.tool(), session.primaryColor, session.secondaryColor)
        ui.libraryTool = null
        ui.previewBrushId = null
        navigator.open(AppDestination.BrushEditor)
        CommandResult.Success()
    }
    register(
        BrushCommandIds.EDITOR_SHOW_SECTION,
        R.string.command_brush_editor_show_section,
        listOf(ParameterSpec("section", ParameterType.Enumeration, allowedValues = BrushEditorSection.entries.map { it.name })),
        availability = editorOpen,
    ) { _, arguments ->
        val editor = ui.editor ?: return@register editorClosed()
        editor.section = BrushEditorSection.valueOf(arguments.getValue("section").jsonPrimitive.content)
        CommandResult.Success()
    }
    register(
        BrushCommandIds.EDITOR_SET_VALUE,
        R.string.command_brush_editor_set_value,
        listOf(ParameterSpec("path", ParameterType.String), ParameterSpec("value", ParameterType.Json)),
        availability = editorOpen,
    ) { _, arguments ->
        val editor = ui.editor ?: return@register editorClosed()
        val path = arguments.getValue("path").jsonPrimitive.content
        if (!isEditablePath(path)) return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Field $path cannot be edited")
        editor.setValue(path, arguments.getValue("value"))
        CommandResult.Success()
    }
    register(
        BrushCommandIds.EDITOR_REMOVE_VALUE,
        R.string.command_brush_editor_remove_value,
        listOf(ParameterSpec("path", ParameterType.String)),
        availability = editorOpen,
    ) { _, arguments ->
        val editor = ui.editor ?: return@register editorClosed()
        val path = arguments.getValue("path").jsonPrimitive.content
        if (!isEditablePath(path)) return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Field $path cannot be edited")
        editor.removeValue(path)
        CommandResult.Success()
    }
    register(
        BrushCommandIds.EDITOR_IMPORT_TEXTURE,
        R.string.command_brush_editor_import_texture,
        listOf(textureSlotParameter, ParameterSpec("uri", ParameterType.String)),
        availability = editorOpen,
    ) { _, arguments ->
        val editor = ui.editor ?: return@register editorClosed()
        val uri = arguments.getValue("uri").jsonPrimitive.content.toUri()
        if (uri.scheme != "content") return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
        val slot = arguments.textureSlot()
        val keepColors = slot == TextureSlot.Shape && editor.working.document.boolean("stamp.keepColors") == true
        val png =
            withContext(Dispatchers.IO) { TextureImport.read(contentResolver, uri, slot, keepColors) }
                ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "The image cannot be read")
        editor.setTexture(slot, png)
        CommandResult.Success()
    }
    register(
        BrushCommandIds.EDITOR_REMOVE_TEXTURE,
        R.string.command_brush_editor_remove_texture,
        listOf(textureSlotParameter),
        availability = editorOpen,
    ) { _, arguments ->
        val editor = ui.editor ?: return@register editorClosed()
        editor.setTexture(arguments.textureSlot(), null)
        CommandResult.Success()
    }
    var autoRepeatJob: Job? = null
    register(
        BrushCommandIds.EDITOR_AUTO_REPEAT,
        R.string.command_brush_editor_auto_repeat,
        listOf(
            ParameterSpec(
                "slot",
                ParameterType.Enumeration,
                JsonPrimitive("grain"),
                allowedValues = TextureSlot.entries.map { it.name.lowercase() },
            ),
            ParameterSpec("outputSize", ParameterType.Integer, JsonPrimitive(0)),
            ParameterSpec("patchScale", ParameterType.Number, JsonPrimitive(0.25)),
            ParameterSpec("seamOverlap", ParameterType.Number, JsonPrimitive(0.25)),
            ParameterSpec("mirroring", ParameterType.Boolean, JsonPrimitive(false)),
            ParameterSpec("ignoreLowBands", ParameterType.Boolean, JsonPrimitive(false)),
            ParameterSpec("maskHardness", ParameterType.Number, JsonPrimitive(0.5)),
            ParameterSpec("pyramidBlending", ParameterType.Boolean, JsonPrimitive(false)),
        ),
        availability = editorOpen,
    ) { _, arguments ->
        val editor = ui.editor ?: return@register editorClosed()

        fun number(name: String) = arguments.number(name).toFloat()

        fun flag(name: String) = arguments.getValue(name).jsonPrimitive.boolean
        val settings =
            BrushPad.Companion.AutoRepeatSettings(
                outputSize = arguments.number("outputSize").toInt().coerceIn(0, TextureImport.MAXIMUM_GRAIN_SIDE),
                patchScale = number("patchScale").coerceIn(MINIMUM_PATCH_SCALE, 1f),
                seamOverlap = number("seamOverlap").coerceIn(0f, MAXIMUM_SEAM_OVERLAP),
                mirroring = flag("mirroring"),
                ignoreLowBands = flag("ignoreLowBands"),
                maskHardness = number("maskHardness").coerceIn(0f, 1f),
                pyramidBlending = flag("pyramidBlending"),
            )
        autoRepeatJob?.cancel()
        val job = ui.scope.async { editor.autoRepeat(arguments.textureSlot(), settings) }
        autoRepeatJob = job
        val done =
            try {
                job.await()
            } catch (exception: CancellationException) {
                return@register CommandResult.Failure(CommandErrorCode.HandlerFailed, "Canceled")
            }
        if (!done) {
            return@register CommandResult.Failure(CommandErrorCode.Unavailable, "The slot has no texture to repeat")
        }
        CommandResult.Success()
    }
    register(
        BrushCommandIds.EDITOR_CANCEL_AUTO_REPEAT,
        R.string.command_brush_editor_cancel_auto_repeat,
        availability = editorOpen,
    ) { _, _ ->
        autoRepeatJob?.cancel()
        CommandResult.Success()
    }
    register(
        BrushCommandIds.EDITOR_UNDO_TEXTURE,
        R.string.command_brush_editor_undo_texture,
        listOf(textureSlotParameter),
        availability = editorOpen,
    ) { _, arguments ->
        val editor = ui.editor ?: return@register editorClosed()
        if (!editor.undoTexture(arguments.textureSlot())) {
            return@register CommandResult.Failure(CommandErrorCode.Unavailable, "Nothing to undo")
        }
        CommandResult.Success()
    }
    register(
        BrushCommandIds.EDITOR_PASTE_TEXTURE,
        R.string.command_brush_editor_paste_texture,
        listOf(textureSlotParameter),
        availability = editorOpen,
    ) { _, arguments ->
        val editor = ui.editor ?: return@register editorClosed()
        val uri = clipboardImage() ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "The clipboard holds no image")
        val slot = arguments.textureSlot()
        val keepColors = slot == TextureSlot.Shape && editor.working.document.boolean("stamp.keepColors") == true
        val png =
            withContext(Dispatchers.IO) { TextureImport.read(contentResolver, uri, slot, keepColors) }
                ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "The clipboard image cannot be read")
        editor.setTexture(slot, png)
        CommandResult.Success()
    }
    register(
        BrushCommandIds.EDITOR_USE_LIBRARY_TEXTURE,
        R.string.command_brush_editor_use_library_texture,
        listOf(textureSlotParameter, brushIdParameter),
        availability = editorOpen,
    ) { _, arguments ->
        val editor = ui.editor ?: return@register editorClosed()
        val brushId = arguments.brushId()
        if (library.entry(brushId) == null) return@register unknownBrush(brushId)
        val slot = arguments.textureSlot()
        val source = library.loadPackage(brushId)
        val png =
            source.document.string(slot.field)?.let { source.files[it] }
                ?: return@register CommandResult.Failure(
                    CommandErrorCode.InvalidArguments,
                    "That brush has no ${slot.name.lowercase()} texture",
                )
        editor.setTexture(slot, png)
        CommandResult.Success()
    }

    register(BrushCommandIds.EDITOR_CLEAR_PAD, R.string.command_brush_editor_clear_pad, availability = editorOpen) { _, _ ->
        val editor = ui.editor ?: return@register editorClosed()
        editor.clearPad()
        CommandResult.Success()
    }
    register(
        BrushCommandIds.EDITOR_SET_PAD_DARK,
        R.string.command_brush_editor_set_pad_dark,
        listOf(ParameterSpec("dark", ParameterType.Boolean)),
        availability = editorOpen,
    ) { _, arguments ->
        val editor = ui.editor ?: return@register editorClosed()
        editor.padDark = arguments.getValue("dark").jsonPrimitive.boolean
        editor.clearPad()
        CommandResult.Success()
    }
    register(
        BrushCommandIds.EDITOR_IMPORT_AUTHOR_PICTURE,
        R.string.command_brush_editor_import_author_picture,
        listOf(ParameterSpec("uri", ParameterType.String)),
        availability = editorOpen,
    ) { _, arguments ->
        val editor = ui.editor ?: return@register editorClosed()
        val uri = arguments.text("uri").toUri()
        if (uri.scheme != "content") {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
        }
        val png =
            withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let(BrushAbout::authorPicture) }
                ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "The image cannot be read")
        editor.setFile(BrushAbout.AUTHOR_FILE, png)
        CommandResult.Success()
    }
    register(
        BrushCommandIds.EDITOR_REMOVE_AUTHOR_PICTURE,
        R.string.command_brush_editor_remove_author_picture,
        availability = editorOpen,
    ) { _, _ ->
        val editor = ui.editor ?: return@register editorClosed()
        editor.setFile(BrushAbout.AUTHOR_FILE, null)
        CommandResult.Success()
    }
    register(
        BrushCommandIds.EDITOR_SET_SIGNATURE,
        R.string.command_brush_editor_set_signature,
        listOf(ParameterSpec("strokes", ParameterType.Array)),
        availability = editorOpen,
    ) { _, arguments ->
        val editor = ui.editor ?: return@register editorClosed()
        val strokes =
            try {
                arguments.getValue("strokes").jsonArray.map { stroke ->
                    stroke.jsonArray.map { point ->
                        val (x, y) = point.jsonArray.map { it.jsonPrimitive.double.toFloat() }
                        x to y
                    }
                }
            } catch (exception: IllegalArgumentException) {
                return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "strokes must be arrays of [x, y] points")
            } catch (exception: IndexOutOfBoundsException) {
                return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "strokes must be arrays of [x, y] points")
            }
        val png = withContext(Dispatchers.Default) { BrushAbout.signature(strokes) }
        editor.setFile(BrushAbout.SIGNATURE_FILE, png)
        CommandResult.Success()
    }
    register(
        BrushCommandIds.EDITOR_CREATE_RESET_POINT,
        R.string.command_brush_editor_create_reset_point,
        listOf(ParameterSpec("name", ParameterType.String)),
        availability = editorOpen,
    ) { _, arguments ->
        val editor = ui.editor ?: return@register editorClosed()
        val name = arguments.text("name").trim()
        if (name.isEmpty()) return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "A reset point needs a name")
        editor.createResetPoint(name, Instant.now().toString())
        CommandResult.Success()
    }
    register(
        BrushCommandIds.EDITOR_RESTORE_RESET_POINT,
        R.string.command_brush_editor_restore_reset_point,
        listOf(ParameterSpec("index", ParameterType.Integer)),
        availability = editorOpen,
    ) { _, arguments ->
        val editor = ui.editor ?: return@register editorClosed()
        if (!editor.restoreResetPoint(arguments.getValue("index").jsonPrimitive.int)) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "No such reset point")
        }
        CommandResult.Success()
    }
    register(BrushCommandIds.EDITOR_RESET_ALL, R.string.command_brush_editor_reset_all, availability = editorOpen) { _, _ ->
        val editor = ui.editor ?: return@register editorClosed()
        if (!editor.resetAll()) {
            return@register CommandResult.Failure(CommandErrorCode.Unavailable, "The brush has no reset point and is not built in")
        }
        CommandResult.Success()
    }

    register(
        BrushCommandIds.EDITOR_SET_PAD_COLOR,
        R.string.command_brush_editor_set_pad_color,
        listOf(ParameterSpec("useColor", ParameterType.Boolean)),
        availability = editorOpen,
    ) { _, arguments ->
        val editor = ui.editor ?: return@register editorClosed()
        editor.padUsesColor = arguments.getValue("useColor").jsonPrimitive.boolean
        CommandResult.Success()
    }
    register(
        BrushCommandIds.EDITOR_SET_PAD_SIZE,
        R.string.command_brush_editor_set_pad_size,
        listOf(ParameterSpec("sizePx", ParameterType.Number)),
        availability = editorOpen,
    ) { _, arguments ->
        val editor = ui.editor ?: return@register editorClosed()
        editor.updatePadSize(arguments.number("sizePx").toFloat())
        CommandResult.Success()
    }
    register(BrushCommandIds.EDITOR_DONE, R.string.command_brush_editor_done, availability = editorOpen) { _, _ ->
        val editor = ui.editor ?: return@register editorClosed()
        editor.brushError?.let { return@register CommandResult.Failure(CommandErrorCode.Unavailable, it) }
        editor.save()
        ui.closeEditor()
        navigator.back()
        CommandResult.Success()
    }
    register(BrushCommandIds.EDITOR_CANCEL, R.string.command_brush_editor_cancel, availability = editorOpen) { _, _ ->
        ui.closeEditor()
        navigator.back()
        CommandResult.Success()
    }

    register(
        BrushCommandIds.SETTINGS_PRESSURE_CURVE,
        R.string.command_settings_pressure_curve,
        listOf(ParameterSpec("points", ParameterType.Array)),
        category = CommandCategory.Settings,
    ) { _, arguments ->
        val points =
            arguments.getValue("points").jsonArray.map { point ->
                val pair = (point as? JsonArray)?.takeIf { it.size == 2 }
                    ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "points must be [[x, y], ...]")
                pair[0].jsonPrimitive.double.toFloat() to pair[1].jsonPrimitive.double.toFloat()
            }
        ui.inputSettings.updatePressureCurve(points)
        CommandResult.Success()
    }
    register(
        BrushCommandIds.SETTINGS_SMOOTHING,
        R.string.command_settings_smoothing,
        listOf(ParameterSpec("amount", ParameterType.Number, minimum = 0.0, maximum = 1.0)),
        category = CommandCategory.Settings,
    ) { _, arguments ->
        ui.inputSettings.updateGlobalSmoothing(arguments.getValue("amount").jsonPrimitive.double.toFloat())
        CommandResult.Success()
    }
}

internal fun isEditablePath(path: String): Boolean = path.split('.').first() in EDITABLE_SECTIONS

private val EDITABLE_SECTIONS =
    setOf(
        "name",
        "author",
        "license",
        "path",
        "shape",
        "grain",
        "dynamics",
        "taper",
        "render",
        "colorDynamics",
        "wetMix",
        "smudge",
        "particles",
        "dual",
        "stamp",
        "hover",
        "roll",
        "limits",
        "defaults",
        "properties",
        "curves",
    )

private fun JsonObject.brushId() = getValue("brushId").jsonPrimitive.content

private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

private fun JsonObject.number(name: String) = getValue(name).jsonPrimitive.double

private fun JsonObject.tool() = BrushTool.entries.first { it.key == getValue("tool").jsonPrimitive.content }

private fun JsonObject.textureSlot() = TextureSlot.valueOf(getValue("slot").jsonPrimitive.content.replaceFirstChar { it.uppercase() })

private fun unknownBrush(brushId: String) = CommandResult.Failure(CommandErrorCode.InvalidArguments, "No brush $brushId")

private fun editorClosed() = CommandResult.Failure(CommandErrorCode.Unavailable, "The brush editor is not open")

suspend fun importBrushFile(
    ui: BrushUiState,
    contentResolver: ContentResolver,
    uri: Uri,
    onReport: (List<ImportWarning>) -> Unit = {},
): CommandResult {
    val library = ui.library
    return try {
        val bytes = withContext(Dispatchers.IO) { BrushFiles.read(contentResolver, uri) }
        when (BrushFiles.kindOf(bytes)) {
            BrushFileKind.Brush -> {
                val brushId = library.importBrush(bytes)
                ui.shownGroupId = library.entry(brushId)?.groupId
                CommandResult.Success(JsonObject(mapOf("brushId" to JsonPrimitive(brushId))))
            }
            BrushFileKind.BrushSet -> {
                val groupId = library.importBrushSet(bytes)
                ui.shownGroupId = groupId
                CommandResult.Success(JsonObject(mapOf("groupId" to JsonPrimitive(groupId))))
            }
            BrushFileKind.ProcreateBrush, BrushFileKind.ProcreateBrushSet -> {
                val converted = withContext(Dispatchers.IO) { ProcreateBrushImport.convert(bytes) }
                val groupId = library.importConverted(converted.name, converted.brushes.map { it.brushPackage })
                ui.shownGroupId = groupId
                val warnings = converted.brushes.flatMap { it.warnings }
                if (warnings.isNotEmpty()) onReport(warnings)
                val result = mapOf("groupId" to JsonPrimitive(groupId), "brushes" to JsonPrimitive(converted.brushes.size))
                CommandResult.Success(JsonObject(result))
            }
            null -> CommandResult.Failure(CommandErrorCode.InvalidArguments, "Not a brush file")
        }
    } catch (error: IOException) {
        CommandResult.Failure(CommandErrorCode.HandlerFailed, "Unreadable: ${error.message}")
    } catch (error: InvalidBrushException) {
        CommandResult.Failure(CommandErrorCode.InvalidArguments, "Invalid brush: ${error.message}")
    } catch (error: ImportJsonException) {
        CommandResult.Failure(CommandErrorCode.InvalidArguments, "Invalid brush: ${error.message}")
    }
}
