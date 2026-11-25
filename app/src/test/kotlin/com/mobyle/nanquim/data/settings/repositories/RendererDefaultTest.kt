package com.mobyle.nanquim.data.settings.repositories

import android.content.SharedPreferences
import com.mobyle.nanquim.domain.settings.models.Renderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RendererDefaultTest {
    @Test
    fun nothingSavedUsesTheDefault() {
        assertEquals(Renderer.Vulkan, AppSettingsRepositoryImpl(MemoryPreferences(), Renderer.Vulkan).renderer)
        assertEquals(Renderer.Gles, AppSettingsRepositoryImpl(MemoryPreferences(), Renderer.Gles).renderer)
    }

    @Test
    fun savedGlesMovesToVulkanOnceWhereVulkanIsTheDefault() {
        val preferences = MemoryPreferences(mutableMapOf("renderer" to Renderer.Gles.id))
        assertEquals(Renderer.Vulkan, AppSettingsRepositoryImpl(preferences, Renderer.Vulkan).renderer)

        AppSettingsRepositoryImpl(preferences, Renderer.Vulkan).changeRenderer(Renderer.Gles)
        assertEquals(Renderer.Gles, AppSettingsRepositoryImpl(preferences, Renderer.Vulkan).renderer)
    }

    @Test
    fun glesChosenAfterTheUpdateSticks() {
        val preferences = MemoryPreferences()
        AppSettingsRepositoryImpl(preferences, Renderer.Vulkan).changeRenderer(Renderer.Gles)
        assertEquals(Renderer.Gles, AppSettingsRepositoryImpl(preferences, Renderer.Vulkan).renderer)
    }

    @Test
    fun savedChoicesStayWhereGlesIsTheDefault() {
        for (saved in listOf(Renderer.Gles, Renderer.Vulkan)) {
            val preferences = MemoryPreferences(mutableMapOf("renderer" to saved.id))
            assertEquals(saved, AppSettingsRepositoryImpl(preferences, Renderer.Gles).renderer)
        }
    }
}

private class MemoryPreferences(
    private val values: MutableMap<String, Any?> = mutableMapOf(),
) : SharedPreferences {
    override fun getAll(): Map<String, *> = values.toMap()

    override fun getString(
        key: String,
        defValue: String?,
    ): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(
        key: String,
        defValues: Set<String>?,
    ): Set<String>? = values[key] as? Set<String> ?: defValues

    override fun getInt(
        key: String,
        defValue: Int,
    ): Int = values[key] as? Int ?: defValue

    override fun getLong(
        key: String,
        defValue: Long,
    ): Long = values[key] as? Long ?: defValue

    override fun getFloat(
        key: String,
        defValue: Float,
    ): Float = values[key] as? Float ?: defValue

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = key in values

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(
            key: String,
            value: String?,
        ) = put(key, value)

        override fun putStringSet(
            key: String,
            values: Set<String>?,
        ) = put(key, values)

        override fun putInt(
            key: String,
            value: Int,
        ) = put(key, value)

        override fun putLong(
            key: String,
            value: Long,
        ) = put(key, value)

        override fun putFloat(
            key: String,
            value: Float,
        ) = put(key, value)

        override fun putBoolean(
            key: String,
            value: Boolean,
        ) = put(key, value)

        override fun remove(key: String) = put(key, null)

        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        private fun put(
            key: String,
            value: Any?,
        ): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clear) values.clear()
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }
}
