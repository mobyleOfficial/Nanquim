package com.mobyle.nanquim.brush

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import com.mobyle.nanquim.brush.library.BrushLibrary
import com.mobyle.nanquim.brush.library.BrushTool
import com.mobyle.nanquim.canvas.SrgbColor
import com.mobyle.nanquim.settings.InputSettings

class BrushUiState(
    val library: BrushLibrary,
    val brushes: ActiveBrushes,
    val thumbnails: BrushThumbnails,
    val inputSettings: InputSettings,
    val scope: CoroutineScope,
) {
    var libraryTool: BrushTool? by mutableStateOf(null)

    var shownGroupId: String? by mutableStateOf(null)

    var previewBrushId: String? by mutableStateOf(null)

    var editor: BrushEditorSession? by mutableStateOf(null)
        private set

    suspend fun openEditor(
        brushId: String,
        tool: BrushTool,
        primaryColor: SrgbColor = SrgbColor.BLACK,
        secondaryColor: SrgbColor = SrgbColor.WHITE,
    ): BrushEditorSession {
        editor?.close()
        val brushPackage = library.loadPackage(brushId)
        val active = brushes[tool]?.takeIf { it.brushId == brushId }
        val session =
            BrushEditorSession(
                brushId = brushId,
                original = brushPackage,
                library = library,
                brushes = brushes,
                scope = scope,
                padSizePx = BrushEditorSession.padSizeFor(brushes, tool, brushId, brushPackage.document),
                padOpacity = active?.opacity ?: 1f,
                pressureCurve = inputSettings.pressureCurve.takeUnless { inputSettings.isLinear },
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
            )
        editor = session
        return session
    }

    fun closeEditor() {
        editor?.close()
        editor = null
    }
}
