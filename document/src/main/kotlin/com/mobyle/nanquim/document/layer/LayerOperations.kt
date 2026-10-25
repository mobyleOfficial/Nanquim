package com.mobyle.nanquim.document.layer

open class LayerOperationException(
    message: String,
) : IllegalArgumentException(message)

private fun fail(message: String): Nothing = throw LayerOperationException(message)

private fun List<LayerNode>.mapTree(transform: (LayerNode) -> LayerNode): List<LayerNode> =
    map { node ->
        val mapped = if (node is LayerGroup) node.copy(children = node.children.mapTree(transform)) else node
        transform(mapped)
    }

private fun DocumentState.updateNode(
    id: Int,
    transform: (LayerNode) -> LayerNode,
): DocumentState {
    if (find(id) == null) fail("No layer $id")
    return copy(layers = layers.mapTree { node -> if (node.id == id) transform(node) else node })
}

private fun DocumentState.updateRaster(
    id: Int,
    transform: (RasterLayer) -> RasterLayer,
): DocumentState {
    if (raster(id) == null) fail("Layer $id is not a raster layer")
    return updateNode(id) { transform(it as RasterLayer) }
}

private fun List<LayerNode>.without(id: Int): List<LayerNode> =
    filter { it.id != id }.map { node -> if (node is LayerGroup) node.copy(children = node.children.without(id)) else node }

private fun List<LayerNode>.inserting(
    node: LayerNode,
    parentId: Int?,
    index: Int,
): List<LayerNode> {
    if (parentId == null) {
        return toMutableList().apply { add(index.coerceIn(0, size), node) }
    }
    return map { child ->
        when {
            child !is LayerGroup -> child
            child.id == parentId -> child.copy(children = child.children.toMutableList().apply { add(index.coerceIn(0, size), node) })
            else -> child.copy(children = child.children.inserting(node, parentId, index))
        }
    }
}

private fun LayerNode.containsId(id: Int): Boolean =
    this.id == id || (this is LayerGroup && children.any { it.containsId(id) })

fun DocumentState.withActiveLayer(id: Int?): DocumentState {
    if (id != null && find(id) == null) fail("No layer $id")
    return copy(activeLayerId = id)
}

private fun DocumentState.insertionPointAboveActive(): LayerPosition {
    val active = activeLayerId?.let(::positionOf) ?: return LayerPosition(null, layers.size)
    return LayerPosition(active.parentId, active.index + 1)
}

fun DocumentState.addRasterLayer(name: String): Pair<DocumentState, Int> {
    val id = nextId
    val position = insertionPointAboveActive()
    val state =
        copy(
            layers = layers.inserting(RasterLayer(id, name), position.parentId, position.index),
            activeLayerId = id,
            nextId = id + 1,
        )
    return state to id
}

data class DuplicateResult(
    val state: DocumentState,
    val newNodeId: Int,
    val surfaceCopies: Map<Int, Int>,
)

fun DocumentState.duplicate(
    id: Int,
    copyName: (String) -> String,
): DuplicateResult {
    val original = find(id) ?: fail("No layer $id")
    val position = positionOf(id)!!
    var next = nextId
    val surfaceCopies = mutableMapOf<Int, Int>()

    fun copyOf(node: LayerNode): LayerNode {
        val newId = next++
        return when (node) {
            is RasterLayer -> {
                surfaceCopies[node.id] = newId
                val mask =
                    node.mask?.let { mask ->
                        val maskId = next++
                        surfaceCopies[mask.id] = maskId
                        mask.copy(id = maskId)
                    }
                node.copy(id = newId, mask = mask, isReference = false)
            }
            is LayerGroup -> node.copy(id = newId, children = node.children.map(::copyOf))
        }
    }
    val copy =
        when (val duplicated = copyOf(original)) {
            is RasterLayer -> duplicated.copy(name = copyName(original.name))
            is LayerGroup -> duplicated.copy(name = copyName(original.name))
        }
    val state =
        copy(
            layers = layers.inserting(copy, position.parentId, position.index + 1),
            activeLayerId = copy.id,
            nextId = next,
        )
    return DuplicateResult(state, copy.id, surfaceCopies)
}

fun DocumentState.withoutPrivateLayers(): DocumentState {
    val privateIds = rasterLayers().filter { it.isPrivate }.map { it.id }
    return if (privateIds.isEmpty()) this else delete(privateIds)
}

fun DocumentState.delete(ids: Collection<Int>): DocumentState {
    if (ids.isEmpty()) fail("Nothing to delete")
    ids.forEach { if (find(it) == null) fail("No layer $it") }
    val roots = ids.filter { id -> ancestorsOf(id).none { it.id in ids } }
    val anchor = positionOf(roots.maxBy { positionOf(it)!!.index })!!
    var remaining = layers
    roots.forEach { remaining = remaining.without(it) }
    val state = copy(layers = remaining)
    val siblings = state.childrenOf(anchor.parentId)
    val removedBelow = roots.count { root -> positionOf(root)!!.let { it.parentId == anchor.parentId && it.index < anchor.index } }
    val belowIndex = anchor.index - removedBelow - 1
    val active =
        when {
            activeLayerId != null && state.find(activeLayerId) != null -> activeLayerId
            belowIndex in siblings.indices -> siblings[belowIndex].id
            siblings.isNotEmpty() -> siblings.first().id
            else -> anchor.parentId ?: state.layers.lastOrNull()?.id
        }
    return state.copy(activeLayerId = active)
}

fun DocumentState.rename(
    id: Int,
    name: String,
): DocumentState {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) fail("A layer name cannot be empty")
    if (trimmed.length > MAXIMUM_NAME_LENGTH) fail("A layer name has at most $MAXIMUM_NAME_LENGTH characters")
    return updateNode(id) { node ->
        when (node) {
            is RasterLayer -> node.copy(name = trimmed)
            is LayerGroup -> node.copy(name = trimmed)
        }
    }
}

const val MAXIMUM_NAME_LENGTH = 128

fun DocumentState.setOpacity(
    id: Int,
    opacity: Float,
): DocumentState {
    if (!opacity.isFinite()) fail("Opacity must be a number")
    val clamped = opacity.coerceIn(0f, 1f)
    return updateNode(id) { node ->
        when (node) {
            is RasterLayer -> node.copy(opacity = clamped)
            is LayerGroup -> node.copy(opacity = clamped)
        }
    }
}

fun DocumentState.setBlend(
    id: Int,
    blend: BlendMode,
): DocumentState =
    updateNode(id) { node ->
        when (node) {
            is RasterLayer -> {
                if (blend == BlendMode.PassThrough) fail("Pass-through is only for groups")
                node.copy(blend = blend)
            }
            is LayerGroup -> node.copy(blend = blend)
        }
    }

fun DocumentState.setHidden(
    id: Int,
    hidden: Boolean,
): DocumentState =
    updateNode(id) { node ->
        when (node) {
            is RasterLayer -> node.copy(hidden = hidden)
            is LayerGroup -> node.copy(hidden = hidden)
        }
    }

fun DocumentState.setLocked(
    id: Int,
    locked: Boolean,
): DocumentState =
    updateNode(id) { node ->
        when (node) {
            is RasterLayer -> node.copy(locked = locked)
            is LayerGroup -> node.copy(locked = locked)
        }
    }

fun DocumentState.setCollapsed(
    groupId: Int,
    collapsed: Boolean,
): DocumentState {
    if (group(groupId) == null) fail("Layer $groupId is not a group")
    return updateNode(groupId) { (it as LayerGroup).copy(collapsed = collapsed) }
}

fun DocumentState.setAlphaLock(
    id: Int,
    alphaLock: Boolean,
): DocumentState = updateRaster(id) { it.copy(alphaLock = alphaLock) }

fun DocumentState.setPrivate(
    id: Int,
    isPrivate: Boolean,
): DocumentState = updateRaster(id) { it.copy(isPrivate = isPrivate) }

fun DocumentState.setAssisted(
    id: Int,
    assisted: Boolean,
): DocumentState = updateRaster(id) { it.copy(assisted = assisted) }

fun DocumentState.setClipping(
    id: Int,
    clipping: Boolean,
): DocumentState = updateRaster(id) { it.copy(clipping = clipping) }

fun DocumentState.setReference(
    id: Int,
    reference: Boolean,
): DocumentState {
    if (raster(id) == null) fail("Layer $id is not a raster layer")
    return copy(
        layers =
            layers.mapTree { node ->
                when {
                    node !is RasterLayer -> node
                    node.id == id -> node.copy(isReference = reference)
                    reference -> node.copy(isReference = false)
                    else -> node
                }
            },
    )
}

fun DocumentState.setBackground(background: Background): DocumentState = copy(background = background)

fun DocumentState.addMask(layerId: Int): Pair<DocumentState, Int> {
    val layer = raster(layerId) ?: fail("Layer $layerId is not a raster layer")
    if (layer.mask != null) fail("Layer $layerId already has a mask")
    val maskId = nextId
    return updateRaster(layerId) { it.copy(mask = LayerMask(maskId)) }.copy(nextId = maskId + 1) to maskId
}

fun DocumentState.removeMask(layerId: Int): Pair<DocumentState, Int> {
    val mask = raster(layerId)?.mask ?: fail("Layer $layerId has no mask")
    return updateRaster(layerId) { it.copy(mask = null) } to mask.id
}

fun DocumentState.setMaskEnabled(
    layerId: Int,
    enabled: Boolean,
): DocumentState {
    val mask = raster(layerId)?.mask ?: fail("Layer $layerId has no mask")
    return updateRaster(layerId) { it.copy(mask = mask.copy(enabled = enabled)) }
}

fun DocumentState.setMaskInverted(
    layerId: Int,
    inverted: Boolean,
): DocumentState {
    val mask = raster(layerId)?.mask ?: fail("Layer $layerId has no mask")
    return updateRaster(layerId) { it.copy(mask = mask.copy(inverted = inverted)) }
}

fun DocumentState.move(
    id: Int,
    parentId: Int?,
    index: Int,
): DocumentState {
    val node = find(id) ?: fail("No layer $id")
    if (parentId != null) {
        if (group(parentId) == null) fail("Layer $parentId is not a group")
        if (node.containsId(parentId)) fail("A group cannot move into itself")
    }
    val without = layers.without(id)
    val siblings = if (parentId == null) without else copy(layers = without).childrenOf(parentId)
    if (index !in 0..siblings.size) fail("Index $index is out of range")
    return copy(layers = without.inserting(node, parentId, index))
}

fun DocumentState.groupLayers(
    ids: Collection<Int>,
    name: String,
): Pair<DocumentState, Int> {
    if (ids.isEmpty()) fail("Nothing to group")
    ids.forEach { if (find(it) == null) fail("No layer $it") }
    val roots = ids.filter { id -> ancestorsOf(id).none { it.id in ids } }
    val order = allNodes().map { it.id }.toList()
    val ordered = roots.sortedBy { order.indexOf(it) }
    val topmost = ordered.last()
    val topPosition = positionOf(topmost)!!
    val movingBelow = ordered.count { other -> other != topmost && positionOf(other)!!.let { it.parentId == topPosition.parentId && it.index < topPosition.index } }
    var remaining = layers
    ordered.forEach { remaining = remaining.without(it) }
    val groupId = nextId
    val group = LayerGroup(groupId, name, children = ordered.map { find(it)!! })
    val insertAt = topPosition.index - movingBelow
    return copy(layers = remaining.inserting(group, topPosition.parentId, insertAt), activeLayerId = groupId, nextId = groupId + 1) to groupId
}

fun DocumentState.ungroup(groupId: Int): DocumentState {
    val group = group(groupId) ?: fail("Layer $groupId is not a group")
    val position = positionOf(groupId)!!
    var remaining = layers.without(groupId)
    group.children.forEachIndexed { offset, child -> remaining = remaining.inserting(child, position.parentId, position.index + offset) }
    val active = if (activeLayerId == groupId) group.children.lastOrNull()?.id ?: position.parentId else activeLayerId
    return copy(layers = remaining, activeLayerId = active)
}

fun DocumentState.nodeBelow(id: Int): LayerNode? {
    val position = positionOf(id) ?: return null
    return childrenOf(position.parentId).getOrNull(position.index - 1)
}

data class MergeResult(
    val state: DocumentState,
    val targetLayerId: Int,
    val removedSurfaceIds: Set<Int>,
)

fun DocumentState.mergeDown(id: Int): MergeResult {
    val upper = raster(id) ?: fail("Layer $id is not a raster layer")
    val lower = nodeBelow(id) as? RasterLayer ?: fail("There is no raster layer below layer $id")
    if (isEffectivelyLocked(lower.id)) fail("Layer ${lower.id} is locked")
    val removed = setOfNotNull(upper.id, upper.mask?.id, lower.mask?.id)
    val state =
        copy(layers = layers.without(upper.id), activeLayerId = lower.id)
            .updateRaster(lower.id) { it.copy(blend = BlendMode.Normal, opacity = 1f, mask = null, hidden = false, text = null) }
    return MergeResult(state, lower.id, removed)
}

fun DocumentState.combineDown(
    id: Int,
    groupName: String,
): Pair<DocumentState, Int> {
    val below = nodeBelow(id) ?: fail("There is no layer below layer $id")
    return groupLayers(listOf(below.id, id), groupName)
}

fun DocumentState.flattenGroup(groupId: Int): MergeResult {
    val group = group(groupId) ?: fail("Layer $groupId is not a group")
    val position = positionOf(groupId)!!
    val newId = nextId
    val removed = DocumentState(widthPx, heightPx, layers = group.children).pixelSurfaceIds()
    val blend = if (group.blend == BlendMode.PassThrough) BlendMode.Normal else group.blend
    val flattened = RasterLayer(newId, group.name, opacity = group.opacity, blend = blend, hidden = group.hidden)
    val state =
        copy(
            layers = layers.without(groupId).inserting(flattened, position.parentId, position.index),
            activeLayerId = newId,
            nextId = newId + 1,
        )
    return MergeResult(state, newId, removed)
}

fun DocumentState.mergeLayers(ids: Collection<Int>): MergeResult {
    val layers = rasterLayers().filter { it.id in ids }
    if (layers.size < 2) fail("Merging needs at least two raster layers")
    if (ids.any { raster(it) == null }) fail("Only raster layers can be merged")
    val target = layers.first()
    if (isEffectivelyLocked(target.id)) fail("Layer ${target.id} is locked")
    var remaining = this.layers
    layers.drop(1).forEach { remaining = remaining.without(it.id) }
    val removed = layers.flatMapTo(mutableSetOf()) { listOfNotNull(it.id.takeIf { id -> id != target.id }, it.mask?.id) }
    val state =
        copy(layers = remaining, activeLayerId = target.id)
            .updateRaster(target.id) { it.copy(blend = BlendMode.Normal, opacity = 1f, mask = null, hidden = false, text = null) }
    return MergeResult(state, target.id, removed)
}

fun DocumentState.flattenDocument(name: String): MergeResult {
    if (layers.isEmpty()) fail("The document has no layers")
    val newId = nextId
    val state = copy(layers = listOf(RasterLayer(newId, name)), activeLayerId = newId, nextId = newId + 1)
    return MergeResult(state, newId, pixelSurfaceIds())
}

fun maximumLayers(
    memoryBudgetBytes: Long,
    widthPx: Int,
    heightPx: Int,
    bytesPerPixel: Int = PixelFormat.Rgba8.bytesPerPixel,
): Int {
    val layerBytes = widthPx.toDouble() * heightPx * bytesPerPixel * 0.5
    if (layerBytes <= 0) return MAXIMUM_LAYER_LIMIT
    return (memoryBudgetBytes * 0.6 / layerBytes).toInt().coerceIn(1, MAXIMUM_LAYER_LIMIT)
}

const val MAXIMUM_LAYER_LIMIT = 999

fun DocumentState.subtreeIds(id: Int): Set<Int> {
    val node = find(id) ?: return emptySet()
    val ids = mutableSetOf<Int>()

    fun visit(current: LayerNode) {
        ids += current.id
        if (current is LayerGroup) current.children.forEach(::visit)
    }
    visit(node)
    return ids
}
