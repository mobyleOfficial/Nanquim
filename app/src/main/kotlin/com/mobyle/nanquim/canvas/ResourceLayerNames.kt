package com.mobyle.nanquim.canvas

import android.content.Context
import com.mobyle.nanquim.R
import com.mobyle.nanquim.document.history.HistoryLabel
import com.mobyle.nanquim.document.session.LayerNames

class ResourceLayerNames(
    private val context: Context,
) : LayerNames {
    override fun layer(number: Int): String = context.getString(R.string.layer_default_name, number)

    override fun group(number: Int): String = context.getString(R.string.group_default_name, number)

    override fun copyOf(name: String): String = context.getString(R.string.layer_copy_name, name)

    override fun flattened(): String = context.getString(R.string.layer_flattened_name)

    override fun selection(number: Int): String = context.getString(R.string.selection_default_name, number)
}

fun HistoryLabel.titleRes(): Int =
    when (this) {
        HistoryLabel.BrushStroke -> R.string.history_brush_stroke
        HistoryLabel.EraseStroke -> R.string.history_erase_stroke
        HistoryLabel.SmudgeStroke -> R.string.history_smudge_stroke
        HistoryLabel.HueSaturationBrightness -> R.string.adjust_hue_saturation_brightness
        HistoryLabel.ColorBalance -> R.string.adjust_color_balance
        HistoryLabel.Curves -> R.string.adjust_curves
        HistoryLabel.GradientMap -> R.string.adjust_gradient_map
        HistoryLabel.GaussianBlur -> R.string.adjust_gaussian_blur
        HistoryLabel.MotionBlur -> R.string.adjust_motion_blur
        HistoryLabel.PerspectiveBlur -> R.string.adjust_perspective_blur
        HistoryLabel.Sharpen -> R.string.adjust_sharpen
        HistoryLabel.Noise -> R.string.adjust_noise
        HistoryLabel.Bloom -> R.string.adjust_bloom
        HistoryLabel.Glitch -> R.string.adjust_glitch
        HistoryLabel.Halftone -> R.string.adjust_halftone
        HistoryLabel.ChromaticAberration -> R.string.adjust_chromatic_aberration
        HistoryLabel.Liquify -> R.string.adjust_liquify
        HistoryLabel.NewLayer -> R.string.history_new_layer
        HistoryLabel.DuplicateLayer -> R.string.history_duplicate_layer
        HistoryLabel.DeleteLayer -> R.string.history_delete_layer
        HistoryLabel.RenameLayer -> R.string.history_rename_layer
        HistoryLabel.LayerOpacity -> R.string.history_layer_opacity
        HistoryLabel.LayerBlendMode -> R.string.history_layer_blend_mode
        HistoryLabel.LayerVisibility -> R.string.history_layer_visibility
        HistoryLabel.LockLayer -> R.string.history_lock_layer
        HistoryLabel.AlphaLock -> R.string.history_alpha_lock
        HistoryLabel.DrawingAssist -> R.string.history_drawing_assist
        HistoryLabel.EditText -> R.string.history_edit_text
        HistoryLabel.Animation -> R.string.history_animation
        HistoryLabel.VideoFrame -> R.string.history_video_frame
        HistoryLabel.Private -> R.string.history_private
        HistoryLabel.RasterizeVideo -> R.string.history_rasterize_video
        HistoryLabel.RasterizeText -> R.string.history_rasterize_text
        HistoryLabel.ClippingMask -> R.string.history_clipping_mask
        HistoryLabel.AddMask -> R.string.history_add_mask
        HistoryLabel.DeleteMask -> R.string.history_delete_mask
        HistoryLabel.ApplyMask -> R.string.history_apply_mask
        HistoryLabel.MaskSettings -> R.string.history_mask_settings
        HistoryLabel.MoveLayer -> R.string.history_move_layer
        HistoryLabel.GroupLayers -> R.string.history_group_layers
        HistoryLabel.Ungroup -> R.string.history_ungroup
        HistoryLabel.MergeDown -> R.string.history_merge_down
        HistoryLabel.MergeLayers -> R.string.history_merge_layers
        HistoryLabel.CombineDown -> R.string.history_combine_down
        HistoryLabel.FlattenGroup -> R.string.history_flatten_group
        HistoryLabel.Flatten -> R.string.history_flatten
        HistoryLabel.ClearLayer -> R.string.history_clear_layer
        HistoryLabel.FillLayer -> R.string.history_fill_layer
        HistoryLabel.InvertLayer -> R.string.history_invert_layer
        HistoryLabel.ReferenceLayer -> R.string.history_reference_layer
        HistoryLabel.BackgroundColor -> R.string.history_background_color
        HistoryLabel.SelectAll -> R.string.history_select_all
        HistoryLabel.Deselect -> R.string.history_deselect
        HistoryLabel.InvertSelection -> R.string.history_invert_selection
        HistoryLabel.SelectContents -> R.string.history_select_contents
        HistoryLabel.SaveSelection -> R.string.history_save_selection
        HistoryLabel.LoadSelection -> R.string.history_load_selection
        HistoryLabel.DeleteSavedSelection -> R.string.history_delete_saved_selection
        HistoryLabel.SelectShape -> R.string.history_select_shape
        HistoryLabel.FeatherSelection -> R.string.history_feather_selection
        HistoryLabel.PaintSelection -> R.string.history_paint_selection
        HistoryLabel.FloodFill -> R.string.history_flood_fill
        HistoryLabel.AutomaticSelection -> R.string.history_automatic_selection
        HistoryLabel.Cut -> R.string.history_cut
        HistoryLabel.Paste -> R.string.history_paste
        HistoryLabel.CopyToNewLayer -> R.string.history_copy_to_new_layer
        HistoryLabel.Transform -> R.string.history_transform
        HistoryLabel.CropCanvas -> R.string.canvas_crop
        HistoryLabel.ResizeCanvas -> R.string.canvas_size
        HistoryLabel.RotateCanvas -> R.string.history_rotate_canvas
        HistoryLabel.FlipCanvas -> R.string.history_flip_canvas
    }
