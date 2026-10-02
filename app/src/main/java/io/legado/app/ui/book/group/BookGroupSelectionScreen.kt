package io.legado.app.ui.book.group

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.BookGroupEditorSnapshot
import io.legado.app.ui.components.LegadoTopAppBar

class BookGroupSelectionActions(val close: () -> Unit, val add: () -> Unit,
    val edit: (BookGroupEditorSnapshot) -> Unit, val checked: (Long, Boolean) -> Unit,
    val move: (Long, Long) -> Unit, val finish: () -> Unit, val cancel: () -> Unit, val retry: () -> Unit, val confirm: () -> Unit)

@Composable
fun BookGroupSelectionScreen(state: BookGroupSelectionUiState, actions: BookGroupSelectionActions, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    val latest by rememberUpdatedState(actions)
    val stable = remember { BookGroupSelectionActions({ latest.close() }, { latest.add() }, { latest.edit(it) },
        { id, shown -> latest.checked(id, shown) }, { from, to -> latest.move(from, to) }, { latest.finish() }, { latest.cancel() }, { latest.retry() }, { latest.confirm() }) }
    val drag = rememberBookGroupSelectionDrag(list, stable)
    val up = stringResource(R.string.book_group_selection_move_up); val down = stringResource(R.string.book_group_selection_move_down)
    val enabled = !state.busy && !state.loading && !state.finished
    Surface(modifier.fillMaxSize()) {
        Scaffold(topBar = {
            LegadoTopAppBar(stringResource(R.string.group_select), actions.close, actions = {
                IconButton(actions.add, enabled = enabled, modifier = Modifier.testTag("book-group-selection-add")) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.add_group)) }
            })
        }, bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalArrangement = Arrangement.End) {
                    TextButton(actions.close, enabled = !state.busy && !state.finished, modifier = Modifier.testTag("book-group-selection-cancel")) { Text(stringResource(R.string.cancel)) }
                    TextButton(actions.confirm, enabled = !state.busy && !state.finished, modifier = Modifier.testTag("book-group-selection-confirm")) { Text(stringResource(R.string.ok)) }
                }
            }
        }) { padding ->
            Column(Modifier.padding(padding)) {
                if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { error -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f).padding(12.dp).testTag("book-group-selection-error"))
                    TextButton(actions.retry) { Text(stringResource(R.string.retry)) }
                } }
                LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("book-group-selection-list")) {
                    itemsIndexed(state.groups, key = { _, group -> group.id }) { index, group ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("book-group-selection-row-${group.id}"), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(state.checked(group.id), { actions.checked(group.id, it) }, enabled = enabled,
                                modifier = Modifier.semantics { contentDescription = group.name }.testTag("book-group-selection-check-${group.id}"))
                            Text(group.name, modifier = Modifier.weight(1f).padding(vertical = 16.dp)
                                .bookGroupSelectionReorder(group.id, list, drag, enabled, stable)
                                .clickable(enabled = enabled) { actions.checked(group.id, !state.checked(group.id)) }
                                .semantics { customActions = buildList {
                                    if (enabled && index > 0) add(CustomAccessibilityAction(up) { actions.move(group.id, state.groups[index - 1].id); actions.finish(); true })
                                    if (enabled && index < state.groups.lastIndex) add(CustomAccessibilityAction(down) { actions.move(group.id, state.groups[index + 1].id); actions.finish(); true })
                                } }.testTag("book-group-selection-name-${group.id}"))
                            TextButton({ actions.edit(group) }, enabled = enabled, modifier = Modifier.testTag("book-group-selection-edit-${group.id}")) { Text(stringResource(R.string.edit)) }

                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
