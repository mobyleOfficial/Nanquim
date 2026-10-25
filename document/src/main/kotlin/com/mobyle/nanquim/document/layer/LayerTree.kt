package com.mobyle.nanquim.document.layer

import com.mobyle.nanquim.document.animation.AnimationSettings

data class RgbaColor(
    val red: Float,
    val green: Float,
    val blue: Float,
    val alpha: Float = 1f,
) {
    companion object {
        val WHITE = RgbaColor(1f, 1f, 1f, 1f)
        val BLACK = RgbaColor(0f, 0f, 0f, 1f)
    }
}

data class Background(
    val color: RgbaColor = RgbaColor.WHITE,
    val hidden: Boolean = false,
)

data class LayerMask(
    val id: Int,
    val enabled: Boolean = true,
    val inverted: Boolean = false,
)

sealed interface LayerNode {
    val id: Int
    val name: String
    val opacity: Float
    val blend: BlendMode
    val hidden: Boolean
    val locked: Boolean
}

data class RasterLayer(
    override val id: Int,
    override val name: String,
    override val opacity: Float = 1f,
    override val blend: BlendMode = BlendMode.Normal,
    override val hidden: Boolean = false,
    override val locked: Boolean = false,
    val alphaLock: Boolean = false,
    val clipping: Boolean = false,
    val mask: LayerMask? = null,
    val isReference: Boolean = false,
    val assisted: Boolean = false,
    val text: TextLayerContent? = null,
    val video: VideoLayerContent? = null,
    val isPrivate: Boolean = false,
) : LayerNode {
    init {
        require(blend != BlendMode.PassThrough) { "A raster layer cannot use pass-through" }
    }
}

data class LayerGroup(
    override val id: Int,
    override val name: String,
    override val opacity: Float = 1f,
    override val blend: BlendMode = BlendMode.PassThrough,
    override val hidden: Boolean = false,
    override val locked: Boolean = false,
    val collapsed: Boolean = false,
    val children: List<LayerNode> = emptyList(),
) : LayerNode

data class SavedSelection(
    val id: Int,
    val name: String,
)

data class LayerPosition(
    val parentId: Int?,
    val index: Int,
)

data class DocumentState(
    val widthPx: Int,
    val heightPx: Int,
    val background: Background = Background(),
    val layers: List<LayerNode> = emptyList(),
    val activeLayerId: Int? = null,
    val nextId: Int = 1,
    val selectionId: Int? = null,
    val savedSelections: List<SavedSelection> = emptyList(),
    val pixelFormat: PixelFormat = PixelFormat.Rgba8,
    val animation: AnimationSettings = AnimationSettings(),
) {
    init {
        require(widthPx > 0 && heightPx > 0) { "Canvas size must be positive" }
    }

    fun allNodes(): Sequence<LayerNode> =
        sequence {
            suspend fun SequenceScope<LayerNode>.visit(nodes: List<LayerNode>) {
                for (node in nodes) {
                    yield(node)
                    if (node is LayerGroup) visit(node.children)
                }
            }
            visit(layers)
        }

    fun find(id: Int): LayerNode? = allNodes().firstOrNull { it.id == id }

    fun raster(id: Int): RasterLayer? = find(id) as? RasterLayer

    fun group(id: Int): LayerGroup? = find(id) as? LayerGroup

    val activeLayer: LayerNode? get() = activeLayerId?.let(::find)

    fun positionOf(id: Int): LayerPosition? {
        fun search(
            nodes: List<LayerNode>,
            parentId: Int?,
        ): LayerPosition? {
            nodes.forEachIndexed { index, node ->
                if (node.id == id) return LayerPosition(parentId, index)
                if (node is LayerGroup) search(node.children, node.id)?.let { return it }
            }
            return null
        }
        return search(layers, null)
    }

    fun childrenOf(parentId: Int?): List<LayerNode> = if (parentId == null) layers else group(parentId)?.children.orEmpty()

    fun ancestorsOf(id: Int): List<LayerGroup> {
        val ancestors = mutableListOf<LayerGroup>()
        var parentId = positionOf(id)?.parentId
        while (parentId != null) {
            val parent = group(parentId) ?: break
            ancestors += parent
            parentId = positionOf(parentId)?.parentId
        }
        return ancestors
    }

    fun isEffectivelyHidden(id: Int): Boolean = find(id)?.hidden == true || ancestorsOf(id).any { it.hidden }

    fun isEffectivelyLocked(id: Int): Boolean = find(id)?.locked == true || ancestorsOf(id).any { it.locked }

    fun rasterLayers(): List<RasterLayer> = allNodes().filterIsInstance<RasterLayer>().toList()

    fun pixelSurfaceIds(): Set<Int> =
        rasterLayers().flatMapTo(mutableSetOf()) { layer -> listOfNotNull(layer.id, layer.mask?.id) } + selectionSurfaceIds()

    fun selectionSurfaceIds(): Set<Int> = setOfNotNull(selectionId) + savedSelections.map { it.id }

    val referenceLayerId: Int? get() = rasterLayers().firstOrNull { it.isReference }?.id

    val layerCount: Int get() = rasterLayers().size
}
