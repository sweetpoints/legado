package io.legado.app.ui.book.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.CoverRequest
import io.legado.app.ui.components.LegadoTopAppBar
import io.legado.app.ui.components.cover.ComposeCover

@Composable
fun BookGroupEditorScreen(
    state: BookGroupEditorUiState,
    onName: (String) -> Unit,
    onSort: (Int) -> Unit,
    onRefresh: (Boolean) -> Unit,
    onOnlyRead: (Boolean) -> Unit,
    onCover: () -> Unit,
    onSelectCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onCloseCoverMenu: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onRequestDelete: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val maximum = (LocalConfiguration.current.screenHeightDp * .85f).dp
    val enabled = !state.saving && !state.finished
    val sortNames = stringArrayResource(R.array.book_sort)
    var sortMenu by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth()) {
        Column(Modifier.heightIn(max = maximum).imePadding()) {
            LegadoTopAppBar(
                stringResource(if (state.editing) R.string.group_edit else R.string.add_group),
                onClose,
            )
            if (state.loading || state.saving || state.importingCover)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(
                Modifier.weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                state.error?.let { error ->
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("book-group-error"),
                    )
                    if (state.loadFailed)
                        TextButton(
                            onRetry,
                            enabled = enabled,
                            modifier = Modifier.testTag("book-group-retry"),
                        ) {
                            Text(stringResource(R.string.retry))
                        }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(90.dp, 126.dp)
                            .clickable(
                                enabled = enabled && !state.selectingCover,
                                onClick = onCover,
                            )
                            .testTag("book-group-cover")
                    ) {
                        ComposeCover(
                            CoverRequest(path = state.draft.cover),
                            Modifier.fillMaxSize(),
                            contentDescription = stringResource(R.string.img_cover),
                        )
                    }
                    OutlinedTextField(
                        state.draft.name,
                        onName,
                        enabled = enabled,
                        maxLines = 2,
                        label = { Text(stringResource(R.string.group_name)) },
                        modifier =
                            Modifier.weight(1f).padding(start = 12.dp).testTag("book-group-name"),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.sort), Modifier.padding(end = 8.dp))
                    Box {
                        TextButton(
                            { sortMenu = true },
                            enabled = enabled,
                            modifier = Modifier.testTag("book-group-sort"),
                        ) {
                            Text(sortNames[(state.draft.bookSort + 1).coerceIn(sortNames.indices)])
                        }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            sortNames.forEachIndexed { index, label ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        sortMenu = false
                                        onSort(index - 1)
                                    },
                                    modifier = Modifier.testTag("book-group-sort-${index - 1}"),
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        state.draft.enableRefresh,
                        onRefresh,
                        enabled = enabled,
                        modifier = Modifier.testTag("book-group-refresh"),
                    )
                    Text(
                        stringResource(R.string.allow_drop_down_refresh),
                        Modifier.clickable(enabled = enabled) {
                            onRefresh(!state.draft.enableRefresh)
                        },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        state.draft.onlyUpdateRead,
                        onOnlyRead,
                        enabled = enabled,
                        modifier = Modifier.testTag("book-group-only-read"),
                    )
                    Text(
                        stringResource(R.string.only_update_read),
                        Modifier.clickable(enabled = enabled) {
                            onOnlyRead(!state.draft.onlyUpdateRead)
                        },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.canDelete)
                    TextButton(
                        { onRequestDelete(true) },
                        enabled = enabled && !state.loading && !state.loadFailed,
                        modifier = Modifier.testTag("book-group-delete"),
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClose,
                    enabled = enabled,
                    modifier = Modifier.testTag("book-group-cancel"),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(
                    onSave,
                    enabled =
                        enabled &&
                            !state.loading &&
                            !state.loadFailed &&
                            !state.importingCover &&
                            !state.selectingCover,
                    modifier = Modifier.testTag("book-group-save"),
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        }
    }
    if (state.coverMenu)
        AlertDialog(
            onDismissRequest = onCloseCoverMenu,
            title = { Text(stringResource(R.string.img_cover)) },
            text = {
                Column {
                    TextButton(
                        onSelectCover,
                        modifier = Modifier.testTag("book-group-select-cover"),
                    ) {
                        Text(stringResource(R.string.select_image))
                    }
                    TextButton(
                        onRemoveCover,
                        modifier = Modifier.testTag("book-group-remove-cover"),
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                }
            },
            confirmButton = {
                TextButton(onCloseCoverMenu) { Text(stringResource(R.string.cancel)) }
            },
        )
    if (state.confirmDelete)
        AlertDialog(
            onDismissRequest = { onRequestDelete(false) },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.sure_del)) },
            confirmButton = {
                TextButton(
                    onDelete,
                    enabled = enabled,
                    modifier = Modifier.testTag("book-group-delete-confirm"),
                ) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(
                    { onRequestDelete(false) },
                    enabled = enabled,
                    modifier = Modifier.testTag("book-group-delete-cancel"),
                ) {
                    Text(stringResource(R.string.no))
                }
            },
        )
}
