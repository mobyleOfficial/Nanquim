package com.mobyle.nanquim.formats.interchange

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import com.mobyle.nanquim.document.brush.BrushDocument
import com.mobyle.nanquim.document.brush.BrushPackage
import kotlin.math.PI
import kotlin.math.roundToInt

data class ConvertedBrush(
    val brushPackage: BrushPackage,
    val warnings: List<ImportWarning>,
)

object ProcreateBrush {
    const val ARCHIVE = "Brush.archive"
    const val SHAPE = "Shape.png"
    const val GRAIN = "Grain.png"
    const val AUTHOR_PICTURE = "AuthorPicture/AuthorPicture.png"
    const val SIGNATURE = "Signature/SignaturePicture.png"

    fun convert(
        archive: JsonObject,
        files: Map<String, ByteArray>,
        id: String,
        createdAt: String,
    ): ConvertedBrush {
        if (archive.text("\$class") != "SilicaBrush") throw ImportJsonException("Not a Procreate brush")
        val warnings = mutableListOf<ImportWarning>()
        val name =
            archive
                .text("name")
                ?.trim()
                .orEmpty()
                .ifEmpty { "Brush" }
        warnings += ImportWarning("provisionalBrushMapping", name)
        val curves = mutableMapOf<String, JsonArray>()

        fun curve(
            key: String,
            label: String,
        ): JsonElement {
            val points = (archive[key] as? JsonObject)?.get("points") as? JsonArray ?: return JsonPrimitive("curve:linear")
            val parsed = points.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::point) }.sortedBy { it.first }
            if (parsed.size < 2 || parsed.first().first != 0.0 || parsed.last().first != 1.0) return JsonPrimitive("curve:linear")
            if (parsed == listOf(0.0 to 0.0, 1.0 to 1.0)) return JsonPrimitive("curve:linear")
            curves[label] =
                buildJsonArray {
                    parsed.forEach { (x, y) ->
                        add(
                            buildJsonArray {
                                add(JsonPrimitive(x))
                                add(JsonPrimitive(y))
                            },
                        )
                    }
                }
            return JsonPrimitive("curve:$label")
        }

        fun dynamics(
            pressureAmount: Double,
            pressureCurveKey: String,
            label: String,
            tiltAmount: Double,
            speed: Double,
            jitter: Double,
        ): JsonObject =
            buildJsonObject {
                put("min", 0.0)
                put("pressure", if (pressureAmount > 0) curve(pressureCurveKey, label) else JsonNull)
                put("pressureAmount", pressureAmount.coerceIn(0.0, 1.0))
                put("tilt", if (tiltAmount > 0) JsonPrimitive("curve:linear") else JsonNull)
                put("tiltAmount", tiltAmount.coerceIn(0.0, 1.0))
                put("speed", speed.coerceIn(-1.0, 1.0))
                put("jitter", jitter.coerceIn(0.0, 1.0))
            }

        val shapeFile = files[SHAPE]
        val grainFile = files[GRAIN]
        val packageFiles = mutableMapOf<String, ByteArray>()
        shapeFile?.let { packageFiles[BrushDocument.DEFAULT_SHAPE_TEXTURE] = it }
        grainFile?.let { packageFiles["grain.png"] = it }
        files[AUTHOR_PICTURE]?.let { packageFiles["author.png"] = it }
        files[SIGNATURE]?.let { packageFiles["signature.png"] = it }
        if (shapeFile == null) {
            warnings += ImportWarning("brushShapeReplaced", name)
        }
        val shapeName = archive.text("bundledShapePath").orEmpty().lowercase()
        val hardness =
            when {
                "hard" in shapeName -> 1.0
                "soft" in shapeName -> 0.0
                else -> 0.5
            }
        val rotationMode =
            when {
                archive.flag("shapeAzimuth") -> "penAzimuth"
                archive.flag("shapeRandomise") -> "random"
                archive.flag("oriented") -> "followStroke"
                else -> "fixed"
            }

        val json =
            buildJsonObject {
                put("format", BrushDocument.FORMAT)
                put("id", id)
                put("name", name)
                archive.text("authorName")?.takeIf { it.isNotBlank() }?.let { put("author", it) }
                put("createdAt", createdAt)
                put("importedFrom", "procreate")
                putJsonObject("path") {
                    put("spacing", archive.number("plotSpacing", 0.1).coerceIn(0.005, 2.0))
                    put("jitterLateral", archive.number("plotJitter", 0.0).coerceIn(0.0, 2.0))
                    put("streamline", archive.number("plotSmoothing", 0.0).coerceIn(0.0, 1.0))
                    put("stabilization", archive.number("plotMovingAverageStabilization", 0.0).coerceIn(0.0, 1.0))
                    put("jitterFilter", archive.number("plotFFTSmoothingAmount", 0.0).coerceIn(0.0, 1.0))
                    put("pressureSmoothing", archive.number("dynamicsPressureSmoothing", 0.0).coerceIn(0.0, 1.0))
                    put("falloff", archive.number("dynamicsFalloff", 0.0).coerceIn(0.0, 1.0))
                }
                putJsonObject("shape") {
                    put("texture", if (shapeFile != null) JsonPrimitive(BrushDocument.DEFAULT_SHAPE_TEXTURE) else JsonNull)
                    put("hardness", hardness)
                    put("rotationMode", rotationMode)
                    put("rotation", archive.number("shapeRotation", 0.0) * PI)
                    put("roundness", archive.number("shapeRoundness", 1.0).coerceIn(0.05, 1.0))
                    put("scatter", archive.number("shapeScatter", 0.0).coerceIn(0.0, 2.0))
                    put("count", (1 + (archive.number("shapeCount", 0.0) * 15).roundToInt()).coerceIn(1, 16))
                    put("countJitter", archive.number("shapeCountJitter", 0.0).coerceIn(0.0, 1.0))
                    put("flipXJitter", if (archive.flag("shapeFlipXJitter")) 0.5 else 0.0)
                    put("flipYJitter", if (archive.flag("shapeFlipYJitter")) 0.5 else 0.0)
                    put("invert", archive.flag("shapeInverted"))
                    put("filtering", if (archive.flag("shapeFilter", true)) "improved" else "none")
                    put("azimuth", archive.flag("shapeAzimuth"))
                    put("randomized", archive.flag("shapeRandomise"))
                }
                if (grainFile != null) {
                    putJsonObject("grain") {
                        put("texture", "grain.png")
                        val movement = archive.number("textureMovement", 0.0).coerceIn(0.0, 1.0)
                        put("movement", movement)
                        put("mode", if (movement >= 0.5) "moving" else "texturized")
                        put("zoom", archive.number("textureZoom", 0.0).coerceIn(0.0, 1.0))
                        put("scale", archive.number("textureScale", 1.0).coerceIn(0.05, 16.0))
                        put("rotation", archive.number("textureRotation", 0.0) * PI)
                        put("offsetJitter", archive.flag("textureOffsetJitter", true))
                        put("depth", archive.number("grainDepth", 1.0).coerceIn(0.0, 1.0))
                        put("depthMinimum", archive.number("grainDepthMinimum", 0.0).coerceIn(0.0, 1.0))
                        put("depthJitter", archive.number("grainDepthJitter", 0.0).coerceIn(0.0, 1.0))
                        put("brightness", archive.number("textureBrightness", 0.0).coerceIn(-1.0, 1.0))
                        put("contrast", archive.number("textureContrast", 0.0).coerceIn(-1.0, 1.0))
                        put("invert", archive.flag("textureInverted"))
                        put("blend", "multiply")
                    }
                } else {
                    put("grain", buildJsonObject { put("texture", JsonNull) })
                }
                putJsonObject("dynamics") {
                    put(
                        "size",
                        dynamics(
                            archive.number("dynamicsPressureSize", 0.0),
                            "dynamicsPressureSizeCurve",
                            "pressureSize",
                            archive.number("dynamicsTiltSize", 0.0),
                            archive.number("dynamicsSpeedSize", 0.0),
                            archive.number("dynamicsJitterSize", 0.0),
                        ),
                    )
                    put(
                        "opacity",
                        dynamics(
                            archive.number("dynamicsPressureOpacity", 0.0),
                            "dynamicsPressureOpacityCurve",
                            "pressureOpacity",
                            archive.number("dynamicsTiltOpacity", 0.0),
                            archive.number("dynamicsSpeedOpacity", 0.0),
                            archive.number("dynamicsJitterOpacity", 0.0),
                        ),
                    )
                    putJsonObject("bleed") {
                        val pressure = archive.number("dynamicsPressureBleed", 0.0).coerceIn(0.0, 1.0)
                        val tilt = archive.number("dynamicsTiltBleed", 0.0).coerceIn(0.0, 1.0)
                        put("min", 0.0)
                        put("pressure", if (pressure > 0) curve("dynamicsPressureBleedCurve", "pressureBleed") else JsonNull)
                        put("pressureAmount", pressure)
                        put("tilt", if (tilt > 0) JsonPrimitive("curve:linear") else JsonNull)
                        put("tiltAmount", tilt)
                    }
                    put("tiltSizeCompression", archive.number("dynamicsTiltCompression", 0.0).coerceIn(0.0, 1.0))
                    put("tiltGradation", archive.number("dynamicsTiltGradation", 0.0).coerceIn(0.0, 1.0))
                }
                putJsonObject("render") {
                    put("flow", archive.number("dynamicsGlazedFlow", 1.0).coerceIn(0.0, 1.0))
                    put("gammaCorrect", archive.flag("blendGammaCorrect"))
                    put("wetEdges", archive.number("wetEdgesAmount", 0.0).coerceIn(0.0, 1.0))
                    put("burntEdges", archive.number("burntEdgesAmount", 0.0).coerceIn(0.0, 1.0))
                }
                putJsonObject("taper") {
                    put("stylus", taper(archive, "pencilTaper"))
                    put("finger", taper(archive, "taper"))
                }
                putJsonObject("colorDynamics") {
                    for ((ours, theirs) in COLOR_DYNAMICS) put(ours, archive.number(theirs, 0.0).coerceIn(-1.0, 1.0))
                }
                putJsonObject("hover") {
                    put("outline", archive.flag("hoverOutline", true))
                    put("fill", archive.flag("hoverFill"))
                    put("pressure", archive.number("hoverPressure", 0.5).coerceIn(0.0, 1.0))
                }
                putJsonObject("properties") {
                    put("previewSize", archive.number("previewSize", 0.5).coerceIn(0.0, 1.0))
                    put("smudge", archive.number("smudgeOpacity", 0.8).coerceIn(0.0, 1.0))
                }
                putJsonObject("defaults") {
                    put("size", archive.number("paintSize", 0.3).coerceIn(0.0, 1.0))
                    put("opacity", archive.number("paintOpacity", 1.0).coerceIn(0.0, 1.0))
                }
                if (curves.isNotEmpty()) put("curves", JsonObject(curves))
                putJsonObject("extensions") { put("procreate", archive) }
            }
        if (archive.number("blendMode", 0.0) != 0.0) warnings += ImportWarning("brushBlendModeIgnored", name)
        if (archive.number("dynamicsMix", 0.0) > 0 || archive.number("dynamicsLoad", 0.0) > 0.01) {
            warnings += ImportWarning("brushWetMixIgnored", name)
        }
        if (archive.flag("stamp")) warnings += ImportWarning("brushStampIgnored", name)
        return ConvertedBrush(BrushPackage(BrushDocument.from(json), packageFiles), warnings)
    }

    private fun taper(
        archive: JsonObject,
        prefix: String,
    ): JsonObject =
        buildJsonObject {
            put("start", archive.number("${prefix}StartLength", 0.0).coerceIn(0.0, 1.0))
            put("end", archive.number("${prefix}EndLength", 0.0).coerceIn(0.0, 1.0))
            put("relative", true)
            put("minSize", 1 - archive.number("${prefix}Size", 1.0).coerceIn(0.0, 1.0))
            put("minOpacity", archive.number("${prefix}Opacity", 1.0).coerceIn(0.0, 1.0))
            put("shape", archive.number("${prefix}Shape", 0.0).coerceIn(-1.0, 1.0))
            put("linkStartEnd", archive.flag("${prefix}SizeLinked"))
        }

    internal fun point(text: String): Pair<Double, Double>? {
        val parts =
            text
                .trim()
                .removePrefix("{")
                .removeSuffix("}")
                .split(',')
        if (parts.size != 2) return null
        val x = parts[0].trim().toDoubleOrNull() ?: return null
        val y = parts[1].trim().toDoubleOrNull() ?: return null
        if (!x.isFinite() || !y.isFinite()) return null
        return x.coerceIn(0.0, 1.0) to y.coerceIn(0.0, 1.0)
    }

    private val COLOR_DYNAMICS =
        listOf(
            "hueJitter" to "dynamicsJitterHue",
            "saturationJitter" to "dynamicsJitterSaturation",
            "brightnessJitter" to "dynamicsJitterLightness",
            "darknessJitter" to "dynamicsJitterDarkness",
            "strokeHueJitter" to "dynamicsJitterStrokeHue",
            "strokeSaturationJitter" to "dynamicsJitterStrokeSaturation",
            "strokeBrightnessJitter" to "dynamicsJitterStrokeLightness",
            "pressureHue" to "dynamicsPressureHue",
            "pressureSaturation" to "dynamicsPressureSaturation",
            "pressureBrightness" to "dynamicsPressureBrightness",
            "tiltHue" to "dynamicsTiltHue",
            "tiltSaturation" to "dynamicsTiltSaturation",
            "tiltBrightness" to "dynamicsTiltBrightness",
            "secondaryColorByPressure" to "dynamicsPressureSecondaryColor",
        )
}

data class ProcreateBrushSetIndex(
    val name: String,
    val brushes: List<String>,
)

object ProcreateBrushSet {
    const val INDEX = "brushset.plist"

    fun parseIndex(text: String): ProcreateBrushSetIndex? {
        val name =
            Regex("<key>name</key>\\s*<string>([^<]*)</string>")
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.let(::unescape)
        val array =
            Regex("<key>brushes</key>\\s*<array>(.*?)</array>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1) ?: return null
        val brushes = Regex("<string>([^<]*)</string>").findAll(array).map { unescape(it.groupValues[1]) }.toList()
        return ProcreateBrushSetIndex(name.orEmpty(), brushes)
    }

    private fun unescape(text: String): String =
        text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.number(
    key: String,
    fallback: Double,
): Double = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: fallback

private fun JsonObject.flag(
    key: String,
    fallback: Boolean = false,
): Boolean = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: fallback
