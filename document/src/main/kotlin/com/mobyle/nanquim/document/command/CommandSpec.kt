package com.mobyle.nanquim.document.command

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class CommandSpec(
    val id: String,
    val titleRes: Int,
    val category: CommandCategory,
    val parameters: List<ParameterSpec> = emptyList(),
    val undoable: Boolean,
    val defaultShortcut: KeyShortcut? = null,
    val availability: (AppState) -> Availability = { Availability.Enabled },
    val aliases: List<String> = emptyList(),
) {
    init {
        require(COMMAND_ID_PATTERN.matches(id)) { "Invalid command id: $id" }
        aliases.forEach { alias -> require(COMMAND_ID_PATTERN.matches(alias)) { "Invalid alias: $alias" } }
        val parameterNames = parameters.map { it.name }
        require(parameterNames.size == parameterNames.toSet().size) { "Duplicate parameter names in $id" }
    }

    companion object {
        private val COMMAND_ID_PATTERN = Regex("^[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*)+$")
    }
}

enum class CommandCategory {
    Gallery,
    Canvas,
    Layer,
    Selection,
    Transform,
    Adjust,
    Color,
    Brush,
    Text,
    View,
    Animation,
    Export,
    Settings,
}

data class KeyShortcut(
    val key: String,
    val control: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
)

sealed interface Availability {
    data object Enabled : Availability

    data class Disabled(
        val reason: String,
    ) : Availability
}

interface AppState {
    val hasOpenDocument: Boolean
}

interface CommandContext {
    val appState: AppState
}

fun interface CommandHandler {
    suspend fun execute(
        context: CommandContext,
        arguments: JsonObject,
    ): CommandResult
}

sealed interface CommandResult {
    data class Success(
        val result: JsonObject = JsonObject(emptyMap()),
    ) : CommandResult

    data class Failure(
        val code: CommandErrorCode,
        val message: String,
    ) : CommandResult
}

enum class CommandErrorCode {
    UnknownCommand,
    Unavailable,
    InvalidArguments,
    HandlerFailed,
}

data class ParameterSpec(
    val name: String,
    val type: ParameterType,
    val defaultValue: JsonElement? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val allowedValues: List<String> = emptyList(),
) {
    init {
        require(type != ParameterType.Enumeration || allowedValues.isNotEmpty()) {
            "Enumeration parameter $name needs allowedValues"
        }
    }
}

enum class ParameterType {
    Boolean,
    Integer,
    Number,
    String,
    Enumeration,
    Object,
    Array,

    Json,
}
