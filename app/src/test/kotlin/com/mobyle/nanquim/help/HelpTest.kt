package com.mobyle.nanquim.help

import android.content.SharedPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.mobyle.nanquim.canvas.input.CanvasTool

class HelpTest {
    private val preferences = MemoryPreferences()

    @Test
    fun theWelcomeCardsShowOnceUntilReplayed() {
        val help = Help(preferences)
        help.showWelcomeIfFirstLaunch()
        assertTrue(help.welcomeVisible)
        help.welcomeCard = 3
        help.finishWelcome()
        assertFalse(help.welcomeVisible)

        val nextLaunch = Help(preferences)
        nextLaunch.showWelcomeIfFirstLaunch()
        assertFalse(nextLaunch.welcomeVisible)
        nextLaunch.showWelcome()
        assertTrue(nextLaunch.welcomeVisible)
        assertEquals(0, nextLaunch.welcomeCard)
    }

    @Test
    fun aToolTipShowsTheFirstTimeOnly() {
        val help = Help(preferences)
        help.toolSelected(CanvasTool.Brush)
        assertNull(help.tip)
        help.toolSelected(CanvasTool.Eraser)
        assertEquals(CanvasTool.Eraser, help.tip)
        help.toolSelected(CanvasTool.Brush)
        assertNull(help.tip)
        help.toolSelected(CanvasTool.Eraser)
        assertNull(help.tip)
        assertTrue(Help(preferences).tipSeen(CanvasTool.Eraser))

        help.resetTips()
        help.toolSelected(CanvasTool.Eraser)
        assertEquals(CanvasTool.Eraser, help.tip)
    }

    @Test
    fun automatedSessionsShowNothingOnTheirOwn() {
        val help = Help(preferences).apply { automatic = false }
        help.showWelcomeIfFirstLaunch()
        help.toolSelected(CanvasTool.Smudge)
        assertFalse(help.welcomeVisible)
        assertNull(help.tip)
        assertFalse(help.tipSeen(CanvasTool.Smudge))
        help.showTip(CanvasTool.Smudge)
        assertEquals(CanvasTool.Smudge, help.tip)
    }

    private class MemoryPreferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values

        override fun getString(
            key: String,
            defValue: String?,
        ) = values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?,
        ) = values[key] as? MutableSet<String> ?: defValues

        override fun getInt(
            key: String,
            defValue: Int,
        ) = values[key] as? Int ?: defValue

        override fun getLong(
            key: String,
            defValue: Long,
        ) = values[key] as? Long ?: defValue

        override fun getFloat(
            key: String,
            defValue: Float,
        ) = values[key] as? Float ?: defValue

        override fun getBoolean(
            key: String,
            defValue: Boolean,
        ) = values[key] as? Boolean ?: defValue

        override fun contains(key: String) = key in values

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()

            override fun putString(
                key: String,
                value: String?,
            ) = apply { changes[key] = value }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ) = apply { changes[key] = values?.toMutableSet() }

            override fun putInt(
                key: String,
                value: Int,
            ) = apply { changes[key] = value }

            override fun putLong(
                key: String,
                value: Long,
            ) = apply { changes[key] = value }

            override fun putFloat(
                key: String,
                value: Float,
            ) = apply { changes[key] = value }

            override fun putBoolean(
                key: String,
                value: Boolean,
            ) = apply { changes[key] = value }

            override fun remove(key: String) = apply { removed += key }

            override fun clear() = apply { removed += values.keys }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                removed.forEach(values::remove)
                values.putAll(changes)
            }
        }
    }
}
