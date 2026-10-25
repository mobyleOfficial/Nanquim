package com.mobyle.nanquim.ui.canvas

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.command.CanvasCommandIds
import com.mobyle.nanquim.command.DocumentCommandIds
import kotlin.math.roundToInt

@Composable
fun CopyPasteMenu(
    session: CanvasSession,
    onCommand: (String, Map<String, Any?>) -> Unit,
) {
    val at = session.copyPasteMenu ?: return
    val close = { onCommand(CanvasCommandIds.VIEW_COPY_PASTE_MENU, emptyMap()) }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { close() } })
    val placement =
        Modifier.layout { measurable, constraints ->
            val menu = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(constraints.maxWidth, constraints.maxHeight) {
                val gap = MENU_GAP.roundToPx()
                val below = at.y.roundToInt() + gap
                val y = if (below + menu.height <= constraints.maxHeight) below else at.y.roundToInt() - gap - menu.height
                val x = (at.x.roundToInt() - menu.width / 2).coerceIn(0, (constraints.maxWidth - menu.width).coerceAtLeast(0))
                menu.place(x, y.coerceAtLeast(0))
            }
        }
    Surface(modifier = placement, shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp, shadowElevation = 4.dp) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for ((command, title) in ENTRIES) {
                TextButton(onClick = {
                    close()
                    onCommand(command, emptyMap())
                }) { Text(stringResource(title)) }
            }
        }
    }
}

private val ENTRIES =
    listOf(
        DocumentCommandIds.EDIT_CUT to R.string.command_edit_cut,
        DocumentCommandIds.EDIT_COPY to R.string.command_edit_copy,
        DocumentCommandIds.EDIT_COPY_MERGED to R.string.command_edit_copy_merged,
        DocumentCommandIds.EDIT_PASTE to R.string.command_edit_paste,
        DocumentCommandIds.SELECTION_COPY_PASTE to R.string.command_selection_copy_paste,
    )

private val MENU_GAP = 24.dp
