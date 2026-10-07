package io.legado.app.ui.rss.subscription

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.legado.app.R
import io.legado.app.data.repository.RuleSubscriptionEditor

class RuleSubscriptionActions(
    val create: () -> Unit = {},
    val edit: (Long) -> Unit = {},
    val delete: (Long) -> Unit = {},
    val open: (Long) -> Unit = {},
    val name: (String) -> Unit = {},
    val url: (String) -> Unit = {},
    val type: (Int) -> Unit = {},
    val automatic: (Boolean) -> Unit = {},
    val interval: (String) -> Unit = {},
    val silent: (Boolean) -> Unit = {},
    val save: () -> Unit = {},
    val cancelEditor: () -> Unit = {},
    val retry: () -> Unit = {},
    val close: () -> Unit = {},
    val beginDrag: () -> Boolean = { false },
    val move: (Long, Long) -> Unit = { _, _ -> },
    val finishDrag: () -> Unit = {},
    val cancelDrag: () -> Unit = {},
)

@Composable
fun RuleSubscriptionScreen(
    state: RuleSubscriptionState,
    actions: RuleSubscriptionActions,
    modifier: Modifier = Modifier,
) {
    val types = stringArrayResource(R.array.rule_type)
    val enabled = state.loaded && !state.busy && !state.closed
    val list = rememberLazyListState()
    val drag = rememberRuleSubscriptionDrag(list, actions)
    val up = stringResource(R.string.rule_subscription_move_up)
    val down = stringResource(R.string.rule_subscription_move_down)
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = actions.close,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("subscription-back"),
                    ) {
                        Text(
                            stringResource(R.string.back),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        stringResource(R.string.rule_subscription),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TextButton(
                        onClick = actions.create,
                        enabled = enabled,
                        modifier = Modifier.testTag("subscription-add"),
                    ) {
                        Text(
                            stringResource(R.string.add),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            if (state.loading || state.busy)
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("subscription-loading"))
            if (state.issue != null && state.editor == null) SubscriptionError(state, actions.retry)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.rows.isEmpty() && !state.loading)
                    Text(
                        stringResource(R.string.rule_sub_empty_msg),
                        Modifier.align(Alignment.Center)
                            .padding(16.dp)
                            .testTag("subscription-empty"),
                    )
                LazyColumn(
                    Modifier.fillMaxSize().testTag("subscription-list"),
                    state = list,
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    itemsIndexed(state.rows, key = { _, row -> row.id }) { index, row ->
                        var menu by rememberSaveable(row.id) { mutableStateOf(false) }
                        Row(
                            Modifier.fillMaxWidth()
                                .ruleSubscriptionReorder(
                                    row.id,
                                    list,
                                    drag,
                                    enabled && state.editor == null,
                                    actions,
                                )
                                .clickable(enabled = enabled, onClick = { actions.open(row.id) })
                                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
                                .testTag("subscription-row-${row.id}")
                                .semantics {
                                    customActions = buildList {
                                        if (index > 0)
                                            add(
                                                CustomAccessibilityAction(up) {
                                                    if (actions.beginDrag()) {
                                                        actions.move(
                                                            row.id,
                                                            state.rows[index - 1].id,
                                                        )
                                                        actions.finishDrag()
                                                        true
                                                    } else false
                                                }
                                            )
                                        if (index < state.rows.lastIndex)
                                            add(
                                                CustomAccessibilityAction(down) {
                                                    if (actions.beginDrag()) {
                                                        actions.move(
                                                            row.id,
                                                            state.rows[index + 1].id,
                                                        )
                                                        actions.finishDrag()
                                                        true
                                                    } else false
                                                }
                                            )
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(row.name, style = MaterialTheme.typography.titleMedium)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = MaterialTheme.shapes.extraSmall,
                                    ) {
                                        Text(
                                            types.getOrElse(row.type) { types.first() },
                                            Modifier.padding(3.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                    Text(
                                        row.url,
                                        Modifier.weight(1f).padding(start = 8.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            TextButton(
                                onClick = { actions.edit(row.id) },
                                enabled = enabled,
                                modifier = Modifier.testTag("subscription-edit-${row.id}"),
                            ) {
                                Text(stringResource(R.string.edit))
                            }
                            Box {
                                IconButton(
                                    onClick = { menu = true },
                                    enabled = enabled,
                                    modifier = Modifier.testTag("subscription-menu-${row.id}"),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_more_vert),
                                        contentDescription = stringResource(R.string.more_menu),
                                    )
                                }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.delete),
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            menu = false
                                            actions.delete(row.id)
                                        },
                                        enabled = enabled,
                                        modifier =
                                            Modifier.testTag("subscription-delete-${row.id}"),
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
    state.editor?.let { editor -> SubscriptionEditor(editor, state, actions, types) }
}

@Composable
private fun SubscriptionError(state: RuleSubscriptionState, retry: () -> Unit) {
    val message =
        when (state.issue) {
            RuleSubscriptionIssue.EmptyUrl -> stringResource(R.string.null_url)
            RuleSubscriptionIssue.DuplicateUrl ->
                stringResource(R.string.url_already) + " (${state.error.orEmpty()})"
            RuleSubscriptionIssue.Missing -> stringResource(R.string.rule_subscription_missing)
            RuleSubscriptionIssue.Conflict -> stringResource(R.string.rule_subscription_conflict)
            else -> state.error ?: stringResource(R.string.error)
        }
    Column(Modifier.padding(horizontal = 16.dp).testTag("subscription-error")) {
        Text(message, color = MaterialTheme.colorScheme.error)
        if (!state.busy)
            TextButton(onClick = retry, modifier = Modifier.testTag("subscription-retry")) {
                Text(stringResource(R.string.retry))
            }
    }
}

@Composable
private fun SubscriptionEditor(
    editor: RuleSubscriptionEditor,
    state: RuleSubscriptionState,
    actions: RuleSubscriptionActions,
    types: Array<String>,
) {
    val enabled = !state.busy && !state.pendingSave
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .85f).dp
    Dialog(onDismissRequest = { if (!state.busy) actions.cancelEditor() }) {
        Surface(
            Modifier.fillMaxWidth()
                .heightIn(max = maxHeight)
                .imePadding()
                .testTag("subscription-editor"),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.rule_subscription),
                    style = MaterialTheme.typography.titleLarge,
                )
                Column(
                    Modifier.weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .testTag("subscription-editor-scroll")
                ) {
                    var typeMenu by rememberSaveable(editor.newId) { mutableStateOf(false) }
                    Box {
                        TextButton(
                            onClick = { typeMenu = true },
                            enabled = enabled,
                            modifier = Modifier.testTag("subscription-type"),
                        ) {
                            Text(stringResource(R.string.book_type) + " " + types[editor.type])
                        }
                        DropdownMenu(typeMenu, { typeMenu = false }) {
                            types.forEachIndexed { index, label ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        typeMenu = false
                                        actions.type(index)
                                    },
                                    enabled = enabled,
                                    modifier = Modifier.testTag("subscription-type-$index"),
                                )
                            }
                        }
                    }
                    SubscriptionInput(
                        editor.newId,
                        "subscription-name",
                        editor.name,
                        stringResource(R.string.name),
                        enabled,
                        actions.name,
                    )
                    SubscriptionInput(
                        editor.newId,
                        "subscription-url",
                        editor.url,
                        "URL",
                        enabled,
                        actions.url,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            editor.automatic,
                            actions.automatic,
                            enabled = enabled,
                            modifier = Modifier.testTag("subscription-auto"),
                        )
                        Text(stringResource(R.string.auto_update), Modifier.weight(1f))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            editor.silent,
                            actions.silent,
                            enabled = enabled && editor.silentEnabled,
                            modifier = Modifier.testTag("subscription-silent"),
                        )
                        Text(stringResource(R.string.silent_update), Modifier.weight(1f))
                    }
                    OutlinedTextField(
                        editor.interval,
                        { if (it.all(Char::isDigit)) actions.interval(it) },
                        enabled = enabled && editor.automatic,
                        label = {
                            Text(
                                stringResource(R.string.update_interval) +
                                    stringResource(R.string.time_hour)
                            )
                        },
                        singleLine = true,
                        keyboardOptions =
                            androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                        modifier = Modifier.fillMaxWidth().testTag("subscription-interval"),
                    )
                    if (state.pendingSave)
                        Text(
                            stringResource(R.string.rule_subscription_save_pending),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    if (state.issue != null) SubscriptionError(state, actions.retry)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = actions.cancelEditor,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("subscription-cancel"),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(
                        onClick = actions.save,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("subscription-save"),
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionInput(
    id: Long,
    tag: String,
    value: String,
    label: String,
    enabled: Boolean,
    changed: (String) -> Unit,
) {
    var start by rememberSaveable(id, tag) { mutableIntStateOf(value.length) }
    var end by rememberSaveable(id, tag) { mutableIntStateOf(value.length) }
    OutlinedTextField(
        TextFieldValue(
            value,
            TextRange(start.coerceIn(0, value.length), end.coerceIn(0, value.length)),
        ),
        {
            start = it.selection.start
            end = it.selection.end
            changed(it.text)
        },
        enabled = enabled,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().testTag(tag),
        maxLines = 5,
    )
}
