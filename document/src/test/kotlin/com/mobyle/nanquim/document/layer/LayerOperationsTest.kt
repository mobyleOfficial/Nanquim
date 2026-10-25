package com.mobyle.nanquim.document.layer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LayerOperationsTest {
    private fun ids(nodes: List<LayerNode>): List<Int> = nodes.map { it.id }

    private fun threeLayers(): DocumentState {
        var state = DocumentState(100, 100)
        repeat(3) { index -> state = state.addRasterLayer("Layer ${index + 1}").first }
        return state
    }

    @Test
    fun newLayersGoAboveTheActiveOneAndBecomeActive() {
        var state = threeLayers()
        assertEquals(listOf(1, 2, 3), ids(state.layers))
        state = state.withActiveLayer(1)
        val (added, id) = state.addRasterLayer("New")
        assertEquals(4, id)
        assertEquals(listOf(1, 4, 2, 3), ids(added.layers))
        assertEquals(4, added.activeLayerId)
    }

    @Test
    fun deleteSelectsTheLayerBelow() {
        val state = threeLayers().withActiveLayer(2).delete(listOf(2))
        assertEquals(listOf(1, 3), ids(state.layers))
        assertEquals(1, state.activeLayerId)
        val bottomDeleted = threeLayers().withActiveLayer(1).delete(listOf(1))
        assertEquals(2, bottomDeleted.activeLayerId)
        val allDeleted = threeLayers().delete(listOf(1, 2, 3))
        assertTrue(allDeleted.layers.isEmpty())
        assertNull(allDeleted.activeLayerId)
    }

    @Test
    fun groupKeepsOrderAndSitsWhereTheTopmostWas() {
        val (state, groupId) = threeLayers().groupLayers(listOf(3, 1), "Group")
        assertEquals(listOf(2, groupId), ids(state.layers))
        val group = state.group(groupId)!!
        assertEquals(listOf(1, 3), ids(group.children))
        assertEquals(BlendMode.PassThrough, group.blend)
        assertEquals(groupId, state.activeLayerId)
        assertEquals(LayerPosition(groupId, 1), state.positionOf(3))
        assertEquals(listOf(group), state.ancestorsOf(1))

        val ungrouped = state.ungroup(groupId)
        assertEquals(listOf(2, 1, 3), ids(ungrouped.layers))
    }

    @Test
    fun moveIntoAndOutOfGroups() {
        val (grouped, groupId) = threeLayers().groupLayers(listOf(2), "Group")
        val moved = grouped.move(3, groupId, 0)
        assertEquals(listOf(1, groupId), ids(moved.layers))
        assertEquals(listOf(3, 2), ids(moved.group(groupId)!!.children))
        val out = moved.move(2, null, 0)
        assertEquals(listOf(2, 1, groupId), ids(out.layers))
        assertThrows<LayerOperationException> { out.move(groupId, groupId, 0) }
        assertThrows<LayerOperationException> { out.move(1, null, 5) }
    }

    @Test
    fun duplicateCopiesGroupsWithNewIdsAndMasks() {
        val (masked, maskId) = threeLayers().addMask(2)
        val (grouped, groupId) = masked.setReference(2, true).groupLayers(listOf(1, 2), "Group")
        val result = grouped.duplicate(groupId) { "$it copy" }
        val copy = result.state.group(result.newNodeId)!!
        assertEquals("Group copy", copy.name)
        assertEquals(listOf(groupId, result.newNodeId, 3), ids(result.state.layers))
        val copiedMasked = copy.children[1] as RasterLayer
        assertEquals(result.surfaceCopies[maskId], copiedMasked.mask!!.id)
        assertEquals(setOf(1, 2, maskId), result.surfaceCopies.keys)
        assertFalse(copiedMasked.isReference)
        val allIds = result.state.allNodes().map { it.id }.toList()
        assertEquals(allIds.size, allIds.toSet().size)
    }

    @Test
    fun mergeDownKeepsTheLowerLayerWithNormalBlend() {
        val state =
            threeLayers()
                .setBlend(2, BlendMode.Multiply)
                .setOpacity(2, 0.5f)
                .addMask(3)
                .first
        val result = state.mergeDown(3)
        assertEquals(listOf(1, 2), ids(result.state.layers))
        val merged = result.state.raster(2)!!
        assertEquals(BlendMode.Normal, merged.blend)
        assertEquals(1f, merged.opacity)
        assertEquals(2, result.targetLayerId)
        assertTrue(3 in result.removedSurfaceIds)
        assertEquals(2, result.removedSurfaceIds.size)
        assertThrows<LayerOperationException> { result.state.mergeDown(1) }
    }

    @Test
    fun flattenReplacesGroupsAndDocument() {
        val (grouped, groupId) = threeLayers().groupLayers(listOf(1, 2), "Sketch")
        val flattened = grouped.flattenGroup(groupId)
        val layer = flattened.state.raster(flattened.targetLayerId)!!
        assertEquals("Sketch", layer.name)
        assertEquals(listOf(flattened.targetLayerId, 3), ids(flattened.state.layers))
        assertEquals(setOf(1, 2), flattened.removedSurfaceIds)

        val document = grouped.flattenDocument("Flattened")
        assertEquals(1, document.state.layers.size)
        assertEquals(setOf(1, 2, 3), document.removedSurfaceIds)
    }

    @Test
    fun propertiesAreValidated() {
        val state = threeLayers()
        assertThrows<LayerOperationException> { state.setBlend(1, BlendMode.PassThrough) }
        assertThrows<LayerOperationException> { state.rename(1, "   ") }
        assertEquals(1f, state.setOpacity(1, 3f).raster(1)!!.opacity)
        val referenced = state.setReference(1, true).setReference(3, true)
        assertEquals(3, referenced.referenceLayerId)
        assertEquals(1, referenced.rasterLayers().count { it.isReference })
        val hidden = state.groupLayers(listOf(1), "G").let { (grouped, groupId) -> grouped.setHidden(groupId, true) }
        assertTrue(hidden.isEffectivelyHidden(1))
        assertFalse(hidden.isEffectivelyHidden(3))
    }

    @Test
    fun layerLimitFollowsTheBudgetFormula() {
        assertEquals(19, maximumLayers(1L shl 30, 4096, 4096))
        assertEquals(MAXIMUM_LAYER_LIMIT, maximumLayers(1L shl 40, 64, 64))
    }

    @Test
    fun engineTreeListsCompositingFields() {
        val (state, maskId) = threeLayers().addMask(1)
        val (grouped, groupId) = state.groupLayers(listOf(1, 2), "G")
        val json = grouped.setBlend(groupId, BlendMode.Screen).toEngineLayerTree(soloLayerId = 3).toString()
        assertTrue(json.contains("\"blend\":\"screen\""))
        assertTrue(json.contains("\"mask\":{\"id\":$maskId,\"enabled\":true,\"inverted\":false}"))
        assertTrue(json.contains("\"solo\":3"))
        assertTrue(json.contains("\"type\":\"group\""))
    }
}
