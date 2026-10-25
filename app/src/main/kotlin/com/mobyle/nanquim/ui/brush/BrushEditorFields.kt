package com.mobyle.nanquim.ui.brush

import androidx.annotation.StringRes
import com.mobyle.nanquim.R
import com.mobyle.nanquim.brush.BrushEditorSection
import com.mobyle.nanquim.brush.TextureSlot
import com.mobyle.nanquim.ui.canvas.blendNameRes
import com.mobyle.nanquim.document.layer.BlendMode

enum class FieldUnit {
    Percent,

    Pixels,

    Degrees,

    Count,

    Factor,
}

sealed interface EditorField {
    @get:StringRes val labelRes: Int
}

data class Header(
    @StringRes override val labelRes: Int,
) : EditorField

data class NumberField(
    val path: String,
    @StringRes override val labelRes: Int,
    val minimum: Double,
    val maximum: Double,
    val default: Double,
    val unit: FieldUnit = FieldUnit.Percent,
) : EditorField

data class ToggleField(
    val path: String,
    @StringRes override val labelRes: Int,
    val default: Boolean,
) : EditorField

data class ChoiceField(
    val path: String,
    @StringRes override val labelRes: Int,
    val options: List<Pair<String, Int>>,
    val default: String,
) : EditorField

data class CurveField(
    val path: String,
    @StringRes override val labelRes: Int,
) : EditorField {
    val curveName: String get() = path.replace('.', '-')
}

data class TextureField(
    val slot: TextureSlot,
    @StringRes override val labelRes: Int,
) : EditorField

enum class AboutPart { CreatedAt, AuthorPicture, Signature, ResetPoints }

data class AboutField(
    val part: AboutPart,
    @StringRes override val labelRes: Int,
) : EditorField

data class BrushPickerField(
    val path: String,
    @StringRes override val labelRes: Int,
) : EditorField

data class TextField(
    val path: String,
    @StringRes override val labelRes: Int,
) : EditorField

private const val MAXIMUM_TAPER_PIXELS = 500.0
private const val PI = Math.PI

private val FILTERING =
    listOf(
        "none" to R.string.brush_field_filtering_none,
        "classic" to R.string.brush_field_filtering_classic,
        "improved" to R.string.brush_field_filtering_improved,
    )

private val LAYER_BLEND_MODES =
    BlendMode.entries.filter { it != BlendMode.PassThrough }.map { it.id to blendNameRes(it) }

private fun taperFields(prefix: String) =
    listOf(
        NumberField("$prefix.start", R.string.brush_field_taper_start, 0.0, MAXIMUM_TAPER_PIXELS, 0.0, FieldUnit.Pixels),
        NumberField("$prefix.end", R.string.brush_field_taper_end, 0.0, MAXIMUM_TAPER_PIXELS, 0.0, FieldUnit.Pixels),
        NumberField("$prefix.minSize", R.string.brush_field_taper_size, 0.0, 1.0, 0.0),
        NumberField("$prefix.minOpacity", R.string.brush_field_taper_opacity, 0.0, 1.0, 1.0),
        NumberField("$prefix.shape", R.string.brush_field_taper_shape, -1.0, 1.0, 0.0, FieldUnit.Factor),
        NumberField("$prefix.tip", R.string.brush_field_taper_tip, 0.0, 1.0, 1.0),
        ToggleField("$prefix.pressureMode", R.string.brush_field_taper_pressure, true),
        ToggleField("$prefix.relative", R.string.brush_field_taper_relative, false),
        ToggleField("$prefix.tipAnimation", R.string.brush_field_taper_tip_animation, false),
        ToggleField("$prefix.linkStartEnd", R.string.brush_field_taper_link, false),
    )

val BRUSH_EDITOR_FIELDS: Map<BrushEditorSection, List<EditorField>> =
    mapOf(
        BrushEditorSection.StrokePath to
            listOf(
                NumberField("path.spacing", R.string.brush_field_spacing, 0.005, 2.0, 0.1),
                NumberField("path.jitterLateral", R.string.brush_field_jitter, 0.0, 2.0, 0.0),
                NumberField("path.falloff", R.string.brush_field_falloff, 0.0, 1.0, 0.0),
            ),
        BrushEditorSection.Stabilization to
            listOf(
                Header(R.string.brush_header_streamline),
                NumberField("path.streamline", R.string.brush_field_amount, 0.0, 1.0, 0.0),
                NumberField("path.pressureSmoothing", R.string.brush_field_pressure, 0.0, 1.0, 0.0),
                Header(R.string.brush_header_stabilization),
                NumberField("path.stabilization", R.string.brush_field_amount, 0.0, 1.0, 0.0),
                Header(R.string.brush_header_motion_filtering),
                NumberField("path.jitterFilter", R.string.brush_field_amount, 0.0, 1.0, 0.0),
                NumberField("path.jitterFilterExpression", R.string.brush_field_expression, 0.0, 1.0, 0.0),
            ),
        BrushEditorSection.Taper to
            listOf(Header(R.string.brush_header_pressure_taper)) + taperFields("taper.stylus") +
                listOf(Header(R.string.brush_header_touch_taper)) + taperFields("taper.finger") +
                listOf(ToggleField("taper.classic", R.string.brush_field_taper_classic, false)),
        BrushEditorSection.Shape to
            listOf(
                TextureField(TextureSlot.Shape, R.string.brush_field_shape_source),
                NumberField("shape.hardness", R.string.brush_field_hardness, 0.0, 1.0, 1.0),
                ToggleField("shape.invert", R.string.brush_field_invert, false),
                Header(R.string.brush_header_shape_behavior),
                ChoiceField(
                    "shape.rotationMode",
                    R.string.brush_field_rotation_mode,
                    listOf(
                        "fixed" to R.string.brush_field_rotation_fixed,
                        "followStroke" to R.string.brush_field_rotation_follow,
                        "penAzimuth" to R.string.brush_field_rotation_azimuth,
                        "random" to R.string.brush_field_rotation_random,
                    ),
                    "followStroke",
                ),
                NumberField("shape.rotation", R.string.brush_field_rotation, -PI, PI, 0.0, FieldUnit.Degrees),
                NumberField("shape.rotationJitter", R.string.brush_field_rotation_jitter, 0.0, 1.0, 0.0),
                ToggleField("shape.randomized", R.string.brush_field_randomized, false),
                ToggleField("shape.azimuth", R.string.brush_field_azimuth, false),
                NumberField("shape.scatter", R.string.brush_field_scatter, 0.0, 2.0, 0.0),
                NumberField("shape.count", R.string.brush_field_count, 1.0, 16.0, 1.0, FieldUnit.Count),
                NumberField("shape.countJitter", R.string.brush_field_count_jitter, 0.0, 1.0, 0.0),
                NumberField("shape.flipXJitter", R.string.brush_field_flip_x, 0.0, 1.0, 0.0),
                NumberField("shape.flipYJitter", R.string.brush_field_flip_y, 0.0, 1.0, 0.0),
                NumberField("shape.roundness", R.string.brush_field_roundness, 0.05, 1.0, 1.0),
                CurveField("shape.pressureRoundness", R.string.brush_field_pressure_roundness),
                NumberField("shape.pressureRoundnessAmount", R.string.brush_field_amount, 0.0, 1.0, 0.0),
                NumberField("shape.tiltRoundnessAmount", R.string.brush_field_tilt_roundness, 0.0, 1.0, 0.0),
                ChoiceField("shape.filtering", R.string.brush_field_filtering, FILTERING, "improved"),
                Header(R.string.brush_header_stamp),
                ToggleField("stamp.enabled", R.string.brush_field_stamp_enabled, false),
                ToggleField("stamp.placeOnRelease", R.string.brush_field_stamp_place_on_release, false),
                ToggleField("stamp.keepColors", R.string.brush_field_stamp_keep_colors, false),
            ),
        BrushEditorSection.Grain to
            listOf(
                TextureField(TextureSlot.Grain, R.string.brush_field_grain_source),
                Header(R.string.brush_header_grain_behavior),
                NumberField("grain.movement", R.string.brush_field_grain_movement, 0.0, 1.0, 1.0),
                NumberField("grain.scale", R.string.brush_field_grain_scale, 0.05, 16.0, 1.0, FieldUnit.Factor),
                NumberField("grain.zoom", R.string.brush_field_grain_zoom, 0.0, 1.0, 0.0),
                NumberField("grain.rotation", R.string.brush_field_rotation, -PI, PI, 0.0, FieldUnit.Degrees),
                ToggleField("grain.rotateWithDab", R.string.brush_field_grain_rotate_with_dab, false),
                NumberField("grain.depth", R.string.brush_field_grain_depth, 0.0, 1.0, 1.0),
                NumberField("grain.depthMinimum", R.string.brush_field_grain_depth_minimum, 0.0, 1.0, 0.0),
                NumberField("grain.depthJitter", R.string.brush_field_grain_depth_jitter, 0.0, 1.0, 0.0),
                CurveField("grain.pressureDepth", R.string.brush_field_grain_pressure_depth),
                NumberField("grain.pressureDepthAmount", R.string.brush_field_amount, 0.0, 1.0, 0.0),
                ToggleField("grain.offsetJitter", R.string.brush_field_grain_offset_jitter, true),
                ToggleField("grain.perStroke", R.string.brush_field_grain_per_stroke, false),
                ChoiceField(
                    "grain.blend",
                    R.string.brush_field_grain_blend,
                    listOf(
                        "multiply" to R.string.brush_field_blend_multiply,
                        "subtract" to R.string.brush_field_blend_subtract,
                        "linearBurn" to R.string.brush_field_blend_linear_burn,
                        "colorBurn" to R.string.brush_field_blend_color_burn,
                        "overlay" to R.string.brush_field_blend_overlay,
                        "height" to R.string.brush_field_blend_height,
                    ),
                    "multiply",
                ),
                NumberField("grain.brightness", R.string.brush_field_brightness, -1.0, 1.0, 0.0),
                NumberField("grain.contrast", R.string.brush_field_contrast, -1.0, 1.0, 0.0),
                ToggleField("grain.invert", R.string.brush_field_invert, false),
                ChoiceField("grain.filtering", R.string.brush_field_filtering, FILTERING, "improved"),
            ),
        BrushEditorSection.Rendering to
            listOf(
                ChoiceField(
                    "render.accumulation",
                    R.string.brush_field_rendering_mode,
                    listOf(
                        "glazeLight" to R.string.brush_field_mode_light_glaze,
                        "glazeUniform" to R.string.brush_field_mode_uniform_glaze,
                        "glazeIntense" to R.string.brush_field_mode_intense_glaze,
                        "glazeHeavy" to R.string.brush_field_mode_heavy_glaze,
                        "blendUniform" to R.string.brush_field_mode_uniform_blending,
                        "blendIntense" to R.string.brush_field_mode_intense_blending,
                        "buildUp" to R.string.brush_field_mode_build_up,
                        "wash" to R.string.brush_field_mode_wash,
                    ),
                    "glazeLight",
                ),
                NumberField("render.flow", R.string.brush_field_flow, 0.0, 1.0, 1.0),
                NumberField("render.wetEdges", R.string.brush_field_wet_edges, 0.0, 1.0, 0.0),
                NumberField("render.burntEdges", R.string.brush_field_burnt_edges, 0.0, 1.0, 0.0),
                ChoiceField("render.burntEdgesBlend", R.string.brush_field_burnt_edges_mode, LAYER_BLEND_MODES, "multiply"),
                ChoiceField(
                    "render.blend",
                    R.string.brush_field_blend_mode,
                    LAYER_BLEND_MODES + ("behind" to R.string.brush_field_blend_behind),
                    "normal",
                ),
                ToggleField("render.luminanceBlending", R.string.brush_field_luminance_blending, false),
                ToggleField("render.gammaCorrect", R.string.brush_field_gamma_correct, false),
            ),
        BrushEditorSection.WetMix to
            listOf(
                ToggleField("wetMix.enabled", R.string.brush_field_wet_mix_enabled, false),
                NumberField("wetMix.dilution", R.string.brush_field_dilution, 0.0, 1.0, 0.0),
                NumberField("wetMix.charge", R.string.brush_field_charge, 0.0, 1.0, 1.0),
                NumberField("wetMix.attack", R.string.brush_field_attack, 0.0, 1.0, 1.0),
                NumberField("wetMix.pull", R.string.brush_field_pull, 0.0, 1.0, 0.0),
                NumberField("wetMix.grade", R.string.brush_field_grade, 0.0, 1.0, 0.0),
                NumberField("wetMix.blur", R.string.brush_field_blur, 0.0, 1.0, 0.0),
                NumberField("wetMix.blurJitter", R.string.brush_field_blur_jitter, 0.0, 1.0, 0.0),
                NumberField("wetMix.wetnessJitter", R.string.brush_field_wetness_jitter, 0.0, 1.0, 0.0),
            ),
        BrushEditorSection.Smudge to
            listOf(
                NumberField("smudge.strength", R.string.brush_field_smudge_strength, 0.0, 1.0, 0.8),
                NumberField("smudge.pickUp", R.string.brush_field_pick_up, 0.0, 1.0, 0.5),
                NumberField("smudge.length", R.string.brush_field_length, 0.0, 1.0, 0.5),
                NumberField("smudge.useBrushColor", R.string.brush_field_use_brush_color, 0.0, 1.0, 0.0),
            ),
        BrushEditorSection.Particles to
            listOf(
                NumberField("particles.rate", R.string.brush_field_particle_rate, 0.0, 100.0, 0.0, FieldUnit.Count),
                NumberField("particles.lifetimeMs", R.string.brush_field_particle_lifetime, 1.0, 3000.0, 600.0, FieldUnit.Count),
                NumberField("particles.initialSpeed", R.string.brush_field_particle_speed, 0.0, 2000.0, 150.0, FieldUnit.Count),
                NumberField("particles.speedJitter", R.string.brush_field_particle_speed_jitter, 0.0, 1.0, 0.0),
                ChoiceField(
                    "particles.direction",
                    R.string.brush_field_particle_direction,
                    listOf(
                        "random" to R.string.brush_field_particle_direction_random,
                        "followStroke" to R.string.brush_field_particle_direction_follow,
                        "outward" to R.string.brush_field_particle_direction_outward,
                    ),
                    "random",
                ),
                NumberField("particles.gravity", R.string.brush_field_particle_gravity, -2000.0, 2000.0, 0.0, FieldUnit.Count),
                NumberField("particles.drag", R.string.brush_field_particle_drag, 0.0, 1.0, 0.0),
                NumberField("particles.sizeStart", R.string.brush_field_particle_size_start, 0.0, 4.0, 0.3, FieldUnit.Factor),
                NumberField("particles.sizeEnd", R.string.brush_field_particle_size_end, 0.0, 4.0, 0.1, FieldUnit.Factor),
                NumberField("particles.opacityStart", R.string.brush_field_particle_opacity_start, 0.0, 1.0, 1.0),
                NumberField("particles.opacityEnd", R.string.brush_field_particle_opacity_end, 0.0, 1.0, 0.0),
                NumberField("particles.rotationSpeed", R.string.brush_field_particle_rotation_speed, -20.0, 20.0, 0.0, FieldUnit.Factor),
                ToggleField("particles.useShape", R.string.brush_field_particle_use_shape, true),
            ),
        BrushEditorSection.DualBrush to
            listOf(
                ToggleField("dual.enabled", R.string.brush_field_dual_enabled, true),
                BrushPickerField("dual.brushId", R.string.brush_field_dual_brush),
                ChoiceField(
                    "dual.blend",
                    R.string.brush_field_dual_blend,
                    listOf(
                        "multiply" to R.string.brush_field_blend_multiply,
                        "subtract" to R.string.brush_field_blend_subtract,
                        "darken" to R.string.blend_darken,
                        "lighten" to R.string.blend_lighten,
                        "overlay" to R.string.brush_field_blend_overlay,
                        "height" to R.string.brush_field_blend_height,
                    ),
                    "multiply",
                ),
            ),
        BrushEditorSection.ColorDynamics to
            listOf(
                Header(R.string.brush_header_stamp_color_jitter),
                NumberField("colorDynamics.hueJitter", R.string.brush_field_hue, 0.0, 1.0, 0.0),
                NumberField("colorDynamics.saturationJitter", R.string.brush_field_saturation, 0.0, 1.0, 0.0),
                NumberField("colorDynamics.brightnessJitter", R.string.brush_field_brightness, 0.0, 1.0, 0.0),
                NumberField("colorDynamics.darknessJitter", R.string.brush_field_darkness, 0.0, 1.0, 0.0),
                NumberField("colorDynamics.secondaryColorJitter", R.string.brush_field_secondary_color, 0.0, 1.0, 0.0),
                Header(R.string.brush_header_stroke_color_jitter),
                NumberField("colorDynamics.strokeHueJitter", R.string.brush_field_hue, 0.0, 1.0, 0.0),
                NumberField("colorDynamics.strokeSaturationJitter", R.string.brush_field_saturation, 0.0, 1.0, 0.0),
                NumberField("colorDynamics.strokeBrightnessJitter", R.string.brush_field_brightness, 0.0, 1.0, 0.0),
                NumberField("colorDynamics.strokeDarknessJitter", R.string.brush_field_darkness, 0.0, 1.0, 0.0),
                NumberField("colorDynamics.strokeSecondaryColorJitter", R.string.brush_field_secondary_color, 0.0, 1.0, 0.0),
                Header(R.string.brush_header_color_pressure),
                NumberField("colorDynamics.pressureHue", R.string.brush_field_hue, -1.0, 1.0, 0.0),
                NumberField("colorDynamics.pressureSaturation", R.string.brush_field_saturation, -1.0, 1.0, 0.0),
                NumberField("colorDynamics.pressureBrightness", R.string.brush_field_brightness, -1.0, 1.0, 0.0),
                NumberField("colorDynamics.secondaryColorByPressure", R.string.brush_field_secondary_color, 0.0, 1.0, 0.0),
                Header(R.string.brush_header_color_tilt),
                NumberField("colorDynamics.tiltHue", R.string.brush_field_hue, -1.0, 1.0, 0.0),
                NumberField("colorDynamics.tiltSaturation", R.string.brush_field_saturation, -1.0, 1.0, 0.0),
                NumberField("colorDynamics.tiltBrightness", R.string.brush_field_brightness, -1.0, 1.0, 0.0),
                NumberField("colorDynamics.tiltSecondaryColor", R.string.brush_field_secondary_color, 0.0, 1.0, 0.0),
            ),
        BrushEditorSection.Dynamics to
            listOf(
                Header(R.string.brush_header_speed),
                NumberField("dynamics.size.speed", R.string.brush_field_size, -1.0, 1.0, 0.0),
                NumberField("dynamics.opacity.speed", R.string.brush_field_opacity, -1.0, 1.0, 0.0),
                Header(R.string.brush_header_jitter),
                NumberField("dynamics.size.jitter", R.string.brush_field_size, 0.0, 1.0, 0.0),
                NumberField("dynamics.opacity.jitter", R.string.brush_field_opacity, 0.0, 1.0, 0.0),
            ),
        BrushEditorSection.Stylus to
            listOf(
                Header(R.string.brush_header_pressure),
                CurveField("dynamics.size.pressure", R.string.brush_field_size),
                NumberField("dynamics.size.min", R.string.brush_field_minimum, 0.0, 1.0, 0.0),
                CurveField("dynamics.opacity.pressure", R.string.brush_field_opacity),
                NumberField("dynamics.opacity.min", R.string.brush_field_minimum, 0.0, 1.0, 0.0),
                CurveField("dynamics.flow.pressure", R.string.brush_field_flow),
                NumberField("dynamics.flow.min", R.string.brush_field_minimum, 0.0, 1.0, 0.0),
                CurveField("dynamics.bleed.pressure", R.string.brush_field_bleed),
                NumberField("dynamics.bleed.pressureAmount", R.string.brush_field_amount, 0.0, 1.0, 0.0),
                Header(R.string.brush_header_tilt),
                CurveField("dynamics.size.tilt", R.string.brush_field_size),
                NumberField("dynamics.size.tiltAmount", R.string.brush_field_amount, 0.0, 1.0, 0.0),
                NumberField("dynamics.size.tiltSizeCompression", R.string.brush_field_size_compression, 0.0, 1.0, 0.0),
                CurveField("dynamics.opacity.tilt", R.string.brush_field_opacity),
                NumberField("dynamics.opacity.tiltAmount", R.string.brush_field_amount, 0.0, 1.0, 0.0),
                NumberField("dynamics.tiltGradation", R.string.brush_field_gradation, 0.0, 1.0, 0.0),
                CurveField("dynamics.bleed.tilt", R.string.brush_field_bleed),
                NumberField("dynamics.bleed.tiltAmount", R.string.brush_field_amount, 0.0, 1.0, 0.0),
                Header(R.string.brush_header_finger),
                ChoiceField(
                    "dynamics.fingerPressure",
                    R.string.brush_field_finger_pressure,
                    listOf("constant" to R.string.brush_field_finger_constant, "speed" to R.string.brush_field_finger_speed),
                    "constant",
                ),
                Header(R.string.brush_header_hover),
                ToggleField("hover.outline", R.string.brush_field_hover_outline, true),
                ToggleField("hover.fill", R.string.brush_field_hover_fill, false),
                NumberField("hover.fillOpacity", R.string.brush_field_hover_fill_opacity, 0.0, 1.0, 0.2),
                NumberField("hover.pressure", R.string.brush_field_hover_pressure, 0.0, 1.0, 0.5),
                Header(R.string.brush_header_roll),
                ToggleField("roll.enabled", R.string.brush_field_roll_enabled, false),
                NumberField("roll.amount", R.string.brush_field_amount, -4.0, 4.0, 1.0, FieldUnit.Factor),
            ),
        BrushEditorSection.Properties to
            listOf(
                Header(R.string.brush_header_brush_properties),
                ToggleField("properties.stampPreview", R.string.brush_field_stamp_preview, false),
                ToggleField("properties.orientToScreen", R.string.brush_field_orient_to_screen, false),
                NumberField("properties.previewSize", R.string.brush_field_preview_size, 0.05, 1.0, 0.5),
                NumberField("properties.smudge", R.string.brush_field_smudge_amount, 0.0, 1.0, 0.8),
                Header(R.string.brush_header_brush_behavior),
                NumberField("limits.maxSize", R.string.brush_field_max_size, 1.0, 1000.0, 500.0, FieldUnit.Pixels),
                NumberField("limits.minSize", R.string.brush_field_min_size, 0.1, 100.0, 1.0, FieldUnit.Pixels),
                NumberField("limits.maxOpacity", R.string.brush_field_max_opacity, 0.0, 1.0, 1.0),
                NumberField("limits.minOpacity", R.string.brush_field_min_opacity, 0.0, 1.0, 0.0),
                NumberField("defaults.size", R.string.brush_field_default_size, 0.0, 1.0, 0.3),
                NumberField("defaults.opacity", R.string.brush_field_default_opacity, 0.0, 1.0, 1.0),
            ),
        BrushEditorSection.About to
            listOf(
                TextField("name", R.string.brush_field_name),
                TextField("author", R.string.brush_field_author),
                TextField("license", R.string.brush_field_license),
                AboutField(AboutPart.CreatedAt, R.string.brush_field_created_at),
                AboutField(AboutPart.AuthorPicture, R.string.brush_field_author_picture),
                AboutField(AboutPart.Signature, R.string.brush_field_signature),
                Header(R.string.brush_header_reset_points),
                AboutField(AboutPart.ResetPoints, R.string.brush_field_reset_points),
            ),
    )

fun editorFields(
    section: BrushEditorSection,
    stylusRollReported: Boolean,
): List<EditorField> =
    BRUSH_EDITOR_FIELDS.getValue(section).filter { field ->
        stylusRollReported ||
            !(
                (field is Header && field.labelRes == R.string.brush_header_roll) ||
                    (field is ToggleField && field.path.startsWith("roll.")) ||
                    (field is NumberField && field.path.startsWith("roll."))
            )
    }

fun sectionTitle(section: BrushEditorSection): Int =
    when (section) {
        BrushEditorSection.StrokePath -> R.string.brush_section_stroke_path
        BrushEditorSection.Stabilization -> R.string.brush_section_stabilization
        BrushEditorSection.Taper -> R.string.brush_section_taper
        BrushEditorSection.Shape -> R.string.brush_section_shape
        BrushEditorSection.Grain -> R.string.brush_section_grain
        BrushEditorSection.Rendering -> R.string.brush_section_rendering
        BrushEditorSection.WetMix -> R.string.brush_section_wet_mix
        BrushEditorSection.ColorDynamics -> R.string.brush_section_color_dynamics
        BrushEditorSection.Dynamics -> R.string.brush_section_dynamics
        BrushEditorSection.Stylus -> R.string.brush_section_stylus
        BrushEditorSection.Properties -> R.string.brush_section_properties
        BrushEditorSection.About -> R.string.brush_section_about
        BrushEditorSection.Smudge -> R.string.brush_section_smudge
        BrushEditorSection.Particles -> R.string.brush_section_particles
        BrushEditorSection.DualBrush -> R.string.brush_section_dual_brush
    }
