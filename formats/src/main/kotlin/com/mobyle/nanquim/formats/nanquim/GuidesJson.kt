package com.mobyle.nanquim.formats.nanquim

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import com.mobyle.nanquim.document.guide.GridGuide
import com.mobyle.nanquim.document.guide.GuideSettings
import com.mobyle.nanquim.document.guide.GuideType
import com.mobyle.nanquim.document.guide.PerspectiveGuide
import com.mobyle.nanquim.document.guide.SymmetryGuide
import com.mobyle.nanquim.document.guide.SymmetryKind
import com.mobyle.nanquim.document.guide.VanishingPoint

internal object GuidesJson {
    fun read(
        element: Any?,
        widthPx: Int = 0,
        heightPx: Int = 0,
    ): GuideSettings? {
        val guides = element as? JsonObject ?: return null
        val defaults = GuideSettings()
        val grid = guides["grid"] as? JsonObject
        val origin = (grid?.get("origin") as? JsonArray)?.numbers()
        val perspective = guides["perspective"] as? JsonObject
        val points =
            (perspective?.get("points") as? JsonArray)
                ?.mapNotNull { (it as? JsonArray)?.numbers()?.takeIf { pair -> pair.size == 2 } }
                ?.take(PerspectiveGuide.MAXIMUM_POINTS)
                ?.map { (x, y) -> VanishingPoint(x, y) }
                .orEmpty()
        val symmetry = guides["symmetry"] as? JsonObject
        val center = (symmetry?.get("center") as? JsonArray)?.numbers()
        return GuideSettings(
            type = guides.text("type")?.let(GuideType::fromId) ?: defaults.type,
            visible = (guides["visible"] as? JsonPrimitive)?.booleanOrNull ?: defaults.visible,
            hue = guides.number("hue")?.toFloat()?.let { ((it % 360f) + 360f) % 360f } ?: defaults.hue,
            opacity = guides.number("opacity")?.toFloat()?.coerceIn(0f, 1f) ?: defaults.opacity,
            thickness =
                guides.number("thickness")?.toFloat()?.coerceIn(GuideSettings.MINIMUM_THICKNESS, GuideSettings.MAXIMUM_THICKNESS)
                    ?: defaults.thickness,
            grid =
                GridGuide(
                    size = grid?.number("size")?.coerceIn(GridGuide.MINIMUM_SIZE, GridGuide.MAXIMUM_SIZE) ?: GridGuide.DEFAULT_SIZE,
                    rotation = grid?.number("rotation") ?: 0.0,
                    originX = origin?.getOrNull(0) ?: 0.0,
                    originY = origin?.getOrNull(1) ?: 0.0,
                ),
            perspective = PerspectiveGuide(points),
            symmetry =
                SymmetryGuide(
                    kind = symmetry?.text("kind")?.let(SymmetryKind::fromId) ?: SymmetryKind.Vertical,
                    segments =
                        symmetry?.number("segments")?.toInt()?.coerceIn(SymmetryGuide.MINIMUM_SEGMENTS, SymmetryGuide.MAXIMUM_SEGMENTS)
                            ?: SymmetryGuide.DEFAULT_SEGMENTS,
                    rotational = (symmetry?.get("rotational") as? JsonPrimitive)?.booleanOrNull ?: false,
                    centerX = center?.getOrNull(0) ?: (widthPx / 2.0),
                    centerY = center?.getOrNull(1) ?: (heightPx / 2.0),
                    rotation = symmetry?.number("rotation") ?: 0.0,
                ),
        )
    }

    fun write(
        guides: GuideSettings,
        previous: JsonObject?,
        widthPx: Int = 0,
        heightPx: Int = 0,
    ): JsonObject {
        if (previous != null && read(previous, widthPx, heightPx) == guides) return previous
        val previousGrid = (previous?.get("grid") as? JsonObject).orEmpty()
        val grid =
            JsonObject(
                previousGrid +
                    mapOf(
                        "size" to JsonPrimitive(guides.grid.size),
                        "rotation" to JsonPrimitive(guides.grid.rotation),
                        "origin" to pair(guides.grid.originX, guides.grid.originY),
                    ),
            )
        val previousPerspective = (previous?.get("perspective") as? JsonObject).orEmpty()
        val perspective =
            JsonObject(
                previousPerspective +
                    mapOf(
                        "points" to JsonArray(guides.perspective.points.map { pair(it.x, it.y) }),
                    ),
            )
        val previousSymmetry = (previous?.get("symmetry") as? JsonObject).orEmpty()
        val symmetry =
            JsonObject(
                previousSymmetry +
                    mapOf(
                        "kind" to JsonPrimitive(guides.symmetry.kind.id),
                        "segments" to JsonPrimitive(guides.symmetry.segments),
                        "rotational" to JsonPrimitive(guides.symmetry.rotational),
                        "center" to pair(guides.symmetry.centerX, guides.symmetry.centerY),
                        "rotation" to JsonPrimitive(guides.symmetry.rotation),
                    ),
            )
        return JsonObject(
            previous.orEmpty() +
                mapOf(
                    "type" to JsonPrimitive(guides.type.id),
                    "visible" to JsonPrimitive(guides.visible),
                    "hue" to JsonPrimitive(guides.hue),
                    "opacity" to JsonPrimitive(guides.opacity),
                    "thickness" to JsonPrimitive(guides.thickness),
                    "grid" to grid,
                    "perspective" to perspective,
                    "symmetry" to symmetry,
                ),
        )
    }

    private fun pair(
        x: Double,
        y: Double,
    ) = JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y)))

    private fun JsonArray.numbers(): List<Double> =
        mapNotNull { element ->
            (element as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf(Double::isFinite)
        }

    private fun JsonObject?.orEmpty(): Map<String, kotlinx.serialization.json.JsonElement> = this ?: emptyMap()

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)
            ?.takeUnless {
                it.isString
            }?.doubleOrNull
            ?.takeIf(Double::isFinite)
}
