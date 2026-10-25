package com.mobyle.nanquim.ui.canvas

import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material3.ripple
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.mobyle.nanquim.BuildConfig
import com.mobyle.nanquim.R
import com.mobyle.nanquim.brush.library.BrushLibrary
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.canvas.SrgbColor
import com.mobyle.nanquim.canvas.ToolSettings
import com.mobyle.nanquim.canvas.input.CanvasTool
import com.mobyle.nanquim.command.AnimationCommandIds
import com.mobyle.nanquim.command.BrushCommandIds
import com.mobyle.nanquim.command.CanvasInfoCommandIds
import com.mobyle.nanquim.command.DocumentCommandIds
import com.mobyle.nanquim.command.MacroCommandIds
import com.mobyle.nanquim.command.ReferenceCommandIds
import com.mobyle.nanquim.command.SliderNames
import com.mobyle.nanquim.command.TimelapseCommandIds
import com.mobyle.nanquim.command.ToolCommandIds
import com.mobyle.nanquim.gallery.TimelapseLength
import com.mobyle.nanquim.ui.common.LocalWindowLayout
import com.mobyle.nanquim.ui.common.NanquimIcons
import com.mobyle.nanquim.engine.Point
import kotlin.math.roundToInt

@Composable
fun CanvasBar(
    session: CanvasSession,
    onCommand: (String, Map<String, Any>) -> Unit,
    onOpenGallery: () -> Unit,
    onShare: () -> Unit,
    onAddImage: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleDebugPanel: () -> Unit,
    status: @Composable () -> Unit,
) {
    val compact = LocalWindowLayout.current.compact
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (compact) {
                BarIconButton(NanquimIcons.Gallery, R.string.gallery_button, selected = false, onClick = onOpenGallery)
            } else {
                Text(
                    stringResource(R.string.gallery_button),
                    style = MaterialTheme.typography.titleSmall,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onOpenGallery)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            ActionsMenuButton(session, onCommand, onShare, onAddImage, onOpenSettings, onToggleDebugPanel, withTools = compact)
            if (!compact) {
                AdjustmentsMenuButton(session, onCommand, icon = NanquimIcons.Adjustments)
                BarIconButton(NanquimIcons.Selection, R.string.tool_selection, session.canvasTool == CanvasTool.Selection) {
                    val next = if (session.canvasTool == CanvasTool.Selection) "brush" else "selection"
                    onCommand(ToolCommandIds.TOOL_SELECT, mapOf("tool" to next))
                }
                BarIconButton(NanquimIcons.Transform, R.string.tool_transform, session.canvasTool == CanvasTool.Transform) {
                    val next = if (session.canvasTool == CanvasTool.Transform) "brush" else "transform"
                    onCommand(ToolCommandIds.TOOL_SELECT, mapOf("tool" to next))
                }
            }
            status()
        }
        BarIconButton(NanquimIcons.Brush, R.string.tool_brush, session.canvasTool == CanvasTool.Brush) {
            if (session.canvasTool == CanvasTool.Brush) {
                onCommand(BrushCommandIds.LIBRARY_OPEN, mapOf("tool" to "paint"))
            } else {
                onCommand(ToolCommandIds.TOOL_SELECT, mapOf("tool" to "brush"))
            }
        }
        BarIconButton(NanquimIcons.Smudge, R.string.tool_smudge, session.canvasTool == CanvasTool.Smudge) {
            if (session.canvasTool == CanvasTool.Smudge) {
                onCommand(BrushCommandIds.LIBRARY_OPEN, mapOf("tool" to "smudge"))
            } else {
                onCommand(ToolCommandIds.TOOL_SELECT, mapOf("tool" to "smudge"))
            }
        }
        BarIconButton(NanquimIcons.Eraser, R.string.tool_eraser, session.canvasTool == CanvasTool.Eraser) {
            if (session.canvasTool == CanvasTool.Eraser) {
                onCommand(BrushCommandIds.LIBRARY_OPEN, mapOf("tool" to "erase"))
            } else {
                onCommand(ToolCommandIds.TOOL_SELECT, mapOf("tool" to "eraser"))
            }
        }
        BarIconButton(NanquimIcons.Layers, R.string.tool_layers, session.layersPanelVisible) {
            onCommand(DocumentCommandIds.LAYERS_PANEL, emptyMap())
        }
        val colorLabel = stringResource(R.string.tool_color)
        Swatch(
            session.primaryColor,
            selected = session.colorPanelVisible,
            description = colorLabel,
            modifier = Modifier.padding(start = 8.dp, end = 4.dp).colorDrop(session),
            secondary = session.secondaryColor,
            size = BAR_SWATCH_SIZE,
            onLongClick = { onCommand(ToolCommandIds.COLOR_SWAP, emptyMap()) },
        ) {
            onCommand(if (session.colorPanelVisible) ToolCommandIds.COLOR_PANEL_CLOSE else ToolCommandIds.COLOR_PANEL_OPEN, emptyMap())
        }
    }
}

@Composable
fun BarIconButton(
    icon: ImageVector,
    @StringRes labelRes: Int,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            selected -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        }
    Box(
        Modifier
            .size(TOUCH_TARGET_SIZE)
            .clickable(
                interactionSource = null,
                indication = ripple(bounded = false, radius = BAR_BUTTON_SIZE / 2),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(BAR_BUTTON_SIZE)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = stringResource(labelRes), tint = tint, modifier = Modifier.size(BAR_ICON_SIZE))
        }
    }
}

@Composable
private fun ActionsMenuButton(
    session: CanvasSession,
    onCommand: (String, Map<String, Any>) -> Unit,
    onShare: () -> Unit,
    onAddImage: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleDebugPanel: () -> Unit,
    withTools: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    var adjustmentsOpen by remember { mutableStateOf(false) }
    val document = session.documentSession != null
    val barTools = if (withTools) BAR_TOOLS.values + CanvasTool.Adjust else emptyList()
    val toolInMenu = session.canvasTool in ACTION_TOOLS.values || session.canvasTool in barTools
    Box {
        BarIconButton(NanquimIcons.Actions, R.string.canvas_actions, selected = open || adjustmentsOpen || toolInMenu) { open = true }
        AdjustmentsMenu(expanded = adjustmentsOpen, onDismiss = { adjustmentsOpen = false }, onCommand = onCommand)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun item(
                @StringRes labelRes: Int,
                enabled: Boolean = true,
                checked: Boolean = false,
                action: () -> Unit,
            ) = DropdownMenuItem(
                text = { Text(stringResource(labelRes)) },
                enabled = enabled,
                trailingIcon = if (checked) ({ Text("✓") }) else null,
                onClick = {
                    open = false
                    action()
                },
            )
            if (withTools) {
                item(R.string.tool_adjustments, enabled = document, checked = session.canvasTool == CanvasTool.Adjust) {
                    adjustmentsOpen = true
                }
                for ((name, tool) in BAR_TOOLS) {
                    val labelRes = if (tool == CanvasTool.Selection) R.string.tool_selection else R.string.tool_transform
                    item(labelRes, enabled = document, checked = session.canvasTool == tool) {
                        onCommand(ToolCommandIds.TOOL_SELECT, mapOf("tool" to if (session.canvasTool == tool) "brush" else name))
                    }
                }
                HorizontalDivider()
            }
            item(R.string.canvas_share, enabled = session.artwork != null, action = onShare)
            item(R.string.canvas_add_image, enabled = document, action = onAddImage)
            HorizontalDivider()
            for ((name, tool) in ACTION_TOOLS) {
                val labelRes =
                    when (tool) {
                        CanvasTool.Crop -> R.string.tool_crop
                        CanvasTool.Guide -> R.string.tool_guide
                        CanvasTool.Text -> R.string.tool_text
                        else -> R.string.tool_clone
                    }
                item(labelRes, enabled = document, checked = session.canvasTool == tool) {
                    onCommand(ToolCommandIds.TOOL_SELECT, mapOf("tool" to if (session.canvasTool == tool) "brush" else name))
                }
            }
            item(R.string.reference_button, enabled = document, checked = session.reference.settings.visible) {
                onCommand(ReferenceCommandIds.TOGGLE, emptyMap())
            }
            val animationOn = session.document?.animation?.enabled == true
            item(R.string.animation_button, enabled = document, checked = animationOn) {
                onCommand(AnimationCommandIds.SET_ENABLED, mapOf("enabled" to !animationOn))
            }
            HorizontalDivider()
            val recording = session.artwork?.info?.timelapse?.enabled == true
            item(R.string.command_timelapse_set_enabled, enabled = session.artwork != null, checked = recording) {
                onCommand(TimelapseCommandIds.SET_ENABLED, mapOf("enabled" to !recording))
            }
            item(R.string.command_timelapse_replay, enabled = session.artwork != null) { onCommand(TimelapseCommandIds.REPLAY, emptyMap()) }
            item(R.string.command_timelapse_export, enabled = session.artwork != null) {
                onCommand(TimelapseCommandIds.EXPORT, mapOf("length" to TimelapseLength.Full.id))
            }
            item(R.string.timelapse_export_short, enabled = session.artwork != null) {
                onCommand(TimelapseCommandIds.EXPORT, mapOf("length" to TimelapseLength.ThirtySeconds.id))
            }
            HorizontalDivider()
            item(R.string.canvas_info_title, enabled = session.artwork != null) {
                onCommand(CanvasInfoCommandIds.SHOW, mapOf("visible" to true))
            }
            item(R.string.macros_title) { onCommand(MacroCommandIds.PANEL, mapOf("visible" to true)) }
            if (BuildConfig.DEVELOPER_TOOLS) item(R.string.debug_panel_button, action = onToggleDebugPanel)
            item(R.string.settings_title, action = onOpenSettings)
        }
    }
}

private val ACTION_TOOLS =
    linkedMapOf("crop" to CanvasTool.Crop, "guide" to CanvasTool.Guide, "text" to CanvasTool.Text, "clone" to CanvasTool.Clone)

private val BAR_TOOLS = linkedMapOf("selection" to CanvasTool.Selection, "transform" to CanvasTool.Transform)

@Composable
fun ToolSliders(
    settings: ToolSettings,
    eyedropperArmed: Boolean,
    modifierHeld: Boolean,
    onModifierPress: (Boolean) -> Unit,
    onModifierTap: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    sizePresets: List<Float>,
    opacityPresets: List<Float>,
    onCommand: (String, Map<String, Any>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = LocalWindowLayout.current
    val sliderLength = if (layout.compact || layout.shortHeight) COMPACT_SLIDER_LENGTH else SLIDER_LENGTH
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
        Column(
            Modifier.padding(vertical = 12.dp).width(SLIDER_THICKNESS),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val sizeLabel = stringResource(R.string.tool_size)
                val sizeText = stringResource(R.string.tool_size_value, settings.limits.sizeAt(settings.sizeSlider).roundToInt())
                VerticalSlider(settings.sizeSlider, sizeLabel, sizeText, sliderLength) { slider ->
                    onCommand(ToolCommandIds.BRUSH_SET_SIZE, mapOf("sizePx" to settings.limits.sizeAt(slider)))
                }
                SliderPresets(SliderNames.SIZE, sizePresets, settings.sizeSlider, onCommand) { slider ->
                    onCommand(ToolCommandIds.BRUSH_SET_SIZE, mapOf("sizePx" to settings.limits.sizeAt(slider)))
                }
                val opacityLabel = stringResource(R.string.tool_opacity)
                val opacityText = stringResource(R.string.tool_opacity_value, (settings.limits.opacityAt(settings.opacitySlider) * 100).roundToInt())
                VerticalSlider(settings.opacitySlider, opacityLabel, opacityText, sliderLength) { slider ->
                    onCommand(ToolCommandIds.BRUSH_SET_OPACITY, mapOf("opacity" to settings.limits.opacityAt(slider)))
                }
                SliderPresets(SliderNames.OPACITY, opacityPresets, settings.opacitySlider, onCommand) { slider ->
                    onCommand(ToolCommandIds.BRUSH_SET_OPACITY, mapOf("opacity" to settings.limits.opacityAt(slider)))
                }
            }
            ModifierButton(selected = eyedropperArmed || modifierHeld, onPress = onModifierPress, onTap = onModifierTap)
            SideButton("↶", R.string.command_edit_undo, selected = false, enabled = canUndo) {
                onCommand(DocumentCommandIds.UNDO, emptyMap())
            }
            SideButton("↷", R.string.command_edit_redo, selected = false, enabled = canRedo) {
                onCommand(DocumentCommandIds.REDO, emptyMap())
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SliderPresets(
    slider: String,
    presets: List<Float>,
    current: Float,
    onCommand: (String, Map<String, Any>) -> Unit,
    onApply: (Float) -> Unit,
) {
    val addLabel = stringResource(R.string.slider_preset_add)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        presets.forEachIndexed { index, value ->
            val label = stringResource(R.string.slider_preset, (value * 100).roundToInt())
            val selected = kotlin.math.abs(value - current) < PRESET_MATCH
            Box(
                Modifier
                    .size(PRESET_SIZE)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    ).combinedClickable(
                        onClick = { onApply(value) },
                        onLongClick = { onCommand(BrushCommandIds.SLIDER_PRESET_REMOVE, mapOf("slider" to slider, "index" to index)) },
                    ).semantics { contentDescription = label },
            )
        }
        if (presets.size < BrushLibrary.MAXIMUM_SLIDER_PRESETS) {
            Text(
                "+",
                modifier =
                    Modifier
                        .clickable { onCommand(BrushCommandIds.SLIDER_PRESET_ADD, mapOf("slider" to slider)) }
                        .padding(4.dp)
                        .semantics { contentDescription = addLabel },
            )
        }
    }
}

@Composable
private fun SideButton(
    symbol: String,
    @StringRes labelRes: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(labelRes)
    val color =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        }
    Box(
        Modifier
            .size(SIDE_BUTTON_SIZE)
            .border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = color, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ModifierButton(
    selected: Boolean,
    onPress: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    val label = stringResource(R.string.tool_modifier_button)
    Box(
        Modifier
            .size(SIDE_BUTTON_SIZE)
            .border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPress(true)
                        tryAwaitRelease()
                        onPress(false)
                    },
                    onTap = { onTap() },
                )
            }.semantics {
                contentDescription = label
                role = Role.Button
                onClick {
                    onTap()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "◉",
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

private fun Modifier.colorDrop(session: CanvasSession): Modifier =
    composed {
        val scope = rememberCoroutineScope()
        var origin by remember { mutableStateOf(Offset.Zero) }
        var start by remember { mutableStateOf(Offset.Zero) }
        var last by remember { mutableStateOf(Offset.Zero) }
        var filling by remember { mutableStateOf(false) }
        var pauseJob by remember { mutableStateOf<Job?>(null) }

        fun far(point: Offset) = (point - start).getDistance() > DROP_MINIMUM_DISTANCE_PX

        fun beginAt(point: Offset): Boolean {
            val begun = session.beginThresholdDrag(point.x, point.y, automaticSelection = false, whole = session.canvasFingersDown > 0)
            if (begun) session.moveColorDrop(null)
            return begun
        }

        fun schedulePause(point: Offset) {
            pauseJob?.cancel()
            pauseJob =
                scope.launch {
                    delay(DROP_PAUSE_MILLIS)
                    if (!filling && far(point)) filling = beginAt(point)
                }
        }
        onGloballyPositioned { origin = it.positionInWindow() }.pointerInput(session) {
            detectDragGestures(
                onDragStart = { offset ->
                    filling = false
                    start = origin + offset
                    last = start
                    session.moveColorDrop(Point(start.x.toDouble(), start.y.toDouble()))
                },
                onDrag = { change, _ ->
                    last = origin + change.position
                    if (filling) {
                        session.updateThresholdDrag(last.x, last.y)
                    } else {
                        session.moveColorDrop(Point(last.x.toDouble(), last.y.toDouble()))
                        schedulePause(last)
                    }
                },
                onDragEnd = {
                    pauseJob?.cancel()
                    session.moveColorDrop(null)
                    val filled =
                        if (filling) {
                            session.endThresholdDrag(commit = true)
                            true
                        } else if (far(last) && beginAt(last)) {
                            session.endThresholdDrag(commit = true)
                            true
                        } else {
                            false
                        }
                    filling = false
                    if (filled) session.startContinueFilling()
                },
                onDragCancel = {
                    pauseJob?.cancel()
                    session.moveColorDrop(null)
                    if (filling) session.endThresholdDrag(commit = false)
                    filling = false
                },
            )
        }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Swatch(
    color: SrgbColor,
    selected: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    secondary: SrgbColor? = null,
    size: Dp = SWATCH_SIZE,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        modifier
            .size(size)
            .border(if (selected) 3.dp else 1.dp, outline, CircleShape)
            .padding(4.dp)
            .background(Color(color.red, color.green, color.blue), CircleShape)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        if (secondary != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(SECONDARY_DOT_SIZE)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .background(Color(secondary.red, secondary.green, secondary.blue), CircleShape),
            )
        }
    }
}

@Composable
private fun VerticalSlider(
    value: Float,
    description: String,
    valueText: String,
    length: Dp,
    onValueChange: (Float) -> Unit,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier =
            Modifier
                .semantics {
                    contentDescription = description
                    stateDescription = valueText
                }.graphicsLayer {
                    rotationZ = -90f
                    transformOrigin = TransformOrigin(0f, 0f)
                }.layout { measurable, constraints ->
                    val placeable =
                        measurable.measure(
                            Constraints(
                                minWidth = length.roundToPx(),
                                maxWidth = length.roundToPx(),
                                minHeight = constraints.minWidth,
                                maxHeight = constraints.maxWidth,
                            ),
                        )
                    layout(placeable.height, placeable.width) { placeable.place(-placeable.width, 0) }
                },
    )
}

private val SLIDER_LENGTH = 180.dp
private val COMPACT_SLIDER_LENGTH = 120.dp
private val PRESET_SIZE = 14.dp
private const val PRESET_MATCH = 0.005f
private val SLIDER_THICKNESS = 48.dp
private val SWATCH_SIZE = 40.dp
private val BAR_SWATCH_SIZE = 32.dp
private val BAR_BUTTON_SIZE = 40.dp
private val TOUCH_TARGET_SIZE = 48.dp
private val BAR_ICON_SIZE = 22.dp
private val SECONDARY_DOT_SIZE = 12.dp
private const val DROP_PAUSE_MILLIS = 400L
private const val DROP_MINIMUM_DISTANCE_PX = 60f
private val SIDE_BUTTON_SIZE = 48.dp
