package com.mobyle.nanquim.document.command

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CommandRegistryTest {
    private class FakeAppState(
        override val hasOpenDocument: Boolean,
    ) : AppState

    private class FakeContext(
        override val appState: AppState,
    ) : CommandContext

    private val contextWithDocument = FakeContext(FakeAppState(hasOpenDocument = true))

    private fun echoHandler() = CommandHandler { _, arguments -> CommandResult.Success(arguments) }

    private fun opacitySpec(aliases: List<String> = emptyList()) =
        CommandSpec(
            id = "layer.setOpacity",
            titleRes = 0,
            category = CommandCategory.Layer,
            parameters =
                listOf(
                    ParameterSpec("opacity", ParameterType.Number, minimum = 0.0, maximum = 1.0),
                    ParameterSpec("animate", ParameterType.Boolean, defaultValue = JsonPrimitive(false)),
                ),
            undoable = true,
            availability = { state -> if (state.hasOpenDocument) Availability.Enabled else Availability.Disabled("No document") },
            aliases = aliases,
        )

    @Test
    fun `executes handler with defaults filled in`() =
        runTest {
            val registry = CommandRegistry()
            registry.register(opacitySpec(), echoHandler())

            val result = registry.execute(contextWithDocument, "layer.setOpacity", buildJsonObject { put("opacity", 0.5) })

            val success = assertInstanceOf(CommandResult.Success::class.java, result)
            assertEquals(JsonPrimitive(0.5), success.result["opacity"])
            assertEquals(JsonPrimitive(false), success.result["animate"])
        }

    @Test
    fun `reports only top-level commands to the observer`() =
        runTest {
            val registry = CommandRegistry()
            registry.register(opacitySpec(aliases = listOf("layer.opacity")), echoHandler())
            registry.register(CommandSpec("macro.run", 0, CommandCategory.Settings, undoable = false)) { context, _ ->
                registry.execute(context, "layer.setOpacity", buildJsonObject { put("opacity", 0.25) })
            }
            val reported = mutableListOf<Pair<String, JsonObject>>()
            registry.onExecuted = { id, arguments, _ -> reported += id to arguments }

            registry.execute(contextWithDocument, "layer.opacity", buildJsonObject { put("opacity", 0.5) })
            registry.execute(contextWithDocument, "macro.run")

            assertEquals(
                listOf("layer.setOpacity" to buildJsonObject { put("opacity", 0.5) }, "macro.run" to JsonObject(emptyMap())),
                reported,
            )
        }

    @Test
    fun `resolves aliases to the canonical command`() =
        runTest {
            val registry = CommandRegistry()
            registry.register(opacitySpec(aliases = listOf("layer.opacity")), echoHandler())

            val result = registry.execute(contextWithDocument, "layer.opacity", buildJsonObject { put("opacity", 1) })

            assertInstanceOf(CommandResult.Success::class.java, result)
            assertEquals("layer.setOpacity", registry.find("layer.opacity")?.id)
        }

    @Test
    fun `reports unknown commands`() =
        runTest {
            val result = CommandRegistry().execute(contextWithDocument, "layer.doesNotExist")

            assertEquals(CommandErrorCode.UnknownCommand, (result as CommandResult.Failure).code)
        }

    @Test
    fun `refuses unavailable commands with the reason`() =
        runTest {
            val registry = CommandRegistry()
            registry.register(opacitySpec(), echoHandler())

            val result =
                registry.execute(
                    FakeContext(FakeAppState(hasOpenDocument = false)),
                    "layer.setOpacity",
                    buildJsonObject { put("opacity", 1) },
                )

            val failure = assertInstanceOf(CommandResult.Failure::class.java, result)
            assertEquals(CommandErrorCode.Unavailable, failure.code)
            assertEquals("No document", failure.message)
        }

    @Test
    fun `rejects missing, unknown, mistyped and out-of-range arguments`() =
        runTest {
            val registry = CommandRegistry()
            registry.register(opacitySpec(), echoHandler())
            val invalidArguments =
                listOf(
                    JsonObject(emptyMap()),
                    buildJsonObject {
                        put("opacity", 0.5)
                        put("color", "red")
                    },
                    buildJsonObject { put("opacity", "half") },
                    buildJsonObject { put("opacity", 1.5) },
                    buildJsonObject {
                        put("opacity", 0.5)
                        put("animate", "yes")
                    },
                )

            invalidArguments.forEach { arguments ->
                val result = registry.execute(contextWithDocument, "layer.setOpacity", arguments)
                assertEquals(CommandErrorCode.InvalidArguments, (result as CommandResult.Failure).code, "arguments: $arguments")
            }
        }

    @Test
    fun `validates enumerations`() =
        runTest {
            val registry = CommandRegistry()
            registry.register(
                CommandSpec(
                    id = "layer.setBlend",
                    titleRes = 0,
                    category = CommandCategory.Layer,
                    parameters = listOf(ParameterSpec("blend", ParameterType.Enumeration, allowedValues = listOf("normal", "multiply"))),
                    undoable = true,
                ),
                echoHandler(),
            )

            val accepted = registry.execute(contextWithDocument, "layer.setBlend", buildJsonObject { put("blend", "multiply") })
            val rejected = registry.execute(contextWithDocument, "layer.setBlend", buildJsonObject { put("blend", "glow") })

            assertInstanceOf(CommandResult.Success::class.java, accepted)
            assertEquals(CommandErrorCode.InvalidArguments, (rejected as CommandResult.Failure).code)
        }

    @Test
    fun `converts handler exceptions into failures`() =
        runTest {
            val registry = CommandRegistry()
            registry.register(
                CommandSpec(id = "canvas.info", titleRes = 0, category = CommandCategory.Canvas, undoable = false),
            ) { _, _ -> error("boom") }

            val result = registry.execute(contextWithDocument, "canvas.info")

            val failure = assertInstanceOf(CommandResult.Failure::class.java, result)
            assertEquals(CommandErrorCode.HandlerFailed, failure.code)
            assertEquals("boom", failure.message)
        }

    @Test
    fun `refuses duplicate ids and aliases`() {
        val registry = CommandRegistry()
        registry.register(opacitySpec(aliases = listOf("layer.opacity")), echoHandler())

        assertThrows<IllegalArgumentException> { registry.register(opacitySpec(), echoHandler()) }
        assertThrows<IllegalArgumentException> {
            registry.register(
                CommandSpec(id = "layer.opacity", titleRes = 0, category = CommandCategory.Layer, undoable = false),
                echoHandler(),
            )
        }
    }

    @Test
    fun `rejects malformed ids`() {
        listOf("Layer.new", "layer", "layer..new", "layer.new-one", "").forEach { badId ->
            assertThrows<IllegalArgumentException>("id: '$badId'") {
                CommandSpec(id = badId, titleRes = 0, category = CommandCategory.Layer, undoable = false)
            }
        }
    }

    @Test
    fun `lists commands sorted by id and reports availability`() {
        val registry = CommandRegistry()
        registry.register(CommandSpec(id = "view.fit", titleRes = 0, category = CommandCategory.View, undoable = false), echoHandler())
        registry.register(opacitySpec(), echoHandler())

        assertEquals(listOf("layer.setOpacity", "view.fit"), registry.commands().map { it.id })
        assertEquals(Availability.Disabled("No document"), registry.availability("layer.setOpacity", FakeAppState(false)))
        assertNull(registry.availability("missing.command", FakeAppState(true)))
    }
}
