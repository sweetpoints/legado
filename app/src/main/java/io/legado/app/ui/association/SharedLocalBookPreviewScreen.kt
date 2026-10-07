package io.legado.app.ui.association

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.SharedLocalBookPreviewRow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedLocalBookPreviewScreen(
    state: SharedLocalBookPreviewState,
    toggle: (String) -> Unit,
    selectAll: () -> Unit,
    directory: () -> Unit,
    confirm: () -> Unit,
    cancel: () -> Unit,
    retry: () -> Unit,
    position: Pair<Int, Int>,
    scrolled: (Int, Int) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)
                .testTag("shared-local-books")
        ) {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.local_book),
                        Modifier,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    Box {
                        IconButton(
                            onClick = { menu = true },
                            enabled = state.canAct,
                            modifier = Modifier.testTag("shared-local-menu"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert),
                                stringResource(R.string.menu),
                            )
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.local_book_save_path)) },
                                onClick = {
                                    menu = false
                                    directory()
                                },
                                enabled = state.canAct,
                                modifier = Modifier.testTag("shared-local-directory"),
                            )
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
            if (state.loading || state.busy)
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("shared-local-working"))
            state.error?.let {
                Text(
                    it,
                    Modifier.padding(16.dp).testTag("shared-local-error"),
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(
                    onClick = retry,
                    enabled = !state.loading && !state.busy,
                    modifier = Modifier.testTag("shared-local-retry"),
                ) {
                    Text(stringResource(R.string.retry))
                }
            }
            // Create list state after the source's restored staged batch has been projected.
            if (state.loaded && !state.loading) {
                val list = rememberLazyListState(position.first, position.second)
                val save by rememberUpdatedState(scrolled)
                LaunchedEffect(list) {
                    snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
                        .distinctUntilChanged()
                        .collect { (index, offset) -> save(index, offset) }
                }
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f, fill = false).testTag("shared-local-list"),
                    state = list,
                ) {
                    items(state.rows, key = { it.id }) { row ->
                        SharedLocalBookPreviewRowContent(
                            row,
                            row.id in state.selected,
                            state.canAct,
                        ) {
                            toggle(row.id)
                        }
                        HorizontalDivider()
                    }
                }
            } else Spacer(Modifier.height(24.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = selectAll,
                    enabled = state.canAct,
                    modifier = Modifier.weight(1f).testTag("shared-local-select-all"),
                ) {
                    Text(
                        stringResource(
                            if (state.selected.size == state.selectableCount)
                                R.string.select_cancel_count
                            else R.string.select_all_count,
                            state.selected.size,
                            state.rows.size,
                        )
                    )
                }
                TextButton(
                    onClick = cancel,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("shared-local-cancel"),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(
                    onClick = confirm,
                    enabled = state.canAct && state.selected.isNotEmpty(),
                    modifier = Modifier.testTag("shared-local-confirm"),
                ) {
                    Text(stringResource(R.string.add_to_bookshelf))
                }
            }
        }
    }
}

@Composable
private fun SharedLocalBookPreviewRowContent(
    row: SharedLocalBookPreviewRow,
    selected: Boolean,
    enabled: Boolean,
    click: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 60.dp)
            .toggleable(
                selected,
                enabled = enabled && row.selectable,
                role = Role.Checkbox,
                onValueChange = { click() },
            )
            .testTag("shared-local-row-${row.id}")
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
            when {
                row.directory -> Icon(painterResource(R.drawable.ic_folder), null)
                row.onBookshelf -> Icon(painterResource(R.drawable.ic_book_has), null)
                else -> Checkbox(selected, onCheckedChange = null, enabled = enabled)
            }
        }
        Column(Modifier.weight(1f).padding(vertical = 5.dp)) {
            Text(
                row.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!row.directory)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(15.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            row.format,
                            Modifier.padding(horizontal = 5.dp)
                                .testTag("shared-local-format-${row.id}"),
                            maxLines = 1,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(row.size, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    Text(
                        row.date,
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
        }
    }
}
