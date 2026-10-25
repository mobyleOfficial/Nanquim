package com.mobyle.nanquim.automation

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import com.mobyle.nanquim.command.stateJson
import com.mobyle.nanquim.help.Help
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import com.mobyle.nanquim.brush.library.BrushTool
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.command.toJson
import com.mobyle.nanquim.settings.AppSettings
import com.mobyle.nanquim.document.layer.toJson
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.engine.EngineEvent
import com.mobyle.nanquim.engine.SceneRender

class CanvasAutomationHost(
    private val canvas: CanvasSession,
    private val engine: Engine,
    scope: CoroutineScope,
    private val appSettings: AppSettings,
    private val help: Help? = null,
) : AutomationHost {
    private val mutableEvents = MutableSharedFlow<JsonObject>(extraBufferCapacity = EVENT_BUFFER)
    override val events: SharedFlow<JsonObject> = mutableEvents.asSharedFlow()

    init {
        scope.launch {
            snapshotFlow { canvas.documentVersion }.drop(1).collect {
                mutableEvents.tryEmit(buildJsonObject { put("type", "documentChanged") })
            }
        }
        scope.launch {
            engine.events.collect { event ->
                val json =
                    when (event) {
                        is EngineEvent.Error ->
                            buildJsonObject {
                                put("type", "engineError")
                                put("code", event.code.name)
                                put("message", event.message)
                                put("recoverable", event.recoverable)
                            }
                        EngineEvent.DocumentShown -> buildJsonObject { put("type", "documentShown") }
                        is EngineEvent.TransformReady ->
                            buildJsonObject {
                                put("type", "transformReady")
                                put("x", event.x)
                                put("y", event.y)
                                put("width", event.width)
                                put("height", event.height)
                                put("principalAngle", event.principalAngle)
                            }
                        is EngineEvent.HistoryEntryReady ->
                            buildJsonObject {
                                put("type", "historyEntryReady")
                                put("entry", event.entry)
                                put("bytes", event.bytes)
                            }
                        is EngineEvent.DocumentSaved ->
                            buildJsonObject {
                                put("type", "documentSaved")
                                put("ok", event.result.ok)
                                put("error", event.result.error ?: "")
                                put("tilesWritten", event.result.tilesWritten)
                                put("tilesRemoved", event.result.tilesRemoved)
                                put("milliseconds", event.result.milliseconds)
                            }
                        is EngineEvent.DocumentTilesCorrupt ->
                            buildJsonObject {
                                put("type", "documentTilesCorrupt")
                                put("count", event.count)
                            }
                    }
                mutableEvents.tryEmit(json)
            }
        }
    }

    override fun state(): JsonObject {
        val session = canvas.documentSession
        return buildJsonObject {
            put("document", session?.state?.toJson() ?: JsonNull)
            put(
                "artwork",
                canvas.artwork?.let { artwork ->
                    buildJsonObject {
                        put("id", artwork.id)
                        put("name", artwork.info.name)
                        put("saveStatus", canvas.persistence.status.name)
                    }
                } ?: JsonNull,
            )
            if (session != null) {
                putJsonArray("selectedLayerIds") { session.selectedIds.sorted().forEach { add(JsonPrimitive(it)) } }
                put("maskSelected", session.maskSelected)
                put("soloLayerId", session.soloLayerId?.let(::JsonPrimitive) ?: JsonNull)
                putJsonObject("history") {
                    put("undo", session.history.undoCount)
                    put("redo", session.history.redoCount)
                    put("nextUndo", session.history.nextUndo?.label?.name?.let(::JsonPrimitive) ?: JsonNull)
                }
            }
            put("tool", canvas.canvasTool.name)
            put("brushes", buildJsonObject { BrushTool.entries.forEach { put(it.key, canvas.brushes[it]?.brushId) } })
            put("quickMenuOpen", canvas.quickMenu != null)
            put("projectedDisplay", canvas.projectedDisplay)
            put("spacePanning", canvas.spacePanning)
            putJsonArray("primaryColor") {
                add(JsonPrimitive(canvas.primaryColor.red))
                add(JsonPrimitive(canvas.primaryColor.green))
                add(JsonPrimitive(canvas.primaryColor.blue))
            }
            putJsonArray("secondaryColor") {
                add(JsonPrimitive(canvas.secondaryColor.red))
                add(JsonPrimitive(canvas.secondaryColor.green))
                add(JsonPrimitive(canvas.secondaryColor.blue))
            }
            put("layersPanelVisible", canvas.layersPanelVisible)
            help?.let { put("help", it.stateJson()) }
            put("colorPanelVisible", canvas.colorPanelVisible)
            put("colorPanelMode", canvas.colorPanelMode.id)
            put("colorHarmony", canvas.colorHarmony.id)
            put("guides", canvas.guides.toJson())
            putJsonArray("missingFonts") { canvas.missingFonts.forEach { add(JsonPrimitive(it)) } }
            put("rasterizePrompt", canvas.rasterizePrompt?.layerId?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "timelapse",
                buildJsonObject {
                    val info = canvas.artwork?.info?.timelapse
                    put("enabled", info?.enabled == true)
                    put("resolution", info?.resolution?.id)
                    put("framesRecorded", canvas.timelapse.framesRecorded)
                    put("framesSkipped", canvas.timelapse.framesSkipped)
                    put("segments", canvas.artwork?.folder?.let { canvas.timelapse.segmentsOf(it).size } ?: 0)
                },
            )
            put(
                "playback",
                buildJsonObject {
                    put("playing", canvas.playback.playing)
                    put("framesShown", canvas.playback.framesShown)
                    put("frameId", canvas.documentSession?.playbackFrameId?.let(::JsonPrimitive) ?: JsonNull)
                },
            )
            put(
                "textEdit",
                canvas.textEditor.editing?.let { editing ->
                    buildJsonObject {
                        put("layerId", editing.layerId?.let(::JsonPrimitive) ?: JsonNull)
                        put("text", editing.text)
                        put("selectionStart", editing.selectionStart)
                        put("selectionEnd", editing.selectionEnd)
                        put("laidOut", editing.geometry != null && editing.geometry.error == null)
                    }
                } ?: JsonNull,
            )
            putJsonObject("renderer") {
                put("selected", AppSettings.rendererId(appSettings.renderer))
                put("active", appSettings.activeRenderer?.let { JsonPrimitive(AppSettings.rendererId(it)) } ?: JsonNull)
                put("fellBack", appSettings.rendererFellBack)
            }
        }
    }

    override suspend fun composite(): Triple<Int, Int, ByteArray>? {
        if (canvas.documentSession == null) return null
        val render: SceneRender = awaitCallback { done -> engine.readComposite(done) }
        return if (render.error == null) Triple(render.width, render.height, render.pixels) else null
    }

    private companion object {
        const val EVENT_BUFFER = 64
    }
}
