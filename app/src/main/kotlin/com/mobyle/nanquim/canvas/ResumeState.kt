package com.mobyle.nanquim.canvas

import android.content.SharedPreferences
import androidx.core.content.edit
import com.mobyle.nanquim.canvas.camera.CameraState
import com.mobyle.nanquim.canvas.input.CanvasTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** How the open artwork was being looked at: restored with the artwork when the app comes back after being closed. */
data class SavedView(
    val artworkId: String,
    val camera: CameraState?,
    val tool: CanvasTool,
) {
    fun toJson(): String =
        buildJsonObject {
            put("artwork", artworkId)
            put("tool", tool.name)
            camera?.let {
                put(
                    "camera",
                    buildJsonObject {
                        put("x", it.centerX)
                        put("y", it.centerY)
                        put("zoom", it.zoom)
                        put("rotation", it.rotationRadians)
                        put("flipped", it.flippedHorizontally)
                    },
                )
            }
        }.toString()

    companion object {
        /** Tools that are safe to come back to: the others start an edit (transform, crop…) that must not reopen by itself. */
        val RESTORABLE_TOOLS = setOf(CanvasTool.Brush, CanvasTool.Smudge, CanvasTool.Eraser)

        fun fromJson(text: String): SavedView? =
            runCatching {
                val json = Json.parseToJsonElement(text).jsonObject
                val camera =
                    (json["camera"] as? JsonObject)?.let {
                        CameraState(
                            centerX = it.getValue("x").jsonPrimitive.double,
                            centerY = it.getValue("y").jsonPrimitive.double,
                            zoom = it.getValue("zoom").jsonPrimitive.double,
                            rotationRadians = it.getValue("rotation").jsonPrimitive.double,
                            flippedHorizontally = it.getValue("flipped").jsonPrimitive.boolean,
                        ).takeIf { state ->
                            listOf(state.centerX, state.centerY, state.zoom, state.rotationRadians).all(Double::isFinite) &&
                                state.zoom > 0
                        }
                    }
                val tool =
                    CanvasTool.entries.firstOrNull { it.name == json["tool"]?.jsonPrimitive?.content }?.takeIf { it in RESTORABLE_TOOLS }
                        ?: CanvasTool.Brush
                SavedView(json.getValue("artwork").jsonPrimitive.content, camera, tool)
            }.getOrNull()
    }
}

class ResumeState(
    private val preferences: SharedPreferences,
) {
    fun save(session: CanvasSession) {
        val artworkId = session.artwork?.id ?: return
        preferences.edit { putString(KEY_VIEW, SavedView(artworkId, session.viewport.camera, session.canvasTool).toJson()) }
    }

    /** Puts the camera and the tool back if [artworkId] is the artwork they were saved with. */
    fun restore(
        session: CanvasSession,
        artworkId: String,
    ) {
        val saved = preferences.getString(KEY_VIEW, null)?.let(SavedView::fromJson)?.takeIf { it.artworkId == artworkId } ?: return
        saved.camera?.let(session.viewport::restoreCamera)
        if (saved.tool != session.canvasTool) session.canvasTool = saved.tool
    }

    companion object {
        const val PREFERENCES_NAME = "resume"
        private const val KEY_VIEW = "view"
    }
}
