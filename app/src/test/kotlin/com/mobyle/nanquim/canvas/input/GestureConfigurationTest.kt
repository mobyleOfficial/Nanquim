package com.mobyle.nanquim.canvas.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GestureConfigurationTest {
    @Test
    fun defaultsFollowTheGestureTable() {
        val bindings = GestureConfiguration.defaults().bindings
        val expected =
            mapOf(
                Gesture.TwoFingerTap to GestureAction.Undo,
                Gesture.ThreeFingerTap to GestureAction.Redo,
                Gesture.FourFingerTap to GestureAction.FullScreen,
                Gesture.LongPress to GestureAction.Eyedropper,
                Gesture.ThreeFingerScrub to GestureAction.ClearLayer,
                Gesture.ThreeFingerSwipeDown to GestureAction.CopyPasteMenu,
                Gesture.QuickPinch to GestureAction.FitCanvas,
                Gesture.StylusButton to GestureAction.Eyedropper,
                Gesture.StylusButtonDoublePress to GestureAction.ToggleEraser,
                Gesture.ModifierTap to GestureAction.Eyedropper,
                Gesture.ModifierTouch to GestureAction.LayerSelect,
            )
        assertEquals(expected, bindings.actions)
        assertEquals(GestureAction.None, bindings.actionOf(Gesture.OneFingerTap))
        assertEquals(GestureAction.None, bindings.actionOf(Gesture.FingerDrag))
        assertTrue(GestureConfiguration.defaults().conflicts.isEmpty())
        val older = GestureConfiguration.defaults(undoFingers = 1, fingerPainting = true).bindings
        assertEquals(GestureAction.Undo, older.actionOf(Gesture.OneFingerTap))
        assertEquals(GestureAction.None, older.actionOf(Gesture.TwoFingerTap))
        assertEquals(GestureAction.Paint, older.actionOf(Gesture.FingerDrag))
    }

    @Test
    fun equalTriggersAreConflictsAndTheFirstActionListedWins() {
        val configuration =
            GestureConfiguration
                .defaults()
                .withTriggers(GestureAction.QuickMenu, setOf(Gesture.TwoFingerTap, Gesture.OneFingerTap))
        assertEquals(mapOf(Gesture.TwoFingerTap to listOf(GestureAction.QuickMenu, GestureAction.Undo)), configuration.conflicts)
        assertEquals(GestureAction.QuickMenu, configuration.bindings.actionOf(Gesture.TwoFingerTap))
        assertEquals(GestureAction.QuickMenu, configuration.bindings.actionOf(Gesture.OneFingerTap))
        val resolved = configuration.withTriggers(GestureAction.Undo, emptySet())
        assertTrue(resolved.conflicts.isEmpty())
    }

    @Test
    fun actionsTakeOnlyTriggersTheyCanUse() {
        assertTrue(GestureConfiguration.allows(GestureAction.Smudge, Gesture.StylusButton))
        assertTrue(GestureConfiguration.allows(GestureAction.Erase, Gesture.ModifierTouch))
        assertFalse(GestureConfiguration.allows(GestureAction.Smudge, Gesture.TwoFingerTap))
        assertFalse(GestureConfiguration.allows(GestureAction.Smudge, Gesture.LongPress))
        assertFalse(GestureConfiguration.allows(GestureAction.Undo, Gesture.FingerDrag))
        assertFalse(GestureConfiguration.allows(GestureAction.LayerSelect, Gesture.ModifierTap))
        assertTrue(GestureConfiguration.allows(GestureAction.Eyedropper, Gesture.ModifierTap))
        assertFalse(GestureConfiguration.allows(GestureAction.None, Gesture.OneFingerTap))
        val configuration =
            GestureConfiguration.defaults().withTriggers(
                GestureAction.Smudge,
                setOf(Gesture.FingerDrag, Gesture.ThreeFingerTap),
            )
        assertEquals(setOf(Gesture.FingerDrag), configuration.triggersOf(GestureAction.Smudge))
    }

    @Test
    fun jsonRoundTripKeepsEverything() {
        val configuration =
            GestureConfiguration
                .defaults()
                .withTriggers(GestureAction.Smudge, setOf(Gesture.StylusButton, Gesture.FingerDrag))
                .withTriggers(GestureAction.Eyedropper, emptySet())
                .copy(
                    longPressMillis = 650,
                    repeatDelayMillis = 900,
                    rotation = false,
                    stylusDisablesFingerStrokes = true,
                    shortcutBrush = GestureConfiguration.ShortcutBrush("brush-id", "Ink"),
                )
        val stored = Json.parseToJsonElement(configuration.toJson().toString()) as JsonObject
        assertEquals(configuration, GestureConfiguration.fromJson(stored))
    }

    @Test
    fun storedJsonIsReadForgivingly() {
        val stored =
            Json.parseToJsonElement(
                """{"triggers":{"undo":["oneFingerTap","sixFingerTap"],"smudge":["twoFingerTap"]},"longPressDelay":5}""",
            ) as JsonObject
        val configuration = GestureConfiguration.fromJson(stored)
        assertEquals(setOf(Gesture.OneFingerTap), configuration.triggersOf(GestureAction.Undo))
        assertEquals(emptySet<Gesture>(), configuration.triggersOf(GestureAction.Smudge))
        assertEquals(setOf(Gesture.ThreeFingerTap), configuration.triggersOf(GestureAction.Redo))
        assertEquals(GestureConfiguration.LONG_PRESS_DELAYS.first, configuration.longPressMillis)
        assertTrue(configuration.rotation)
    }
}
