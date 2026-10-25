package com.mobyle.nanquim.macro

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.UUID

data class MacroStep(
    val commandId: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
)

data class Macro(
    val id: String,
    val name: String,
    val steps: List<MacroStep>,
) {
    fun toJson(): JsonObject =
        JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "name" to JsonPrimitive(name),
                "steps" to
                    JsonArray(
                        steps.map { JsonObject(mapOf("command" to JsonPrimitive(it.commandId), "arguments" to it.arguments)) },
                    ),
            ),
        )

    companion object {
        fun fromJson(json: JsonObject): Macro? {
            val id = (json["id"] as? JsonPrimitive)?.contentOrNull ?: return null
            val steps =
                (json["steps"] as? JsonArray).orEmpty().mapNotNull { step ->
                    val fields = step as? JsonObject ?: return@mapNotNull null
                    val command = (fields["command"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    MacroStep(command, fields["arguments"] as? JsonObject ?: JsonObject(emptyMap()))
                }
            return Macro(id, (json["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(), steps)
        }

        fun records(commandId: String): Boolean =
            NOT_RECORDED_PREFIXES.none(commandId::startsWith) &&
                NOT_RECORDED_SUFFIXES.none(commandId::endsWith) &&
                commandId !in NOT_RECORDED

        private val NOT_RECORDED_PREFIXES =
            listOf("macro.", "settings.", "gallery.", "debug.", "quickMenu.", "brush.library.", "brush.editor.", "color.panel.")
        private val NOT_RECORDED_SUFFIXES = listOf(".get", ".list")
        private val NOT_RECORDED =
            setOf("view.layersPanel", "view.copyPasteMenu", "view.exportSheet", "canvas.info.show", "color.history.list")
    }
}

class Macros(
    private val folder: File,
) {
    private val file get() = File(folder, FILE_NAME)

    var macros: List<Macro> by mutableStateOf(load())
        private set

    var panelVisible: Boolean by mutableStateOf(false)

    var recordingName: String? by mutableStateOf(null)
        private set

    var recordedSteps: Int by mutableIntStateOf(0)
        private set

    private val steps = mutableListOf<MacroStep>()

    fun startRecording(name: String) {
        synchronized(steps) { steps.clear() }
        recordedSteps = 0
        recordingName = name
    }

    fun record(
        commandId: String,
        arguments: JsonObject,
    ) {
        if (recordingName == null || !Macro.records(commandId)) return
        synchronized(steps) {
            steps += MacroStep(commandId, arguments)
            recordedSteps = steps.size
        }
    }

    fun stopRecording(): Macro? {
        val name = recordingName ?: return null
        val recorded = synchronized(steps) { steps.toList().also { steps.clear() } }
        recordingName = null
        recordedSteps = 0
        if (recorded.isEmpty()) return null
        val macro = Macro(UUID.randomUUID().toString(), name, recorded)
        save(macros + macro)
        return macro
    }

    fun cancelRecording() {
        synchronized(steps) { steps.clear() }
        recordingName = null
        recordedSteps = 0
    }

    fun find(id: String): Macro? = macros.firstOrNull { it.id == id }

    fun rename(
        id: String,
        name: String,
    ): Boolean {
        if (find(id) == null) return false
        save(macros.map { if (it.id == id) it.copy(name = name) else it })
        return true
    }

    fun delete(id: String): Boolean {
        if (find(id) == null) return false
        save(macros.filter { it.id != id })
        return true
    }

    private fun save(updated: List<Macro>) {
        macros = updated
        folder.mkdirs()
        val temporary = File(folder, "$FILE_NAME.tmp")
        temporary.writeText(JsonArray(updated.map { it.toJson() }).toString())
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }

    private fun load(): List<Macro> =
        runCatching { Json.parseToJsonElement(file.readText()) as? JsonArray }
            .getOrNull()
            .orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(Macro::fromJson) }

    companion object {
        const val FOLDER = "macros"
        private const val FILE_NAME = "macros.json"
    }
}
