package io.legado.app.ui.group

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R

@Composable
internal fun NamedGroupRoute(
    model: NamedGroupViewModel,
    close: () -> Unit,
    showDone: Boolean = false,
) {
    val state by model.state.collectAsStateWithLifecycle()
    BackHandler {
        if (!state.busy) {
            if (state.editing) model.cancelEdit() else close()
        }
    }
    NamedGroupScreen(
        state,
        model::add,
        model::edit,
        model::delete,
        model::name,
        model::confirm,
        model::cancelEdit,
        model::observe,
        close,
        showDone,
    )
}

@Composable
internal fun NamedGroupScreen(
    state: NamedGroupState,
    add: () -> Unit,
    edit: (String) -> Unit,
    delete: (String) -> Unit,
    name: (String) -> Unit,
    confirm: () -> Unit,
    cancelEdit: () -> Unit,
    retry: () -> Unit,
    close: () -> Unit,
    showDone: Boolean = false,
) {
    Surface {
        Column(Modifier.fillMaxSize().imePadding()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.group_manage),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TextButton(
                        add,
                        enabled = !state.loading && !state.busy,
                        modifier = Modifier.testTag("named-group-add"),
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                    ) {
                        Text(stringResource(R.string.add_group))
                    }
                }
            }
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { error ->
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        error,
                        Modifier.weight(1f).testTag("named-group-error"),
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (!state.editing)
                        TextButton(retry, enabled = !state.busy) {
                            Text(stringResource(R.string.retry))
                        }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("named-group-list")) {
                items(state.groups, key = { it }) { group ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(group, Modifier.weight(1f))
                        TextButton(
                            { edit(group) },
                            enabled = !state.busy,
                            modifier = Modifier.testTag("named-group-edit-$group"),
                        ) {
                            Text(stringResource(R.string.edit))
                        }
                        TextButton(
                            { delete(group) },
                            enabled = !state.busy,
                            modifier = Modifier.testTag("named-group-delete-$group"),
                        ) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                    HorizontalDivider()
                }
            }
            if (showDone)
                TextButton(
                    close,
                    enabled = !state.busy,
                    modifier = Modifier.align(Alignment.End).testTag("named-group-done"),
                ) {
                    Text(stringResource(R.string.ok))
                }
        }
    }
    if (state.editing) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(state.original) { focus.requestFocus() }
        AlertDialog(
            onDismissRequest = { if (!state.busy) cancelEdit() },
            title = {
                Text(
                    stringResource(
                        if (state.original == null) R.string.add_group else R.string.group_edit
                    )
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        state.name,
                        name,
                        Modifier.fillMaxWidth().focusRequester(focus).testTag("named-group-name"),
                        enabled = !state.busy,
                        label = { Text(stringResource(R.string.group_name)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (!state.busy) confirm() }),
                    )
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    confirm,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("named-group-confirm"),
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    cancelEdit,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("named-group-cancel"),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
