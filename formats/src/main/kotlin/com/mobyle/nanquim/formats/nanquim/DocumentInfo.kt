package com.mobyle.nanquim.formats.nanquim

import com.mobyle.nanquim.document.guide.GuideSettings
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.LayerNode
import com.mobyle.nanquim.document.layer.RasterLayer
import com.mobyle.nanquim.document.reference.ReferenceSettings
import java.time.Instant
import java.util.UUID

enum class CanvasUnit(
    val id: String,
) {
    Pixels("px"),
    Millimeters("mm"),
    Centimeters("cm"),
    Inches("in"),
    ;

    companion object {
        fun fromId(id: String): CanvasUnit? = entries.firstOrNull { it.id == id }
    }
}

data class DocumentInfo(
    val id: String,
    val name: String,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val dpi: Double = DEFAULT_DPI,
    val unit: CanvasUnit = CanvasUnit.Pixels,
    val strokeCount: Long = 0,
    val timeSpentSeconds: Long = 0,
    val guides: GuideSettings? = null,
    val reference: ReferenceSettings? = null,
    val colorProfile: DocumentColorProfile? = null,
    val cmykProfile: DocumentColorProfile? = null,
    val timelapse: TimelapseSettings? = null,
    val artist: ArtistInfo? = null,
) {
    companion object {
        const val DEFAULT_DPI = 132.0

        fun new(
            name: String,
            now: Instant,
            dpi: Double = DEFAULT_DPI,
            unit: CanvasUnit = CanvasUnit.Pixels,
        ) = DocumentInfo(UUID.randomUUID().toString(), name, now, now, dpi, unit)
    }
}

class DocumentColorProfile(
    val name: String,
    private val bytes: ByteArray,
) {
    val icc: ByteArray get() = bytes.copyOf()

    val size: Int get() = bytes.size

    override fun equals(other: Any?): Boolean = other is DocumentColorProfile && other.name == name && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()

    override fun toString(): String = "DocumentColorProfile($name, ${bytes.size} bytes)"
}

class LayerUuids private constructor(
    private val uuidById: Map<Int, String>,
) {
    fun uuidOf(id: Int): String? = uuidById[id]

    val ids: Set<Int> get() = uuidById.keys

    fun assignedFor(state: DocumentState): LayerUuids {
        val missing =
            (state.allNodes().flatMap { node -> sequenceOf(node.id) + maskIdOf(node) } + state.selectionSurfaceIds())
                .filter { it !in uuidById }
                .toList()
        if (missing.isEmpty()) return this
        return LayerUuids(uuidById + missing.associateWith { UUID.randomUUID().toString() })
    }

    fun renewed(ids: Collection<Int>): LayerUuids = LayerUuids(uuidById + ids.associateWith { UUID.randomUUID().toString() })

    override fun equals(other: Any?): Boolean = other is LayerUuids && other.uuidById == uuidById

    override fun hashCode(): Int = uuidById.hashCode()

    companion object {
        val EMPTY = LayerUuids(emptyMap())

        fun of(uuidById: Map<Int, String>): LayerUuids {
            require(uuidById.values.toSet().size == uuidById.size) { "Duplicate layer UUID" }
            return LayerUuids(uuidById)
        }

        private fun maskIdOf(node: LayerNode): Sequence<Int> =
            (node as? RasterLayer)?.mask?.let { sequenceOf(it.id) } ?: emptySequence()
    }
}
