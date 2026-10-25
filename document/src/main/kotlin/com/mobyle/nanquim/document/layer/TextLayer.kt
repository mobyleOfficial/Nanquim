package com.mobyle.nanquim.document.layer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class TextLayerContent(
    val model: JsonObject,
) {
    val plainText: String
        get() =
            (model["content"] as? JsonArray)
                ?.joinToString("") { run -> ((run as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty() }
                .orEmpty()

    val fontIds: Set<String>
        get() =
            (model["styles"] as? JsonObject)
                ?.values
                ?.mapNotNull { style -> ((style as? JsonObject)?.get("fontId") as? JsonPrimitive)?.contentOrNull }
                ?.toSet()
                .orEmpty()
}

fun DocumentState.addTextLayer(content: TextLayerContent): Pair<DocumentState, Int> {
    val name =
        content.plainText
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .trim()
            .take(MAXIMUM_TEXT_LAYER_NAME)
            .ifEmpty { "Text" }
    val (state, id) = addRasterLayer(name)
    return state.replaceRaster(state.raster(id)!!.copy(text = content)) to id
}

fun DocumentState.setTextContent(
    layerId: Int,
    content: TextLayerContent,
): DocumentState {
    val layer = raster(layerId)?.takeIf { it.text != null } ?: throw LayerOperationException("Layer $layerId is not a text layer")
    return replaceRaster(layer.copy(text = content))
}

class ContentLayerPixelsException(
    val layerId: Int,
) : LayerOperationException("Layer $layerId shows text or video: rasterize it first")

const val MAXIMUM_TEXT_LAYER_NAME = 40

fun DocumentState.rasterizeText(layerId: Int): DocumentState {
    val layer = raster(layerId) ?: throw LayerOperationException("Layer $layerId is not a raster layer")
    if (layer.text == null) throw LayerOperationException("Layer $layerId is not a text layer")
    return replaceRaster(layer.copy(text = null))
}

fun DocumentState.textModels(): Map<Int, JsonObject> =
    allNodes().filterIsInstance<RasterLayer>().mapNotNull { layer -> layer.text?.let { layer.id to it.model } }.toMap()

fun DocumentState.withTextModels(models: Map<Int, JsonObject>): DocumentState =
    models.entries.fold(this) { state, (id, model) ->
        state.raster(id)?.let { state.replaceRaster(it.copy(text = TextLayerContent(model))) } ?: state
    }

internal fun DocumentState.replaceRaster(layer: RasterLayer): DocumentState {
    fun List<LayerNode>.replaced(): List<LayerNode> =
        map { node ->
            when {
                node.id == layer.id -> layer
                node is LayerGroup -> node.copy(children = node.children.replaced())
                else -> node
            }
        }
    return copy(layers = layers.replaced())
}
