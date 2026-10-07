package io.legado.app.ui.book.read

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EffectiveReplacementScreen(
    state: EffectiveReplacementState,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    onConversion: (Int) -> Unit,
    onPickerClose: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !state.loading && !state.busy && !state.finished
    Surface(modifier.fillMaxWidth()) {
        Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)) {
            TopAppBar(
                title = { Text(stringResource(R.string.effective_replaces)) },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(),
            )
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let {
                Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
            }
            if (state.error != null && state.rows.isEmpty())
                TextButton(onRetry) { Text(stringResource(R.string.retry)) }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false).testTag("effective-rule-list")
            ) {
                items(state.rows, key = { it.key }) { row ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            row.name,
                            Modifier.weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("effective-open-${row.key}")
                                .clickable(enabled = enabled) { onOpen(row.key) }
                                .padding(16.dp),
                        )
                        TextButton(
                            { onRemove(row.key) },
                            enabled = enabled,
                            modifier = Modifier.testTag("effective-remove-${row.key}"),
                        ) {
                            Text(stringResource(R.string.highlight_rule_disable))
                        }
                    }
                    HorizontalDivider()
                }
            }
            TextButton(
                onClose,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("effective-close"),
            ) {
                Text(stringResource(R.string.close))
            }
        }
    }
    if (state.conversionPicker && !state.finished)
        AlertDialog(
            onDismissRequest = onPickerClose,
            title = { Text(stringResource(R.string.chinese_converter)) },
            text = {
                Column {
                    stringArrayResource(R.array.chinese_mode).forEachIndexed { index, label ->
                        TextButton(
                            { onConversion(index) },
                            enabled = !state.busy,
                            modifier =
                                Modifier.fillMaxWidth().testTag("effective-conversion-$index"),
                        ) {
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onPickerClose) { Text(stringResource(R.string.cancel)) } },
        )
}
