package com.mobyle.nanquim.document.layer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrivateLayerTest {
    @Test
    fun exportsLeaveOutPrivateLayersAndTheEditorShowsThem() {
        var state = DocumentState(640, 480)
        val (withPaint, paint) = state.addRasterLayer("Paint")
        val (withSecret, secret) = withPaint.addRasterLayer("Secret")
        state = withSecret.setPrivate(secret, true)
        assertEquals(listOf(paint), state.withoutPrivateLayers().rasterLayers().map { it.id })
        fun hidden(tree: JsonObject, id: Int) =
            (tree["layers"] as JsonArray).map { it as JsonObject }.first { (it["id"] as JsonPrimitive).content.toInt() == id }["hidden"]!!.let { (it as JsonPrimitive).boolean }
        assertFalse(hidden(state.toEngineLayerTree(), secret))
        assertTrue(hidden(state.toEngineLayerTree(hidePrivate = true), secret))
        assertFalse(hidden(state.toEngineLayerTree(hidePrivate = true), paint))
        assertEquals(withPaint, withPaint.withoutPrivateLayers())
    }
}
