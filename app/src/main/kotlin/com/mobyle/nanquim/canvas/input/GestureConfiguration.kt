package com.mobyle.nanquim.canvas.input

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

data class GestureConfiguration(
    val triggers: Map<GestureAction, Set<Gesture>>,
    val longPressMillis: Int = GestureArbiter.LONG_PRESS_MILLIS.toInt(),
    val repeatDelayMillis: Int = GestureArbiter.REPEAT_DELAY_MILLIS.toInt(),
    val rotation: Boolean = true,
    val stylusDisablesFingerStrokes: Boolean = false,
    val shortcutBrush: ShortcutBrush? = null,
) {
    data class ShortcutBrush(
        val id: String,
        val name: String,
    )

    fun triggersOf(action: GestureAction): Set<Gesture> = triggers[action].orEmpty()

    val conflicts: Map<Gesture, List<GestureAction>> by lazy {
        Gesture.entries
            .associateWith { gesture -> ACTIONS.filter { gesture in triggersOf(it) } }
            .filterValues { it.size > 1 }
    }

    val bindings: GestureBindings by lazy {
        GestureBindings(
            actions =
                Gesture.entries
                    .mapNotNull { gesture -> ACTIONS.firstOrNull { gesture in triggersOf(it) }?.let { gesture to it } }
                    .toMap(),
            longPressMillis = longPressMillis.toLong(),
            repeatDelayMillis = repeatDelayMillis.toLong(),
            stylusDisablesFingerStrokes = stylusDisablesFingerStrokes,
        )
    }

    fun withTriggers(
        action: GestureAction,
        gestures: Set<Gesture>,
    ): GestureConfiguration = copy(triggers = triggers + (action to gestures.filter { allows(action, it) }.toSet()))

    fun toJson(): JsonObject =
        buildJsonObject {
            put(
                "triggers",
                JsonObject(
                    ACTIONS.associate { action ->
                        action.id to
                            JsonArray(triggersOf(action).sortedBy { it.ordinal }.map { JsonPrimitive(it.id) })
                    },
                ),
            )
            put("longPressDelay", longPressMillis)
            put("repeatDelay", repeatDelayMillis)
            put("rotation", rotation)
            put("stylusDisablesFingerStrokes", stylusDisablesFingerStrokes)
            shortcutBrush?.let {
                put(
                    "shortcutBrush",
                    buildJsonObject {
                        put("id", it.id)
                        put("name", it.name)
                    },
                )
            }
        }

    companion object {
        val ACTIONS =
            listOf(
                GestureAction.Paint,
                GestureAction.Smudge,
                GestureAction.Erase,
                GestureAction.AssistedDrawing,
                GestureAction.Eyedropper,
                GestureAction.QuickMenu,
                GestureAction.FullScreen,
                GestureAction.ClearLayer,
                GestureAction.CopyPasteMenu,
                GestureAction.LayerSelect,
                GestureAction.Undo,
                GestureAction.Redo,
                GestureAction.FitCanvas,
                GestureAction.ToggleEraser,
                GestureAction.ShortcutBrush,
            )

        val LONG_PRESS_DELAYS = 200..1500
        val REPEAT_DELAYS = 200..1500

        fun allows(
            action: GestureAction,
            gesture: Gesture,
        ): Boolean =
            when {
                action !in ACTIONS -> false
                action.stroke -> gesture.continuous
                gesture == Gesture.FingerDrag -> false
                gesture == Gesture.ModifierTap -> !action.atPoint
                else -> true
            }

        fun defaults(
            undoFingers: Int = GestureArbiter.DEFAULT_UNDO_FINGERS,
            fingerPainting: Boolean = false,
        ): GestureConfiguration {
            val triggers =
                mapOf(
                    GestureAction.Paint to if (fingerPainting) setOf(Gesture.FingerDrag) else emptySet(),
                    GestureAction.Eyedropper to setOf(Gesture.LongPress, Gesture.StylusButton, Gesture.ModifierTap),
                    GestureAction.FullScreen to setOf(Gesture.FourFingerTap),
                    GestureAction.ClearLayer to setOf(Gesture.ThreeFingerScrub),
                    GestureAction.CopyPasteMenu to setOf(Gesture.ThreeFingerSwipeDown),
                    GestureAction.LayerSelect to setOf(Gesture.ModifierTouch),
                    GestureAction.Undo to setOf(if (undoFingers == 1) Gesture.OneFingerTap else Gesture.TwoFingerTap),
                    GestureAction.Redo to setOf(Gesture.ThreeFingerTap),
                    GestureAction.FitCanvas to setOf(Gesture.QuickPinch),
                    GestureAction.ToggleEraser to setOf(Gesture.StylusButtonDoublePress),
                )
            return GestureConfiguration(ACTIONS.associateWith { triggers[it].orEmpty() })
        }

        fun fromJson(json: JsonObject): GestureConfiguration {
            val defaults = defaults()
            val stored = json["triggers"] as? JsonObject
            val triggers =
                ACTIONS.associateWith { action ->
                    val ids = stored?.get(action.id) as? JsonArray ?: return@associateWith defaults.triggersOf(action)
                    ids
                        .mapNotNull { Gesture.fromId((it as? JsonPrimitive)?.content) }
                        .filter { allows(action, it) }
                        .toSet()
                }

            fun int(name: String) = (json[name] as? JsonPrimitive)?.intOrNull

            fun boolean(name: String) = (json[name] as? JsonPrimitive)?.booleanOrNull
            val brush = json["shortcutBrush"] as? JsonObject
            val brushId = (brush?.get("id") as? JsonPrimitive)?.contentOrNull
            return GestureConfiguration(
                triggers = triggers,
                longPressMillis = (int("longPressDelay") ?: defaults.longPressMillis).coerceIn(LONG_PRESS_DELAYS),
                repeatDelayMillis = (int("repeatDelay") ?: defaults.repeatDelayMillis).coerceIn(REPEAT_DELAYS),
                rotation = boolean("rotation") ?: defaults.rotation,
                stylusDisablesFingerStrokes = boolean("stylusDisablesFingerStrokes") ?: defaults.stylusDisablesFingerStrokes,
                shortcutBrush = brushId?.let { ShortcutBrush(it, (brush["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()) },
            )
        }
    }
}
