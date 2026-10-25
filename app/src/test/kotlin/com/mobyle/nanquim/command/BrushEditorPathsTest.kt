package com.mobyle.nanquim.command

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.mobyle.nanquim.ui.brush.BRUSH_EDITOR_FIELDS
import com.mobyle.nanquim.ui.brush.BrushPickerField
import com.mobyle.nanquim.ui.brush.ChoiceField
import com.mobyle.nanquim.ui.brush.CurveField
import com.mobyle.nanquim.ui.brush.NumberField
import com.mobyle.nanquim.ui.brush.TextField
import com.mobyle.nanquim.ui.brush.TextureField
import com.mobyle.nanquim.ui.brush.ToggleField

class BrushEditorPathsTest {
    @Test
    fun `every field the editor shows can be set by brush editor commands`() {
        val paths =
            BRUSH_EDITOR_FIELDS.values.flatten().mapNotNull { field ->
                when (field) {
                    is NumberField -> field.path
                    is ToggleField -> field.path
                    is ChoiceField -> field.path
                    is CurveField -> field.path
                    is TextField -> field.path
                    is BrushPickerField -> field.path
                    is TextureField -> field.slot.field
                    else -> null
                }
            }
        for (path in paths) assertTrue(isEditablePath(path), path)
    }

    @Test
    fun `identity fields cannot be edited`() {
        assertFalse(isEditablePath("id"))
        assertFalse(isEditablePath("format"))
        assertFalse(isEditablePath("resetPoints"))
    }
}

class BrushEditorRollFieldsTest {
    @Test
    fun `barrel roll fields show only when the stylus reports a roll`() {
        val section = com.mobyle.nanquim.brush.BrushEditorSection.Stylus

        fun rollPaths(reported: Boolean) =
            com.mobyle.nanquim.ui.brush
                .editorFields(section, reported)
                .filterIsInstance<ToggleField>()
                .filter { it.path.startsWith("roll.") }
        assertTrue(rollPaths(true).isNotEmpty())
        assertTrue(rollPaths(false).isEmpty())
    }
}
