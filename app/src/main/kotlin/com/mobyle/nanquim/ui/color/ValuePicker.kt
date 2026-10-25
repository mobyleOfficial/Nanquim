package com.mobyle.nanquim.ui.color

import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.mobyle.nanquim.R
import com.mobyle.nanquim.document.color.CmykConversion
import com.mobyle.nanquim.document.color.CmykInks
import com.mobyle.nanquim.document.color.ColorComponent
import com.mobyle.nanquim.document.color.InkChannel
import com.mobyle.nanquim.document.color.ColorValue

@Composable
fun ValuePicker(
    color: ColorValue,
    onChange: (ColorValue) -> Unit,
    modifier: Modifier = Modifier,
    cmyk: CmykConversion? = null,
    inks: CmykInks? = null,
    onInks: (CmykInks) -> Unit = {},
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (component in ColorComponent.HSB + ColorComponent.RGB) {
            val label = stringResource(component.titleRes)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(LABEL_WIDTH), maxLines = 1)
                ColorSlider(component, color, label, onChange, Modifier.weight(1f))
                ValueField(
                    shown = component.displayValue(color).toString(),
                    description = stringResource(R.string.color_component_field, label),
                    keyboardType = KeyboardType.Decimal,
                    parse = { text -> component.parse(text, color) },
                    onChange = onChange,
                    modifier = Modifier.width(NUMBER_FIELD_WIDTH),
                )
            }
        }
        if (cmyk != null && inks != null) {
            for (channel in InkChannel.entries) {
                val label = stringResource(channel.titleRes)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(LABEL_WIDTH), maxLines = 1)
                    InkSlider(channel, inks, color, cmyk, label, onInks, Modifier.weight(1f))
                    ValueField(
                        shown = channel.percent(inks).toString(),
                        description = stringResource(R.string.color_component_field, label),
                        keyboardType = KeyboardType.Decimal,
                        parse = { text -> channel.parse(text, inks) },
                        onChange = onInks,
                        modifier = Modifier.width(NUMBER_FIELD_WIDTH),
                    )
                }
            }
        }
        HexRow(color, onChange)
    }
}

private val InkChannel.titleRes: Int
    get() =
        when (this) {
            InkChannel.Cyan -> R.string.color_ink_cyan
            InkChannel.Magenta -> R.string.color_ink_magenta
            InkChannel.Yellow -> R.string.color_ink_yellow
            InkChannel.Black -> R.string.color_ink_black
        }

@Composable
private fun HexRow(
    color: ColorValue,
    onChange: (ColorValue) -> Unit,
) {
    val label = stringResource(R.string.color_hex)
    val invalidMessage = stringResource(R.string.color_hex_invalid)
    var invalid by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(LABEL_WIDTH))
            ValueField(
                shown = color.toHex(),
                description = label,
                keyboardType = KeyboardType.Ascii,
                parse = { text -> ColorValue.parseHex(text, color) },
                onChange = onChange,
                onRefused = { invalid = true },
                onEdited = { invalid = false },
                modifier = Modifier.width(HEX_FIELD_WIDTH),
            )
            TextButton(
                onClick = {
                    scope.launch {
                        val text =
                            clipboard
                                .getClipEntry()
                                ?.clipData
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                        val pasted = text?.let { ColorValue.parseHex(it, color) }
                        if (pasted != null) onChange(pasted)
                        invalid = pasted == null
                    }
                },
            ) { Text(stringResource(R.string.color_paste)) }
        }
        if (invalid) Text(invalidMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun <T> ValueField(
    shown: String,
    description: String,
    keyboardType: KeyboardType,
    parse: (String) -> T?,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    onRefused: () -> Unit = {},
    onEdited: () -> Unit = {},
) {
    var text by remember { mutableStateOf(shown) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(shown, focused) { if (!focused) text = shown }
    val focusManager = LocalFocusManager.current

    fun apply() {
        if (text == shown) return
        val parsed = parse(text)
        if (parsed != null) {
            onChange(parsed)
        } else {
            onRefused()
            text = shown
        }
    }
    BasicTextField(
        value = text,
        onValueChange = {
            text = it
            onEdited()
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Done,
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
            ),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier =
            modifier
                .onFocusChanged { state ->
                    if (focused && !state.isFocused) apply()
                    focused = state.isFocused
                }.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .heightIn(min = 48.dp)
                .wrapContentHeight()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .semantics { contentDescription = description },
    )
}

private val LABEL_WIDTH = 88.dp
private val NUMBER_FIELD_WIDTH = 56.dp
private val HEX_FIELD_WIDTH = 96.dp
