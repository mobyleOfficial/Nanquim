package com.mobyle.nanquim.automation

import android.graphics.Bitmap
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import com.mobyle.nanquim.document.command.Availability
import com.mobyle.nanquim.document.command.CommandContext
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.security.MessageDigest
import java.security.SecureRandom

interface AutomationHost {
    fun state(): JsonObject

    suspend fun composite(): Triple<Int, Int, ByteArray>?

    val events: SharedFlow<JsonObject>
}

class ControlChannel(
    private val registry: CommandRegistry,
    private val context: CommandContext,
    private val host: AutomationHost,
    val token: String = newToken(),
    private val title: (Int) -> String = { "" },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandLock = Mutex()
    private var server: LocalServerSocket? = null
    private val json = Json { encodeDefaults = true }

    fun start() {
        if (server != null) return
        val socket =
            try {
                LocalServerSocket(SOCKET_NAME)
            } catch (exception: java.io.IOException) {
                Log.e(TAG, "Control channel unavailable: ${exception.message}")
                return
            }
        server = socket
        scope.launch {
            while (true) {
                val client =
                    try {
                        socket.accept()
                    } catch (exception: java.io.IOException) {
                        break
                    }
                launch { serve(client) }
            }
        }
        Log.i(TAG, "Control channel listening on localabstract:$SOCKET_NAME")
    }

    fun stop() {
        server?.close()
        server = null
        scope.cancel()
    }

    private suspend fun serve(client: LocalSocket) {
        client.use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            val writer = OutputStreamWriter(socket.outputStream, Charsets.UTF_8)
            val writeLock = Mutex()
            var authenticated = false
            var subscription: Job? = null
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val request =
                        runCatching { Json.parseToJsonElement(line).jsonObject }.getOrElse {
                            send(writer, writeLock, error(JsonNull, PARSE_ERROR, "Invalid JSON"))
                            continue
                        }
                    val id = request["id"] ?: JsonNull
                    val method = (request["method"] as? JsonPrimitive)?.content
                    val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
                    val response: JsonObject =
                        when {
                            method == null -> error(id, INVALID_REQUEST, "No method")
                            method == "authenticate" -> {
                                authenticated = constantTimeEquals((params["token"] as? JsonPrimitive)?.content.orEmpty(), token)
                                if (authenticated) result(id, JsonPrimitive(true)) else error(id, UNAUTHORIZED, "Wrong pairing token")
                            }
                            !authenticated -> error(id, UNAUTHORIZED, "Call authenticate with the pairing token first")
                            method == "listCommands" -> result(id, listCommands())
                            method == "execute" -> execute(id, params)
                            method == "getState" -> result(id, withContext(Dispatchers.Main) { host.state() })
                            method == "screenshot" -> screenshot(id)
                            method == "subscribeEvents" -> {
                                if (subscription == null) {
                                    subscription =
                                        scope.launch {
                                            host.events.collect { event ->
                                                val notification =
                                                    buildJsonObject {
                                                        put("jsonrpc", "2.0")
                                                        put("method", "event")
                                                        put("params", event)
                                                    }
                                                send(writer, writeLock, notification)
                                            }
                                        }
                                }
                                result(id, JsonPrimitive(true))
                            }
                            else -> error(id, METHOD_NOT_FOUND, "Unknown method: $method")
                        }
                    if (request.containsKey("id")) send(writer, writeLock, response)
                }
            } catch (exception: java.io.IOException) {
                Log.i(TAG, "Control channel client left: ${exception.message}")
            } finally {
                subscription?.cancel()
            }
        }
    }

    private fun listCommands(): JsonElement =
        buildJsonArray {
            for (spec in registry.commands()) {
                add(spec.toJson(registry.availability(spec.id, context.appState)))
            }
        }

    private suspend fun execute(
        id: JsonElement,
        params: JsonObject,
    ): JsonObject {
        val commandId = (params["id"] as? JsonPrimitive)?.content ?: return error(id, INVALID_PARAMS, "params.id is required")
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val outcome =
            commandLock.withLock {
                withContext(Dispatchers.Main) { registry.execute(context, commandId, arguments) }
            }
        return when (outcome) {
            is CommandResult.Success -> result(id, outcome.result)
            is CommandResult.Failure ->
                error(id, COMMAND_FAILED, outcome.message, buildJsonObject { put("code", outcome.code.name) })
        }
    }

    private suspend fun screenshot(id: JsonElement): JsonObject {
        val (width, height, pixels) = withContext(Dispatchers.Main) { host.composite() } ?: return error(id, COMMAND_FAILED, "No open document")
        val colors = IntArray(width * height)
        for (index in colors.indices) {
            val offset = index * 4
            colors[index] =
                ((pixels[offset + 3].toInt() and 0xFF) shl 24) or ((pixels[offset].toInt() and 0xFF) shl 16) or
                ((pixels[offset + 1].toInt() and 0xFF) shl 8) or (pixels[offset + 2].toInt() and 0xFF)
        }
        val bitmap = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
        val png = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }.toByteArray()
        bitmap.recycle()
        return result(
            id,
            buildJsonObject {
                put("width", width)
                put("height", height)
                put("png", Base64.encodeToString(png, Base64.NO_WRAP))
            },
        )
    }

    private suspend fun send(
        writer: Writer,
        lock: Mutex,
        message: JsonObject,
    ) = lock.withLock {
        withContext(Dispatchers.IO) {
            writer.write(json.encodeToString(JsonObject.serializer(), message))
            writer.write("\n")
            writer.flush()
        }
    }

    private fun result(
        id: JsonElement,
        value: JsonElement,
    ) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", value)
    }

    private fun error(
        id: JsonElement,
        code: Int,
        message: String,
        data: JsonElement? = null,
    ) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
                data?.let { put("data", it) }
            },
        )
    }

    private fun CommandSpec.toJson(availability: Availability?): JsonObject =
        buildJsonObject {
            put("id", id)
            put("title", title(titleRes))
            put("category", category.name)
            put("undoable", undoable)
            put("available", availability == null || availability == Availability.Enabled)
            putJsonArray("parameters") {
                for (parameter in parameters) {
                    add(
                        buildJsonObject {
                            put("name", parameter.name)
                            put("type", parameter.type.name)
                            put("required", parameter.defaultValue == null)
                            parameter.defaultValue?.let { put("default", it) }
                            parameter.minimum?.let { put("minimum", it) }
                            parameter.maximum?.let { put("maximum", it) }
                            if (parameter.allowedValues.isNotEmpty()) {
                                putJsonArray("allowedValues") { parameter.allowedValues.forEach { add(JsonPrimitive(it)) } }
                            }
                        },
                    )
                }
            }
        }

    companion object {
        private const val TAG = "ControlChannel"
        const val SOCKET_NAME = "nanquim-control"
        private const val PNG_QUALITY = 100

        private const val PARSE_ERROR = -32700
        private const val INVALID_REQUEST = -32600
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602
        private const val COMMAND_FAILED = -32000
        private const val UNAUTHORIZED = -32001

        private const val TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val TOKEN_LENGTH = 8

        fun newToken(): String {
            val random = SecureRandom()
            return String(CharArray(TOKEN_LENGTH) { TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)] })
        }

        private fun constantTimeEquals(
            given: String,
            expected: String,
        ) = MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())
    }
}

suspend fun <T> awaitCallback(start: ((T) -> Unit) -> Unit): T {
    val done = CompletableDeferred<T>()
    start { done.complete(it) }
    return done.await()
}
