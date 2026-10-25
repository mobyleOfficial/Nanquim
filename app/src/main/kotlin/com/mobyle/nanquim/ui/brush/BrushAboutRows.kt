package com.mobyle.nanquim.ui.brush

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.mobyle.nanquim.R
import com.mobyle.nanquim.brush.BrushAbout
import com.mobyle.nanquim.brush.BrushEditorSession
import com.mobyle.nanquim.command.BrushCommandIds
import com.mobyle.nanquim.document.brush.BrushDocument
import com.mobyle.nanquim.document.brush.BrushResetPoints

private val AUTHOR_PREVIEW_SIZE = 96.dp
private const val SIGNATURE_STROKE_DP = 3f
private val EMPTY_ARGUMENTS = JsonObject(emptyMap())

@Composable
fun AboutRow(
    field: AboutField,
    label: String,
    editor: BrushEditorSession,
    document: BrushDocument,
    onCommand: (String, JsonObject) -> Unit,
) {
    when (field.part) {
        AboutPart.CreatedAt -> Text("$label: ${document.createdAt?.substringBefore('T') ?: "—"}")
        AboutPart.AuthorPicture -> AuthorPictureRow(label, editor, onCommand)
        AboutPart.Signature -> SignatureRow(label, editor, onCommand)
        AboutPart.ResetPoints -> ResetPointsRow(document, onCommand)
    }
}

@Composable
private fun AuthorPictureRow(
    label: String,
    editor: BrushEditorSession,
    onCommand: (String, JsonObject) -> Unit,
) {
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                onCommand(BrushCommandIds.EDITOR_IMPORT_AUTHOR_PICTURE, JsonObject(mapOf("uri" to JsonPrimitive(uri.toString()))))
            }
        }
    val bytes = editor.working.files[BrushAbout.AUTHOR_FILE]
    val image = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(AUTHOR_PREVIEW_SIZE).border(1.dp, MaterialTheme.colorScheme.outline)) {
                if (image != null) Image(image, contentDescription = label, modifier = Modifier.fillMaxSize())
            }
            OutlinedButton(onClick = { picker.launch("image/*") }) { Text(stringResource(R.string.brush_texture_import)) }
            if (bytes != null) {
                TextButton(onClick = { onCommand(BrushCommandIds.EDITOR_REMOVE_AUTHOR_PICTURE, EMPTY_ARGUMENTS) }) {
                    Text(stringResource(R.string.brush_texture_remove))
                }
            }
        }
    }
}

@Composable
private fun SignatureRow(
    label: String,
    editor: BrushEditorSession,
    onCommand: (String, JsonObject) -> Unit,
) {
    val strokes = remember(editor.brushId) { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val bytes = editor.working.files[BrushAbout.SIGNATURE_FILE]
    val saved = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }

    fun send() {
        val json =
            JsonArray(
                strokes.map { stroke ->
                    JsonArray(stroke.map { JsonArray(listOf(JsonPrimitive(it.x), JsonPrimitive(it.y))) })
                },
            )
        onCommand(BrushCommandIds.EDITOR_SET_SIGNATURE, JsonObject(mapOf("strokes" to json)))
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                strokes.clear()
                send()
            }) { Text(stringResource(R.string.brush_signature_clear)) }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(BrushAbout.SIGNATURE_WIDTH.toFloat() / BrushAbout.SIGNATURE_HEIGHT)
                .background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outline)
                .semantics { contentDescription = label },
        ) {
            if (saved != null && strokes.isEmpty()) Image(saved, contentDescription = null, modifier = Modifier.fillMaxSize())
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(editor.brushId) {
                        fun normalized(offset: Offset) = Offset(offset.x / size.width, offset.y / size.height)
                        detectDragGestures(
                            onDragStart = { current = listOf(normalized(it)) },
                            onDrag = { change, _ -> current = current + normalized(change.position) },
                            onDragEnd = {
                                strokes.add(current)
                                current = emptyList()
                                send()
                            },
                        )
                    },
            ) {
                val width = SIGNATURE_STROKE_DP.dp.toPx()
                for (stroke in strokes + listOf(current)) {
                    if (stroke.isEmpty()) continue
                    val path = Path()
                    stroke.forEachIndexed { index, point ->
                        val x = point.x * size.width
                        val y = point.y * size.height
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, Color.Black, style = Stroke(width, cap = StrokeCap.Round))
                }
            }
        }
    }
}

@Composable
private fun ResetPointsRow(
    document: BrushDocument,
    onCommand: (String, JsonObject) -> Unit,
) {
    val points = BrushResetPoints.list(document)
    var name by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (points.isEmpty()) Text(stringResource(R.string.brush_reset_points_none))
        points.forEachIndexed { index, point ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${point.name} · ${point.createdAt.substringBefore('T')}", modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    onCommand(BrushCommandIds.EDITOR_RESTORE_RESET_POINT, JsonObject(mapOf("index" to JsonPrimitive(index))))
                }) { Text(stringResource(R.string.brush_reset_point_restore)) }
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.brush_reset_point_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = name.isNotBlank(), onClick = {
                onCommand(BrushCommandIds.EDITOR_CREATE_RESET_POINT, JsonObject(mapOf("name" to JsonPrimitive(name.trim()))))
                name = ""
            }) { Text(stringResource(R.string.brush_reset_point_create)) }
            TextButton(onClick = { onCommand(BrushCommandIds.EDITOR_RESET_ALL, EMPTY_ARGUMENTS) }) {
                Text(stringResource(R.string.brush_reset_all))
            }
        }
    }
}
