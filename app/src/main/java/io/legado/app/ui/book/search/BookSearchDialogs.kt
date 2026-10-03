package io.legado.app.ui.book.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.R

@Composable
internal fun BookSearchDialogs(state: BookSearchUiState, actions: BookSearchActions) {
    if (state.draft.filterDraft != null) {
        val text = state.draft.filterDraft
        var input by remember {
            mutableStateOf(TextFieldValue(text, TextRange(state.draft.filterSelection)))
        }
        LaunchedEffect(text, state.draft.filterSelection) {
            val selection = TextRange(state.draft.filterSelection)
            input =
                if (input.text == text) input.copy(selection = selection)
                else TextFieldValue(text, selection)
        }
        AlertDialog(
            modifier = Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.9f),
            onDismissRequest = actions.dismissFilter,
            title = { Text(stringResource(R.string.search_result_filter)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextField(
                        value = input,
                        onValueChange = { value ->
                            input = value
                            actions.filter(value.text, value.selection.start)
                        },
                        modifier = Modifier.fillMaxWidth().testTag("search-filter-input"),
                        enabled = !state.settingsBusy,
                        minLines = 4,
                        maxLines = 8,
                        placeholder = { Text(stringResource(R.string.search_result_filter_hint)) },
                    )
                    state.settingsError?.let { Text(it) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = actions.confirmFilter,
                    enabled = !state.settingsBusy,
                    modifier = Modifier.testTag("search-filter-confirm"),
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = actions.dismissFilter) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (state.draft.clearHistoryConfirmation) {
        AlertDialog(
            onDismissRequest = { actions.clearHistoryPrompt(false) },
            title = { Text(stringResource(R.string.draw)) },
            text = { Text(stringResource(R.string.sure_clear_search_history)) },
            confirmButton = {
                TextButton(
                    onClick = actions.clearHistory,
                    modifier = Modifier.testTag("search-history-confirm"),
                ) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { actions.clearHistoryPrompt(false) }) {
                    Text(stringResource(R.string.no))
                }
            },
        )
    }
    if (state.draft.emptyScopeConfirmation) {
        AlertDialog(
            onDismissRequest = actions.dismissEmpty,
            title = { Text(stringResource(R.string.book_search_scope_empty)) },
            text = {
                Text(
                    stringResource(
                        if (state.preferences.precision) R.string.book_search_disable_precision
                        else R.string.book_search_use_all_sources,
                        state.draft.scope.substringBefore("::"),
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = actions.confirmEmpty,
                    enabled = !state.settingsBusy,
                    modifier = Modifier.testTag("search-empty-confirm"),
                ) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(onClick = actions.dismissEmpty) { Text(stringResource(R.string.no)) }
            },
        )
    }
}
