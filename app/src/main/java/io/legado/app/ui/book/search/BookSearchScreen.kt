package io.legado.app.ui.book.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.model.webBook.BookSearchScopeSelection
import kotlinx.coroutines.flow.collect

@Composable
internal fun BookSearchScreen(state: BookSearchUiState, actions: BookSearchActions) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    var input by remember { mutableStateOf(TextFieldValue()) }
    var firstReady by rememberSaveable { mutableStateOf(false) }
    val results = state.visibleResults
    val list = rememberLazyListState()
    val submit = {
        focus.clearFocus()
        keyboard?.hide()
        actions.submit()
    }
    LaunchedEffect(state.draft.query, state.draft.selectionStart, state.draft.selectionEnd) {
        val selection = TextRange(state.draft.selectionStart, state.draft.selectionEnd)
        // Preserve the IME composition range when only the selection/state echo changes.
        input =
            if (input.text == state.draft.query) input.copy(selection = selection)
            else TextFieldValue(state.draft.query, selection)
    }
    LaunchedEffect(state.ready) {
        if (state.ready && !firstReady) {
            firstReady = true
            if (state.draft.query.isBlank()) requester.requestFocus()
        }
    }
    LaunchedEffect(results.firstOrNull()?.id) {
        if (results.isNotEmpty()) list.scrollToItem(0)
    }
    LaunchedEffect(
        list,
        results.size,
        state.searching,
        state.draft.manualStop,
        state.draft.interrupted,
        state.draft.hasMore,
    ) {
        if (
            state.searching ||
                state.draft.manualStop ||
                state.draft.interrupted ||
                results.isEmpty()
        )
            return@LaunchedEffect
        snapshotFlow {
            val layout = list.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            last != null &&
                last.index == results.lastIndex &&
                last.offset + last.size <= layout.viewportEndOffset + 1
        }
            .collect { bottom -> if (bottom) actions.continueSearch(false) }
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = actions.close,
                        modifier = Modifier.testTag("search-back"),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                    TextField(
                        value = input,
                        onValueChange = { value ->
                            input = value
                            actions.query(value.text, value.selection.start, value.selection.end)
                        },
                        modifier =
                            Modifier.weight(1f)
                                .focusRequester(requester)
                                .onFocusChanged { actions.focus(it.isFocused) }
                                .testTag("search-query"),
                        enabled = state.ready,
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.search_book_key)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                    )
                    IconButton(
                        onClick = submit,
                        enabled = state.ready,
                        modifier = Modifier.testTag("search-submit"),
                    ) {
                        Icon(painterResource(R.drawable.ic_search), stringResource(R.string.search))
                    }
                    BookSearchMenu(state, actions)
                }
            },
            floatingActionButton = {
                val visible =
                    state.searching ||
                        (state.draft.submittedKey.isNotEmpty() &&
                            state.draft.hasMore &&
                            !state.draft.manualStop)
                if (visible && !state.draft.inputHelp) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (state.searching)
                            Text(
                                "${state.draft.searched}/${state.draft.total}",
                                Modifier.padding(bottom = 8.dp),
                            )
                        FloatingActionButton(
                            onClick = {
                                if (state.searching) actions.stop()
                                else actions.continueSearch(true)
                            },
                            modifier = Modifier.testTag("search-start-stop"),
                        ) {
                            Icon(
                                painterResource(
                                    if (state.searching) R.drawable.ic_stop_black_24dp
                                    else R.drawable.ic_play_24dp
                                ),
                                if (state.searching) stringResource(R.string.stop)
                                else stringResource(R.string.start),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.loading || state.settingsBusy)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (state.searching) {
                    LinearProgressIndicator(
                        progress = {
                            if (state.draft.total == 0) 0f
                            else
                                (state.draft.searched.toFloat() / state.draft.total).coerceIn(
                                    0f,
                                    1f,
                                )
                        },
                        modifier = Modifier.fillMaxWidth().testTag("search-progress"),
                    )
                }
                val errors =
                    listOfNotNull(
                            state.persistError,
                            state.metadataError,
                            state.settingsError,
                            state.commandError,
                        )
                        .distinct()
                if (errors.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            errors.joinToString("\n"),
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(
                            onClick = actions.retry,
                            modifier = Modifier.testTag("search-retry"),
                        ) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    LazyColumn(
                        state = list,
                        modifier = Modifier.fillMaxSize().testTag("search-results"),
                    ) {
                        items(results, key = { it.id }) { result ->
                            BookSearchResultRow(
                                result = result,
                                onShelf =
                                    state.membership.onShelf(
                                        result.bookUrl,
                                        result.name,
                                        result.author,
                                    ),
                                hasRead =
                                    state.preferences.showReadRecord &&
                                        state.membership.hasRead(result.name, result.author),
                                loadOnlyWifi = state.preferences.loadCoverOnlyWifi,
                                onClick = { actions.result(result.id) },
                            )
                        }
                    }
                    if (state.draft.inputHelp) {
                        Surface(Modifier.fillMaxSize()) { BookSearchInputHelp(state, actions) }
                    }
                }
            }
        }
    }
    BookSearchDialogs(state, actions)
}

@Composable
private fun BookSearchMenu(state: BookSearchUiState, actions: BookSearchActions) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val height = LocalConfiguration.current.screenHeightDp.dp * 0.7f
    val selection = BookSearchScopeSelection(state.draft.scope)
    Box {
        IconButton(
            onClick = {
                actions.openedMenu()
                expanded = true
            },
            modifier = Modifier.testTag("search-menu"),
        ) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.menu))
        }
        DropdownMenu(expanded, { expanded = false }, Modifier.heightIn(max = height)) {
            fun action(callback: () -> Unit): () -> Unit = {
                expanded = false
                callback()
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.precision_search)) },
                onClick = action(actions.precision),
                trailingIcon = { Checkbox(state.preferences.precision, null) },
                enabled = state.ready && !state.settingsBusy,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.show_search_read_record)) },
                onClick = action(actions.readRecords),
                trailingIcon = { Checkbox(state.preferences.showReadRecord, null) },
                enabled = state.ready && !state.settingsBusy,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.search_result_filter)) },
                onClick = action(actions.openFilter),
                enabled = state.ready,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.book_source_manage)) },
                onClick = action(actions.sources),
                enabled = state.ready,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.groups_or_source)) },
                onClick = action(actions.scope),
                enabled = state.ready,
            )
            if (selection.isSource) {
                DropdownMenuItem(
                    text = { Text(selection.names.first()) },
                    onClick = action { actions.group(selection.names.first()) },
                    trailingIcon = { Checkbox(true, null) },
                )
            }
            state.groups
                .filter { it in selection.names }
                .forEach { group ->
                    DropdownMenuItem(
                        text = { Text(group) },
                        onClick = action { actions.group(group) },
                        trailingIcon = { Checkbox(true, null) },
                    )
                }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.all_source)) },
                onClick = action(actions.allSources),
                trailingIcon = { Checkbox(selection.names.isEmpty(), null) },
            )
            state.groups
                .filter { it !in selection.names }
                .forEach { group ->
                    DropdownMenuItem(
                        text = { Text(group) },
                        onClick = action { actions.group(group) },
                    )
                }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.log)) },
                onClick = action(actions.log),
                enabled = state.ready,
            )
        }
    }
}
