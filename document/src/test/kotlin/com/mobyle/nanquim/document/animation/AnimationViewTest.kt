package com.mobyle.nanquim.document.animation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.addRasterLayer
import com.mobyle.nanquim.document.layer.groupLayers
import com.mobyle.nanquim.document.layer.toEngineLayerTree

class AnimationViewTest {
    private fun document(frames: Int): DocumentState {
        var state = DocumentState(640, 480)
        repeat(frames) { state = state.addRasterLayer("Frame ${it + 1}").first }
        return state
    }

    @Test
    fun animationOffShowsTheTreeAsItIs() {
        assertNull(document(3).animationAppearance(null))
    }

    @Test
    fun onionFramesFadeWithDistanceAndOthersHide() {
        val state = document(6).let { it.withAnimation(it.animation.copy(enabled = true, firstFrameIsBackground = true)) }
        val ids = state.layers.map { it.id }
        val appearance = state.animationAppearance(ids[3])!!
        assertFalse(appearance.getValue(ids[0]).hidden)
        assertEquals(FrameAppearance(hidden = false), appearance.getValue(ids[3]))
        val skin = state.animation.onionSkin
        val previous = appearance.getValue(ids[2])
        assertEquals(0.5f * (1f - 1f / 3f), previous.opacityFactor, 1e-6f)
        assertEquals(skin.colorPrevious, previous.tint)
        assertEquals(0.5f * (1f - 2f / 3f), appearance.getValue(ids[1]).opacityFactor, 1e-6f)
        assertEquals(skin.colorNext, appearance.getValue(ids[4]).tint)
        assertFalse(appearance.getValue(ids[5]).hidden)
        val playing = state.animationAppearance(ids[3], onion = false)!!
        assertTrue(playing.getValue(ids[2]).hidden)
        assertTrue(playing.getValue(ids[4]).hidden)
    }

    @Test
    fun normalOnionBlendHasNoTint() {
        val state = document(3).let { it.withAnimation(it.animation.copy(enabled = true, onionSkin = OnionSkin(blend = OnionBlend.Normal))) }
        val appearance = state.animationAppearance(state.layers[1].id)!!
        assertNull(appearance.getValue(state.layers[0].id).tint)
        assertFalse(appearance.getValue(state.layers[0].id).hidden)
    }

    @Test
    fun engineTreeCarriesHiddenOpacityAndTintToRasterDescendants() {
        var state = document(4)
        val ids = state.layers.map { it.id }
        state = state.groupLayers(listOf(ids[2], ids[3]), "Frame 3").first
        state = state.withAnimation(state.animation.copy(enabled = true, onionSkin = OnionSkin(frames = 1)))
        val group = state.layers[2]
        val tree = state.toEngineLayerTree(frames = state.animationAppearance(ids[1]))
        val layers = tree["layers"] as JsonArray
        fun node(index: Int) = layers[index] as JsonObject
        assertEquals(0.25f, (node(0)["opacity"] as JsonPrimitive).float, 1e-6f)
        assertEquals(4, (node(0)["tint"] as JsonArray).size)
        assertFalse((node(1)["hidden"] as JsonPrimitive).boolean)
        assertEquals(null, node(1)["tint"])
        assertEquals(group.id, (node(2)["id"] as JsonPrimitive).content.toInt())
        assertEquals(null, node(2)["tint"])
        val children = node(2)["children"] as JsonArray
        assertTrue(children.all { (it as JsonObject)["tint"] is JsonArray })
        assertTrue(state.toEngineLayerTree().toString().contains("tint").not())
    }
}
