package io.legado.app.ui.highlight.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.HighlightColors

@Composable
internal fun HighlightRuleColorPicker(
    draft: HighlightRuleColorDraft,
    onSave: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .6f).dp.coerceAtMost(440.dp)
    val presets = if (draft.withAlpha) HighlightColors.bg else HighlightColors.text
    var value by
        rememberSaveable(draft.channel, draft.color) {
            mutableIntStateOf(draft.color.takeIf { it != 0 } ?: presets.first())
        }
    var hex by
        rememberSaveable(draft.channel, draft.color) {
            mutableStateOf(colorHex(value, draft.withAlpha))
        }
    var error by rememberSaveable(draft.channel, draft.color) { mutableStateOf(false) }
    fun color(next: Int) {
        value = next
        hex = colorHex(next, draft.withAlpha)
        error = false
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.highlight_style)) },
        text = {
            Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
                Box(
                    Modifier.fillMaxWidth()
                        .height(48.dp)
                        .background(Color(value))
                        .testTag("highlight-rule-color-preview")
                )
                presets.toList().chunked(4).forEachIndexed { rowIndex, row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEachIndexed { index, preset ->
                            Box(
                                Modifier.weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("highlight-rule-color-preset-${rowIndex * 4 + index}")
                                    .semantics {
                                        contentDescription = colorHex(preset, draft.withAlpha)
                                    }
                                    .clickable { color(preset) }
                                    .padding(4.dp)
                                    .background(Color(preset))
                            )
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                OutlinedTextField(
                    hex,
                    { text ->
                        hex = text
                        val clean = text.removePrefix("#")
                        val parsed =
                            clean
                                .takeIf {
                                    (it.length == if (draft.withAlpha) 8 else 6) &&
                                        it.all { char -> char in "0123456789abcdefABCDEF" }
                                }
                                ?.toLongOrNull(16)
                        error = parsed == null
                        if (parsed != null)
                            value =
                                if (draft.withAlpha) parsed.toInt()
                                else parsed.toInt() or 0xff000000.toInt()
                    },
                    Modifier.fillMaxWidth().testTag("highlight-rule-color-hex"),
                    label = { Text(if (draft.withAlpha) "#AARRGGBB" else "#RRGGBB") },
                    singleLine = true,
                    isError = error,
                )
                val channels =
                    if (draft.withAlpha) listOf("A" to 24, "R" to 16, "G" to 8, "B" to 0)
                    else listOf("R" to 16, "G" to 8, "B" to 0)
                channels.forEach { (name, shift) ->
                    val channel = value ushr shift and 255
                    Text("$name: $channel")
                    Slider(
                        channel.toFloat(),
                        { next ->
                            color(
                                (value and (255 shl shift).inv()) or
                                    (next.toInt().coerceIn(0, 255) shl shift)
                            )
                        },
                        Modifier.fillMaxWidth().testTag("highlight-rule-color-$name"),
                        valueRange = 0f..255f,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                { onSave(value) },
                Modifier.testTag("highlight-rule-color-save"),
                enabled = !error,
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onCancel, Modifier.testTag("highlight-rule-color-cancel")) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun colorHex(value: Int, alpha: Boolean) =
    if (alpha) "#%08X".format(value) else "#%06X".format(value and 0xffffff)
