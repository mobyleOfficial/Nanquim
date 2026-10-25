package com.mobyle.nanquim.canvas.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.math.roundToLong

data class TouchSession(
    val label: String,
    val recordedBy: String,
    val events: List<TouchEvent>,
)

data class TouchSessionSet(
    val device: String,
    val xdpi: Float,
    val sessions: List<TouchSession>,
) {
    fun millimetres(pixels: Float): Float = pixels * MILLIMETRES_PER_INCH / xdpi

    companion object {
        const val FORMAT = "nanquim-touch-sessions"
        const val VERSION = 1
        const val LABEL_PALM = "palm"
        const val LABEL_GESTURES = "gestures"
        val LABELS = listOf(LABEL_PALM, LABEL_GESTURES)
        const val MILLIMETRES_PER_INCH = 25.4f

        private const val CANCELED = "canceled"

        private val json = Json { prettyPrint = false }

        private val actionNames =
            mapOf(
                TouchAction.Down to "down",
                TouchAction.PointerDown to "pointerDown",
                TouchAction.Move to "move",
                TouchAction.PointerUp to "pointerUp",
                TouchAction.Up to "up",
                TouchAction.Cancel to "cancel",
                TouchAction.HoverEnter to "hoverEnter",
                TouchAction.HoverMove to "hoverMove",
                TouchAction.HoverExit to "hoverExit",
            )
        private val toolNames =
            mapOf(
                PointerTool.Finger to "finger",
                PointerTool.Stylus to "stylus",
                PointerTool.StylusEraser to "eraser",
                PointerTool.Mouse to "mouse",
                PointerTool.Palm to "palm",
                PointerTool.Unknown to "unknown",
            )

        fun encode(set: TouchSessionSet): String =
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("format", FORMAT)
                    put("version", VERSION)
                    put("device", set.device)
                    put("xdpi", set.xdpi)
                    put(
                        "sessions",
                        JsonArray(
                            set.sessions.map { session ->
                                buildJsonObject {
                                    put("label", session.label)
                                    put("recordedBy", session.recordedBy)
                                    put("events", JsonArray(session.events.map(::eventJson)))
                                }
                            },
                        ),
                    )
                },
            )

        fun decode(text: String): TouchSessionSet {
            val root = json.parseToJsonElement(text).jsonObject
            require(root["format"]?.jsonPrimitive?.content == FORMAT) { "Not a touch session set" }
            require(root.getValue("version").jsonPrimitive.int <= VERSION) { "A newer touch session set" }
            val actions = actionNames.entries.associate { (action, name) -> name to action }
            val tools = toolNames.entries.associate { (tool, name) -> name to tool }
            val sessions =
                root.getValue("sessions").jsonArray.map { element ->
                    val session = element.jsonObject
                    val label = session.getValue("label").jsonPrimitive.content
                    require(label in LABELS) { "Unknown label $label" }
                    TouchSession(
                        label = label,
                        recordedBy = session.getValue("recordedBy").jsonPrimitive.content,
                        events =
                            session.getValue("events").jsonArray.map { eventElement ->
                                val event = eventElement.jsonArray
                                TouchEvent(
                                    action = requireNotNull(actions[event[1].jsonPrimitive.content]) { "Unknown action" },
                                    actionIndex = event[2].jsonPrimitive.int,
                                    timeMillis = event[0].jsonPrimitive.long,
                                    pointers =
                                        event[3].jsonArray.map { pointerElement ->
                                            val pointer = pointerElement.jsonArray
                                            TouchPointer(
                                                id = pointer[0].jsonPrimitive.int,
                                                tool = tools[pointer[1].jsonPrimitive.content] ?: PointerTool.Unknown,
                                                x = pointer[2].jsonPrimitive.double.toFloat(),
                                                y = pointer[3].jsonPrimitive.double.toFloat(),
                                                touchMajor = pointer[4].jsonPrimitive.double.toFloat(),
                                                touchMinor = pointer[5].jsonPrimitive.double.toFloat(),
                                            )
                                        },
                                    canceled = event.getOrNull(4)?.jsonPrimitive?.content == CANCELED,
                                )
                            },
                    )
                }
            return TouchSessionSet(
                device = root["device"]?.jsonPrimitive?.content.orEmpty(),
                xdpi =
                    root
                        .getValue("xdpi")
                        .jsonPrimitive.double
                        .toFloat(),
                sessions = sessions,
            )
        }

        private fun eventJson(event: TouchEvent): JsonArray =
            buildJsonArray {
                add(JsonPrimitive(event.timeMillis))
                add(JsonPrimitive(actionNames.getValue(event.action)))
                add(JsonPrimitive(event.actionIndex))
                add(
                    JsonArray(
                        event.pointers.map { pointer ->
                            buildJsonArray {
                                add(JsonPrimitive(pointer.id))
                                add(JsonPrimitive(toolNames.getValue(pointer.tool)))
                                add(JsonPrimitive(tenths(pointer.x)))
                                add(JsonPrimitive(tenths(pointer.y)))
                                add(JsonPrimitive(tenths(pointer.touchMajor)))
                                add(JsonPrimitive(tenths(pointer.touchMinor)))
                            }
                        },
                    ),
                )
                if (event.canceled) add(JsonPrimitive(CANCELED))
            }

        private fun tenths(value: Float): Double = (value * 10.0).roundToLong() / 10.0
    }
}
