package com.mobyle.nanquim.document.command

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class CommandRegistry {
    private class Registration(
        val spec: CommandSpec,
        val handler: CommandHandler,
    )

    private val registrationsById = ConcurrentHashMap<String, Registration>()
    private val canonicalIdByAlias = ConcurrentHashMap<String, String>()

    @Volatile
    var onExecuted: ((commandId: String, arguments: JsonObject, result: CommandResult) -> Unit)? = null

    private class NestedExecution : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<NestedExecution>
    }

    fun register(
        spec: CommandSpec,
        handler: CommandHandler,
    ) {
        val idsToClaim = listOf(spec.id) + spec.aliases
        idsToClaim.forEach { claimedId ->
            require(!registrationsById.containsKey(claimedId) && !canonicalIdByAlias.containsKey(claimedId)) {
                "Command id already registered: $claimedId"
            }
        }
        registrationsById[spec.id] = Registration(spec, handler)
        spec.aliases.forEach { alias -> canonicalIdByAlias[alias] = spec.id }
    }

    fun commands(): List<CommandSpec> = registrationsById.values.map { it.spec }.sortedBy { it.id }

    fun find(idOrAlias: String): CommandSpec? = resolve(idOrAlias)?.spec

    fun availability(
        idOrAlias: String,
        appState: AppState,
    ): Availability? = resolve(idOrAlias)?.spec?.availability?.invoke(appState)

    suspend fun execute(
        context: CommandContext,
        idOrAlias: String,
        arguments: JsonObject = JsonObject(emptyMap()),
    ): CommandResult {
        val registration =
            resolve(idOrAlias)
                ?: return CommandResult.Failure(CommandErrorCode.UnknownCommand, "Unknown command: $idOrAlias")
        val availability = registration.spec.availability(context.appState)
        if (availability is Availability.Disabled) {
            return CommandResult.Failure(CommandErrorCode.Unavailable, availability.reason)
        }
        val resolvedArguments =
            when (val validation = validateArguments(registration.spec, arguments)) {
                is ArgumentValidation.Valid -> validation.arguments
                is ArgumentValidation.Invalid -> return CommandResult.Failure(CommandErrorCode.InvalidArguments, validation.message)
            }
        val topLevel = currentCoroutineContext()[NestedExecution] == null
        val result =
            try {
                if (topLevel) {
                    withContext(NestedExecution()) { registration.handler.execute(context, resolvedArguments) }
                } else {
                    registration.handler.execute(context, resolvedArguments)
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                CommandResult.Failure(CommandErrorCode.HandlerFailed, exception.message ?: exception::class.java.simpleName)
            }
        if (topLevel) onExecuted?.invoke(registration.spec.id, arguments, result)
        return result
    }

    private fun resolve(idOrAlias: String): Registration? {
        val canonicalId = canonicalIdByAlias[idOrAlias] ?: idOrAlias
        return registrationsById[canonicalId]
    }

    private sealed interface ArgumentValidation {
        data class Valid(
            val arguments: JsonObject,
        ) : ArgumentValidation

        data class Invalid(
            val message: String,
        ) : ArgumentValidation
    }

    private fun validateArguments(
        spec: CommandSpec,
        arguments: JsonObject,
    ): ArgumentValidation {
        val parametersByName = spec.parameters.associateBy { it.name }
        val unknownNames = arguments.keys - parametersByName.keys
        if (unknownNames.isNotEmpty()) {
            return ArgumentValidation.Invalid("Unknown parameters for ${spec.id}: ${unknownNames.sorted().joinToString()}")
        }
        val resolved = LinkedHashMap<String, JsonElement>()
        for (parameter in spec.parameters) {
            val value =
                arguments[parameter.name]
                    ?: parameter.defaultValue
                    ?: return ArgumentValidation.Invalid("Missing required parameter '${parameter.name}' for ${spec.id}")
            val optionalAndAbsent = value is JsonNull && parameter.defaultValue is JsonNull
            val problem = if (optionalAndAbsent) null else describeTypeProblem(parameter, value)
            if (problem != null) return ArgumentValidation.Invalid("Parameter '${parameter.name}' of ${spec.id}: $problem")
            resolved[parameter.name] = value
        }
        return ArgumentValidation.Valid(JsonObject(resolved))
    }

    private fun describeTypeProblem(
        parameter: ParameterSpec,
        value: JsonElement,
    ): String? =
        when (parameter.type) {
            ParameterType.Boolean ->
                if ((value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ==
                    null
                ) {
                    "expected boolean"
                } else {
                    null
                }
            ParameterType.Integer -> {
                val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                if (number == null) "expected integer" else describeRangeProblem(parameter, number.toDouble())
            }
            ParameterType.Number -> {
                val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                if (number == null) "expected number" else describeRangeProblem(parameter, number)
            }
            ParameterType.String -> if ((value as? JsonPrimitive)?.isString != true) "expected string" else null
            ParameterType.Enumeration -> {
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (text == null || text !in parameter.allowedValues) "expected one of ${parameter.allowedValues}" else null
            }
            ParameterType.Object -> if (value !is JsonObject) "expected object" else null
            ParameterType.Array -> if (value !is JsonArray) "expected array" else null
            ParameterType.Json -> null
        }

    private fun describeRangeProblem(
        parameter: ParameterSpec,
        number: Double,
    ): String? =
        when {
            parameter.minimum != null && number < parameter.minimum -> "must be >= ${parameter.minimum}"
            parameter.maximum != null && number > parameter.maximum -> "must be <= ${parameter.maximum}"
            else -> null
        }
}
