package com.mobyle.nanquim.ui.help

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.input.CanvasTool
import com.mobyle.nanquim.help.Help
import com.mobyle.nanquim.help.WelcomeCard
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun WelcomeOverlay(help: Help) {
    val cards = WelcomeCard.entries
    val pager = rememberPagerState(initialPage = help.welcomeCard.coerceIn(0, cards.lastIndex)) { cards.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(help.welcomeCard) { if (pager.currentPage != help.welcomeCard) pager.animateScrollToPage(help.welcomeCard) }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { help.welcomeCard = it } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp).widthIn(max = 460.dp),
        ) {
            Column(Modifier.padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { page ->
                    CardContent(cards[page])
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cards.indices.forEach { index ->
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index ==
                                        pager.currentPage
                                    ) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                ),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = help::finishWelcome) { Text(stringResource(R.string.welcome_skip)) }
                    Spacer(Modifier.weight(1f))
                    val last = pager.currentPage == cards.lastIndex
                    Button(onClick = {
                        if (last) help.finishWelcome() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }) { Text(stringResource(if (last) R.string.welcome_done else R.string.welcome_next)) }
                }
            }
        }
    }
}

@Composable
private fun CardContent(card: WelcomeCard) {
    val (title, body) =
        when (card) {
            WelcomeCard.Navigation -> R.string.welcome_navigation_title to R.string.welcome_navigation_body
            WelcomeCard.Undo -> R.string.welcome_undo_title to R.string.welcome_undo_body
            WelcomeCard.Brushes -> R.string.welcome_brushes_title to R.string.welcome_brushes_body
            WelcomeCard.Layers -> R.string.welcome_layers_title to R.string.welcome_layers_body
            WelcomeCard.Color -> R.string.welcome_color_title to R.string.welcome_color_body
        }
    val titleText = stringResource(title)
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val ink = MaterialTheme.colorScheme.onSurface
        val accent = MaterialTheme.colorScheme.primary
        Canvas(Modifier.size(160.dp).semantics { contentDescription = titleText }) { illustrate(card, ink, accent) }
        Text(titleText, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp), textAlign = TextAlign.Center)
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp),
            textAlign = TextAlign.Center,
        )
    }
}

private fun DrawScope.illustrate(
    card: WelcomeCard,
    ink: Color,
    accent: Color,
) {
    val unit = size.minDimension / 10f
    val line = Stroke(width = unit * 0.35f, cap = StrokeCap.Round)
    val center = Offset(size.width / 2f, size.height / 2f)

    fun finger(at: Offset) = drawCircle(accent, radius = unit * 0.9f, center = at)
    when (card) {
        WelcomeCard.Navigation -> {
            val a = center + Offset(-unit * 1.6f, unit * 1.6f)
            val b = center + Offset(unit * 1.6f, -unit * 1.6f)
            finger(a)
            finger(b)
            arrow(a, a + Offset(-unit * 1.8f, unit * 1.8f), ink, line)
            arrow(b, b + Offset(unit * 1.8f, -unit * 1.8f), ink, line)
            drawArc(
                ink,
                startAngle = 200f,
                sweepAngle = 70f,
                useCenter = false,
                topLeft = center - Offset(unit * 4f, unit * 4f),
                size =
                    Size(unit * 8f, unit * 8f),
                style = line,
            )
        }
        WelcomeCard.Undo -> {
            finger(center + Offset(-unit * 1.2f, unit * 2f))
            finger(center + Offset(unit * 1.2f, unit * 2f))
            val path =
                Path().apply {
                    moveTo(center.x + unit * 3f, center.y - unit * 0.5f)
                    cubicTo(
                        center.x + unit * 2.5f,
                        center.y - unit * 4f,
                        center.x - unit * 2.5f,
                        center.y - unit * 4f,
                        center.x - unit * 3f,
                        center.y - unit * 1f,
                    )
                }
            drawPath(path, ink, style = line)
            val tip = Offset(center.x - unit * 3f, center.y - unit * 1f)
            drawLine(ink, tip, tip + Offset(-unit * 0.2f, -unit * 1.4f), strokeWidth = line.width, cap = StrokeCap.Round)
            drawLine(ink, tip, tip + Offset(unit * 1.3f, -unit * 0.5f), strokeWidth = line.width, cap = StrokeCap.Round)
        }
        WelcomeCard.Brushes -> {
            val stroke =
                Path().apply {
                    moveTo(unit * 1f, unit * 7f)
                    cubicTo(unit * 3f, unit * 3f, unit * 5f, unit * 9f, unit * 7.5f, unit * 4.5f)
                }
            drawPath(stroke, accent, style = Stroke(width = unit * 1.1f, cap = StrokeCap.Round))
            drawLine(
                ink,
                Offset(unit * 7.5f, unit * 4.5f),
                Offset(unit * 9.3f, unit * 1.2f),
                strokeWidth = unit * 0.7f,
                cap = StrokeCap.Round,
            )
        }
        WelcomeCard.Layers -> {
            for (index in 0..2) {
                val offset = Offset(unit * (1.2f + index * 1.1f), unit * (4.6f - index * 1.4f))
                val sheet =
                    Path().apply {
                        moveTo(offset.x, offset.y + unit * 1.5f)
                        lineTo(offset.x + unit * 2.6f, offset.y)
                        lineTo(offset.x + unit * 6f, offset.y + unit * 1.2f)
                        lineTo(offset.x + unit * 3.4f, offset.y + unit * 2.7f)
                        close()
                    }
                if (index == 2) drawPath(sheet, accent)
                drawPath(sheet, ink, style = line)
            }
        }
        WelcomeCard.Color -> {
            val hues = (0..6).map { Color.hsv((it * 60f) % 360f, 0.8f, 0.95f) }
            drawCircle(Brush.sweepGradient(hues, center), radius = unit * 4f, center = center)
            drawCircle(Color.White, radius = unit * 1.6f, center = center)
            val angle = -PI / 4
            val ring = center + Offset((cos(angle) * unit * 3f).toFloat(), (sin(angle) * unit * 3f).toFloat())
            drawCircle(ink, radius = unit * 0.7f, center = ring, style = Stroke(width = unit * 0.3f))
        }
    }
}

private fun DrawScope.arrow(
    from: Offset,
    to: Offset,
    color: Color,
    stroke: Stroke,
) {
    drawLine(color, from, to, strokeWidth = stroke.width, cap = StrokeCap.Round)
    val direction = (to - from) / (to - from).getDistance()
    val side = Offset(-direction.y, direction.x)
    val length = stroke.width * 2.5f
    drawLine(color, to, to - direction * length + side * length * 0.7f, strokeWidth = stroke.width, cap = StrokeCap.Round)
    drawLine(color, to, to - direction * length - side * length * 0.7f, strokeWidth = stroke.width, cap = StrokeCap.Round)
}

@Composable
fun ToolTipCard(
    tool: CanvasTool,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text =
        when (tool) {
            CanvasTool.Smudge -> R.string.tip_smudge
            CanvasTool.Eraser -> R.string.tip_eraser
            CanvasTool.Selection -> R.string.tip_selection
            CanvasTool.Transform -> R.string.tip_transform
            CanvasTool.Clone -> R.string.tip_clone
            CanvasTool.Crop -> R.string.tip_crop
            CanvasTool.Adjust -> R.string.tip_adjust
            CanvasTool.Guide -> R.string.tip_guide
            CanvasTool.Text -> R.string.tip_text
            else -> return
        }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
        modifier = modifier.widthIn(max = 520.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.tip_ok), color = MaterialTheme.colorScheme.inversePrimary) }
        }
    }
}
