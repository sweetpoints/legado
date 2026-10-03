package io.legado.app.ui.book.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun BookSearchInputHelp(state: BookSearchUiState, actions: BookSearchActions) {
    LazyColumn(Modifier.fillMaxWidth().testTag("search-input-help")) {
        if (state.suggestions.isNotEmpty()) {
            item("shelf-title") { Text(stringResource(R.string.bookshelf), Modifier.padding(8.dp)) }
            item("shelf-items") {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.suggestions.forEach { suggestion ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = suggestion.name,
                                modifier =
                                    Modifier.heightIn(min = 48.dp)
                                        .combinedClickable(
                                            onClick = { actions.suggestion(suggestion.bookId) }
                                        )
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        item("history-title") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.searchHistory), Modifier.weight(1f))
                if (state.history.isNotEmpty()) {
                    TextButton(
                        onClick = { actions.clearHistoryPrompt(true) },
                        modifier = Modifier.testTag("search-clear-history"),
                    ) {
                        Text(stringResource(R.string.clear))
                    }
                }
            }
        }
        item("history-items") {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.history.forEach { history ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = history.word,
                            modifier =
                                Modifier.heightIn(min = 48.dp)
                                    .combinedClickable(
                                        onClick = { actions.history(history.word) },
                                        onLongClick = { actions.deleteHistory(history.word) },
                                    )
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
