package io.legado.app.ui.book.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookSourcePickerScreen(
    state: BookSourcePickerState,
    onSearch: (String) -> Unit,
    onSelect: (String) -> Unit,
    onCancel: () -> Unit,
    onOpenDelay: () -> Unit,
    onDelayDraft: (String) -> Unit,
    onDelayStep: (Int) -> Unit,
    onSaveDelay: () -> Unit,
    onCloseDelay: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    var query by
        rememberSaveable(stateSaver = TextFieldValue.Saver) {
            mutableStateOf(TextFieldValue(state.query, TextRange(state.query.length)))
        }
    LaunchedEffect(state.query) {
        if (query.text != state.query) query = TextFieldValue(state.query, query.selection)
    }
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().imePadding()) {
            TopAppBar(
                title = { Text(stringResource(R.string.book_source_picker_title)) },
                navigationIcon = {
                    TextButton(onCancel, enabled = !state.busy) {
                        Text(stringResource(R.string.cancel))
                    }
                },
                actions = {
                    Box {
                        IconButton(
                            { menu = true },
                            enabled = !state.busy && !state.finished,
                            modifier = Modifier.testTag("source-picker-menu"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert),
                                stringResource(R.string.menu),
                            )
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.change_source_delay)) },
                                onClick = {
                                    menu = false
                                    onOpenDelay()
                                },
                                modifier = Modifier.testTag("source-picker-delay-menu"),
                            )
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(),
            )
            OutlinedTextField(
                query,
                {
                    val textChanged = it.text != query.text
                    query = it
                    if (textChanged) onSearch(it.text)
                },
                singleLine = true,
                enabled = !state.busy && !state.finished,
                label = { Text(stringResource(R.string.search_book_source)) },
                modifier = Modifier.fillMaxWidth().padding(8.dp).testTag("source-picker-search"),
            )
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { error ->
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onRetry, enabled = !state.busy) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("source-picker-list")) {
                items(state.items, key = { it.url }) { item ->
                    Text(
                        item.displayName,
                        Modifier.fillMaxWidth()
                            .testTag("source-picker-row:${item.url}")
                            .clickable(enabled = !state.busy && !state.finished) {
                                onSelect(item.url)
                            }
                            .padding(16.dp),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    if (state.delayOpen) {
        var delay by
            rememberSaveable(stateSaver = TextFieldValue.Saver) {
                mutableStateOf(TextFieldValue(state.delayDraft, TextRange(state.delayDraft.length)))
            }
        LaunchedEffect(state.delayDraft) {
            if (delay.text != state.delayDraft) {
                delay = TextFieldValue(state.delayDraft, delay.selection)
            }
        }
        AlertDialog(
            onDismissRequest = onCloseDelay,
            title = { Text(stringResource(R.string.change_source_delay)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        delay,
                        {
                            val textChanged = it.text != delay.text
                            delay = it
                            // Selection/IME echoes do not constitute a new numeric draft.
                            if (textChanged) onDelayDraft(it.text)
                        },
                        singleLine = true,
                        enabled = !state.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = state.validDelay == null,
                        supportingText = { Text("0–9999") },
                        modifier = Modifier.testTag("source-picker-delay"),
                    )
                    Row {
                        TextButton(
                            { onDelayStep(-1) },
                            enabled = !state.busy && !state.delayLoading,
                        ) {
                            Text("−1")
                        }
                        TextButton(
                            { onDelayStep(1) },
                            enabled = !state.busy && !state.delayLoading,
                        ) {
                            Text("+1")
                        }
                    }
                    if (state.delayLoading || state.busy)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    onSaveDelay,
                    enabled = state.validDelay != null && !state.busy && !state.delayLoading,
                    modifier = Modifier.testTag("source-picker-delay-save"),
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onCloseDelay,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("source-picker-delay-cancel"),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
