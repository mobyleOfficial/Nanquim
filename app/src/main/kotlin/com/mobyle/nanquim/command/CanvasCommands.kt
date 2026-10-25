package com.mobyle.nanquim.command

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import com.mobyle.nanquim.R
import com.mobyle.nanquim.brush.library.BrushTool
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.document.command.AppState
import com.mobyle.nanquim.document.command.Availability
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandHandler
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.engine.Point
import kotlin.math.PI

object CanvasCommandIds {
    const val VIEW_FIT = "view.fit"
    const val VIEW_ACTUAL_PIXELS = "view.actualPixels"
    const val VIEW_ZOOM_TO = "view.zoomTo"
    const val VIEW_ROTATE_TO = "view.rotateTo"
    const val VIEW_FLIP = "view.flipView"
    const val VIEW_FULL_SCREEN = "view.fullScreen"
    const val VIEW_EXPORT_SHEET = "view.exportSheet"
    const val VIEW_COPY_PASTE_MENU = "view.copyPasteMenu"

    const val DEBUG_CREATE_TEST_DOCUMENT = "debug.document.createTest"
    const val DEBUG_FILL_RANDOM_TILES = "debug.layer.fillRandomTiles"
    const val DEBUG_DIRTY_TILE_OVERLAY = "debug.view.dirtyTileOverlay"
    const val DEBUG_MEMORY_HUD = "debug.view.memoryHud"
    const val DEBUG_SET_MEMORY_BUDGET = "debug.memory.setBudget"
    const val DEBUG_PANEL = "debug.panel.toggle"
    const val DEBUG_CAMERA_TOUR = "debug.view.cameraTour"
    const val DEBUG_RESET_FRAME_STATISTICS = "debug.stats.resetFrames"
    const val DEBUG_READ_FRAME_STATISTICS = "debug.stats.readFrames"
    const val DEBUG_BRUSH_SMOOTHING = "debug.brush.setSmoothing"
    const val DEBUG_FRONT_BUFFER = "debug.render.frontBuffer"
    const val DEBUG_KEEP_GPU_AWAKE = "debug.gpu.keepAwake"
}

private val requiresDocument: (AppState) -> Availability = { state ->
    if (state.hasOpenDocument) Availability.Enabled else Availability.Disabled("No open document")
}

private fun JsonObject.int(name: String) = getValue(name).jsonPrimitive.int

private fun JsonObject.long(name: String) = getValue(name).jsonPrimitive.long

private fun JsonObject.double(name: String) = getValue(name).jsonPrimitive.double

private fun JsonObject.boolean(name: String) = getValue(name).jsonPrimitive.boolean

fun registerCanvasCommands(
    registry: CommandRegistry,
    session: CanvasSession,
    developerTools: Boolean,
) {
    fun register(
        id: String,
        titleRes: Int,
        category: CommandCategory,
        parameters: List<ParameterSpec> = emptyList(),
        undoable: Boolean = false,
        handler: CommandHandler,
    ) = registry.register(
        CommandSpec(
            id = id,
            titleRes = titleRes,
            category = category,
            parameters = parameters,
            undoable = undoable,
            availability = requiresDocument,
        ),
        handler,
    )

    register(CanvasCommandIds.VIEW_FIT, R.string.command_view_fit, CommandCategory.View) { _, _ ->
        session.viewport.fit()
        CommandResult.Success()
    }
    register(CanvasCommandIds.VIEW_ACTUAL_PIXELS, R.string.command_view_actual_pixels, CommandCategory.View) { _, _ ->
        session.viewport.showActualPixels()
        CommandResult.Success()
    }
    register(
        CanvasCommandIds.VIEW_ZOOM_TO,
        R.string.command_view_zoom_to,
        CommandCategory.View,
        listOf(ParameterSpec("zoom", ParameterType.Number, minimum = 0.05, maximum = 64.0)),
    ) { _, arguments ->
        session.viewport.zoomTo(arguments.double("zoom"))
        CommandResult.Success()
    }
    register(
        CanvasCommandIds.VIEW_ROTATE_TO,
        R.string.command_view_rotate_to,
        CommandCategory.View,
        listOf(ParameterSpec("degrees", ParameterType.Number, minimum = -360.0, maximum = 360.0)),
    ) { _, arguments ->
        session.viewport.rotateTo(arguments.double("degrees") * PI / 180)
        CommandResult.Success()
    }
    register(CanvasCommandIds.VIEW_FLIP, R.string.command_view_flip, CommandCategory.View) { _, _ ->
        session.viewport.toggleFlip()
        CommandResult.Success()
    }
    register(
        CanvasCommandIds.VIEW_FULL_SCREEN,
        R.string.command_view_full_screen,
        CommandCategory.View,
        listOf(ParameterSpec("enabled", ParameterType.Boolean, defaultValue = JsonNull)),
    ) { _, arguments ->
        val enabled = (arguments["enabled"] as? JsonPrimitive)?.booleanOrNull ?: !session.fullScreen
        session.updateFullScreen(enabled)
        CommandResult.Success(JsonObject(mapOf("enabled" to JsonPrimitive(enabled))))
    }
    register(CanvasCommandIds.VIEW_EXPORT_SHEET, R.string.command_view_export_sheet, CommandCategory.View) { _, _ ->
        session.requestExportSheet()
        CommandResult.Success()
    }
    register(
        CanvasCommandIds.VIEW_COPY_PASTE_MENU,
        R.string.command_view_copy_paste_menu,
        CommandCategory.View,
        listOf(
            ParameterSpec("x", ParameterType.Number, defaultValue = JsonNull),
            ParameterSpec("y", ParameterType.Number, defaultValue = JsonNull),
        ),
    ) { _, arguments ->
        val x = (arguments["x"] as? JsonPrimitive)?.doubleOrNull
        val y = (arguments["y"] as? JsonPrimitive)?.doubleOrNull
        session.showCopyPasteMenu(if (x != null && y != null) Point(x, y) else null)
        CommandResult.Success()
    }
    if (!developerTools) return

    registry.register(
        CommandSpec(
            id = CanvasCommandIds.DEBUG_CREATE_TEST_DOCUMENT,
            titleRes = R.string.command_debug_create_test_document,
            category = CommandCategory.Canvas,
            parameters =
                listOf(
                    ParameterSpec("width", ParameterType.Integer, minimum = 1.0, maximum = 16384.0),
                    ParameterSpec("height", ParameterType.Integer, minimum = 1.0, maximum = 16384.0),
                    ParameterSpec("layers", ParameterType.Integer, defaultValue = JsonPrimitive(1), minimum = 1.0, maximum = 999.0),
                    ParameterSpec("fillTiles", ParameterType.Integer, defaultValue = JsonPrimitive(0), minimum = -1.0),
                ),
            undoable = false,
        ),
    ) { _, arguments ->
        session.openTestDocument(arguments.int("width"), arguments.int("height"), arguments.int("layers"), arguments.int("fillTiles"))
        CommandResult.Success()
    }
    register(
        CanvasCommandIds.DEBUG_FILL_RANDOM_TILES,
        R.string.command_debug_fill_random_tiles,
        CommandCategory.Layer,
        listOf(
            ParameterSpec("layerId", ParameterType.Integer, defaultValue = JsonPrimitive(0), minimum = 0.0),
            ParameterSpec("count", ParameterType.Integer, defaultValue = JsonPrimitive(DEFAULT_RANDOM_TILE_COUNT), minimum = -1.0),
            ParameterSpec("seed", ParameterType.Integer, defaultValue = JsonPrimitive(CanvasSession.DEFAULT_FILL_SEED)),
        ),
    ) { _, arguments ->
        session.fillRandomTiles(arguments.int("layerId"), arguments.int("count"), arguments.int("seed"))
        CommandResult.Success()
    }
    register(
        CanvasCommandIds.DEBUG_DIRTY_TILE_OVERLAY,
        R.string.command_debug_dirty_tile_overlay,
        CommandCategory.View,
        listOf(ParameterSpec("enabled", ParameterType.Boolean)),
    ) { _, arguments ->
        session.showDirtyTileOverlay(arguments.boolean("enabled"))
        CommandResult.Success()
    }
    registry.register(
        CommandSpec(
            id = CanvasCommandIds.DEBUG_MEMORY_HUD,
            titleRes = R.string.command_debug_memory_hud,
            category = CommandCategory.View,
            parameters = listOf(ParameterSpec("enabled", ParameterType.Boolean)),
            undoable = false,
        ),
    ) { _, arguments ->
        session.showMemoryHud(arguments.boolean("enabled"))
        CommandResult.Success()
    }
    registry.register(
        CommandSpec(
            id = CanvasCommandIds.DEBUG_SET_MEMORY_BUDGET,
            titleRes = R.string.command_debug_set_memory_budget,
            category = CommandCategory.Settings,
            parameters = listOf(ParameterSpec("megabytes", ParameterType.Integer, minimum = 16.0, maximum = 65536.0)),
            undoable = false,
        ),
    ) { _, arguments ->
        session.setMemoryBudget(arguments.long("megabytes") * BYTES_PER_MEGABYTE)
        CommandResult.Success()
    }
    register(CanvasCommandIds.DEBUG_CAMERA_TOUR, R.string.command_debug_camera_tour, CommandCategory.View) { _, _ ->
        session.viewport.startCameraTour()
        CommandResult.Success()
    }
    registry.register(
        CommandSpec(
            id = CanvasCommandIds.DEBUG_BRUSH_SMOOTHING,
            titleRes = R.string.command_debug_brush_smoothing,
            category = CommandCategory.Brush,
            parameters =
                listOf("streamline", "stabilization", "jitterFilter").map {
                    ParameterSpec(it, ParameterType.Number, defaultValue = JsonPrimitive(0), minimum = 0.0, maximum = 1.0)
                },
            undoable = false,
        ),
    ) { _, arguments ->
        val brush = session.brushes[BrushTool.Paint] ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No brush")
        val brushPackage = session.brushLibrary.loadPackage(brush.brushId)
        val document =
            brushPackage.document
                .with("path.streamline", arguments.double("streamline"))
                .with("path.stabilization", arguments.double("stabilization"))
                .with("path.jitterFilter", arguments.double("jitterFilter"))
        val error = session.brushes.preview(BrushTool.Paint, brushPackage.withDocument(document))
        if (error == null) CommandResult.Success() else CommandResult.Failure(CommandErrorCode.Unavailable, error)
    }
    register(
        CanvasCommandIds.DEBUG_FRONT_BUFFER,
        R.string.command_debug_front_buffer,
        CommandCategory.Settings,
        listOf(ParameterSpec("enabled", ParameterType.Boolean)),
    ) { _, arguments ->
        session.enableFrontBuffer(arguments.boolean("enabled"))
        CommandResult.Success()
    }
    register(
        CanvasCommandIds.DEBUG_KEEP_GPU_AWAKE,
        R.string.command_debug_keep_gpu_awake,
        CommandCategory.View,
        listOf(ParameterSpec("milliseconds", ParameterType.Integer, minimum = 0.0, maximum = 10_000.0)),
    ) { _, arguments ->
        session.keepGpuAwake(arguments.int("milliseconds"))
        CommandResult.Success()
    }
    register(
        CanvasCommandIds.DEBUG_RESET_FRAME_STATISTICS,
        R.string.command_debug_reset_frame_statistics,
        CommandCategory.View,
    ) { _, _ ->
        session.resetFrameStatistics()
        CommandResult.Success()
    }
    register(
        CanvasCommandIds.DEBUG_READ_FRAME_STATISTICS,
        R.string.command_debug_read_frame_statistics,
        CommandCategory.View,
    ) { _, _ ->
        val frames = session.frameStatistics()
        val latency = session.latencyStatistics()
        val text = session.textStatistics()
        CommandResult.Success(
            JsonObject(
                mapOf(
                    "sampleCount" to JsonPrimitive(frames.sampleCount),
                    "intervalMedianMilliseconds" to JsonPrimitive(frames.intervalMedianMilliseconds),
                    "intervalP99Milliseconds" to JsonPrimitive(frames.intervalP99Milliseconds),
                    "intervalMaxMilliseconds" to JsonPrimitive(frames.intervalMaxMilliseconds),
                    "droppedFrames" to JsonPrimitive(frames.droppedFrames),
                    "incompleteFrames" to JsonPrimitive(frames.incompleteFrames),
                    "cpuMedianMilliseconds" to JsonPrimitive(frames.cpuMedianMilliseconds),
                    "cpuP99Milliseconds" to JsonPrimitive(frames.cpuP99Milliseconds),
                    "latency" to
                        JsonObject(
                            latency?.let {
                                mapOf(
                                    "inputToDisplayMedianMilliseconds" to JsonPrimitive(it.inputToDisplayMedianMilliseconds),
                                    "inputToFrontBufferMedianMilliseconds" to JsonPrimitive(it.inputToFrontBufferMedianMilliseconds),
                                    "inputToFrontBufferP95Milliseconds" to JsonPrimitive(it.inputToFrontBufferP95Milliseconds),
                                    "frontBufferSampleCount" to JsonPrimitive(it.frontBufferSampleCount),
                                    "inputToFirstFrontBufferMedianMilliseconds" to
                                        JsonPrimitive(it.inputToFirstFrontBufferMedianMilliseconds),
                                    "inputToFirstFrontBufferMaxMilliseconds" to JsonPrimitive(it.inputToFirstFrontBufferMaxMilliseconds),
                                    "firstFrontBufferSampleCount" to JsonPrimitive(it.firstFrontBufferSampleCount),
                                )
                            } ?: emptyMap(),
                        ),
                    "text" to
                        JsonObject(
                            mapOf(
                                "rasterCount" to JsonPrimitive(text?.rasterCount ?: 0),
                                "rasterMedianMilliseconds" to JsonPrimitive(text?.rasterMedianMilliseconds ?: 0.0),
                                "rasterMaxMilliseconds" to JsonPrimitive(text?.rasterMaxMilliseconds ?: 0.0),
                            ),
                        ),
                ),
            ),
        )
    }
    registry.register(
        CommandSpec(
            id = CanvasCommandIds.DEBUG_PANEL,
            titleRes = R.string.command_debug_panel,
            category = CommandCategory.Settings,
            undoable = false,
        ),
    ) { _, _ ->
        session.debugPanelVisible = !session.debugPanelVisible
        CommandResult.Success()
    }
}

private const val DEFAULT_RANDOM_TILE_COUNT = 16
private const val BYTES_PER_MEGABYTE = 1024L * 1024L
