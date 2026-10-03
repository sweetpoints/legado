package io.legado.app.ui.book.explore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.CoverRequest
import io.legado.app.data.repository.ExploreResultsCategory
import io.legado.app.data.repository.ExploreResultsRow
import io.legado.app.data.repository.exploreResultsInShelf
import io.legado.app.ui.components.cover.ComposeCover

class ExploreResultsActions(
    val close: () -> Unit,
    val categories: () -> Unit,
    val category: (ExploreResultsCategory) -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val retry: () -> Unit,
    val showPage: () -> Unit,
    val page: (Int) -> Unit,
    val cancelPage: () -> Unit,
    val confirmPage: () -> Unit,
    val askAdd: () -> Unit,
    val cancelAdd: () -> Unit,
    val confirmAdd: () -> Unit,
    val detail: (String) -> Unit,
)

/** A result list, preserving the source-defined categories and original page actions. */
@Composable
fun ExploreResultsScreen(
    state: ExploreResultsState,
    actions: ExploreResultsActions,
    listState: LazyListState = rememberLazyListState(),
    cover: @Composable (ExploreResultsRow, Boolean) -> Unit = { row, wifiOnly ->
        ComposeCover(
            CoverRequest(row.coverUrl, row.name, row.author, wifiOnly, row.origin),
            Modifier.width(72.dp).height(96.dp),
        )
    },
) {
    val checkpoint = state.checkpoint
    var menu by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    actions.close,
                    enabled = !state.adding,
                    modifier = Modifier.testTag("explore-results-back"),
                ) {
                    Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                }
                Text(
                    checkpoint?.selectedCategory?.title.orEmpty(),
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(
                    actions.showPage,
                    enabled = state.loaded,
                    modifier = Modifier.testTag("explore-results-page"),
                ) {
                    Text(stringResource(R.string.menu_page, checkpoint?.displayedPage ?: 1))
                }
                Box {
                    IconButton(
                        { menu = true },
                        modifier = Modifier.testTag("explore-results-menu"),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_more_vert),
                            stringResource(R.string.more_menu),
                        )
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.show_explore_categories)) },
                            leadingIcon = { Checkbox(state.showCategories, null) },
                            enabled = state.loaded && !state.changingCategories,
                            onClick = {
                                menu = false
                                actions.categories()
                            },
                            modifier = Modifier.testTag("explore-results-show-categories"),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.add_loaded_books_to_bookshelf)) },
                            enabled = state.loaded && !state.adding,
                            onClick = {
                                menu = false
                                actions.askAdd()
                            },
                            modifier = Modifier.testTag("explore-results-add-loaded"),
                        )
                    }
                }
            }
            if (state.showCategories) {
                splitExploreCategoryRows(checkpoint?.categories.orEmpty()).forEachIndexed {
                    rowIndex,
                    categories ->
                    LazyRow(Modifier.fillMaxWidth().testTag("explore-category-row-$rowIndex")) {
                        items(categories) { category ->
                            val selected = category == checkpoint?.selectedCategory
                            TextButton(
                                { actions.category(category) },
                                Modifier.heightIn(min = 48.dp)
                                    .testTag(
                                        "explore-category-${checkpoint?.categories?.indexOf(category)}"
                                    )
                                    .semantics { this.selected = selected },
                            ) {
                                Text(
                                    category.title,
                                    color =
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            if (!state.loaded) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (state.loading) CircularProgressIndicator()
                    if (state.missingSource) Text(stringResource(R.string.error_no_source))
                    else state.loadError?.let { Text(it) }
                    if (!state.loading)
                        TextButton(
                            actions.retry,
                            modifier = Modifier.testTag("explore-results-retry"),
                        ) {
                            Text(stringResource(R.string.retry))
                        }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier.weight(1f).navigationBarsPadding().testTag("explore-results-list"),
                ) {
                    // Keep one stable header slot. Saved row indices remain independent of whether
                    // a previous-page action is currently visible.
                    item(key = "previous-page") {
                        if ((checkpoint?.firstPage ?: 1) > 1) {
                            ExplorePageAction(
                                state.loadingPrevious,
                                checkpoint?.topError,
                                R.string.no_prev_page,
                                actions.previous,
                                "explore-results-previous",
                            )
                        } else Spacer(Modifier.height(0.dp))
                    }
                    items(state.rows, key = { it.key }) { row ->
                        ExploreBookRow(
                            row,
                            exploreResultsInShelf(row, state.membership),
                            state.loadCoverOnlyWifi,
                            { actions.detail(row.key) },
                            cover,
                        )
                        HorizontalDivider()
                    }
                    item(key = "next-page") {
                        when {
                            state.interruptedPage ->
                                ExplorePageAction(
                                    false,
                                    stringResource(R.string.retry),
                                    R.string.retry,
                                    actions.retry,
                                    "explore-results-next",
                                )
                            state.rows.isEmpty() &&
                                checkpoint?.error == null &&
                                !state.loadingNext ->
                                Text(stringResource(R.string.empty), Modifier.padding(24.dp))
                            else ->
                                ExplorePageAction(
                                    state.loadingNext,
                                    checkpoint?.error,
                                    if (checkpoint?.hasMore == false) R.string.no_next_page
                                    else R.string.loading,
                                    actions.next,
                                    "explore-results-next",
                                )
                        }
                    }
                    if (state.loadError != null)
                        item(key = "persistence-error") {
                            Text(state.loadError, Modifier.padding(16.dp))
                            TextButton(actions.retry) { Text(stringResource(R.string.retry)) }
                        }
                }
            }
        }
    }
    checkpoint?.addRows?.let { rows ->
        AlertDialog(
            onDismissRequest = { if (!state.adding) actions.cancelAdd() },
            title = { Text(stringResource(R.string.add_loaded_books_to_bookshelf)) },
            text = {
                Text(stringResource(R.string.add_loaded_books_to_bookshelf_message, rows.size))
            },
            confirmButton = {
                TextButton(
                    actions.confirmAdd,
                    enabled = !state.adding,
                    modifier = Modifier.testTag("explore-results-confirm-add"),
                ) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(
                    actions.cancelAdd,
                    enabled = !state.adding,
                    modifier = Modifier.testTag("explore-results-cancel-add"),
                ) {
                    Text(stringResource(R.string.no))
                }
            },
        )
    }
    state.pagePicker?.let { page ->
        var text by remember(page) { mutableStateOf(page.toString()) }
        AlertDialog(
            onDismissRequest = actions.cancelPage,
            title = { Text(stringResource(R.string.change_page)) },
            text = {
                Column {
                    OutlinedTextField(
                        text,
                        { value ->
                            if (value.length <= 3 && value.all(Char::isDigit)) {
                                text = value
                                value.toIntOrNull()?.takeIf { it in 1..999 }?.let(actions.page)
                            }
                        },
                        modifier = Modifier.testTag("explore-results-page-input"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    Row {
                        TextButton(
                            { actions.page((page - 1).coerceAtLeast(1)) },
                            enabled = page > 1,
                        ) {
                            Text("−")
                        }
                        TextButton(
                            { actions.page((page + 1).coerceAtMost(999)) },
                            enabled = page < 999,
                        ) {
                            Text("+")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    actions.confirmPage,
                    enabled = text.toIntOrNull()?.let { it in 1..999 } == true,
                    modifier = Modifier.testTag("explore-results-confirm-page"),
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(actions.cancelPage) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ExplorePageAction(
    loading: Boolean,
    error: String?,
    label: Int,
    click: () -> Unit,
    tag: String,
) {
    Column(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(tag)
            .clickable(enabled = !loading, onClick = click)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
        else Text(error ?: stringResource(label))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExploreBookRow(
    row: ExploreResultsRow,
    inShelf: Boolean,
    wifiOnly: Boolean,
    click: () -> Unit,
    cover: @Composable (ExploreResultsRow, Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .testTag("explore-book-${row.key}")
            .clickable(role = Role.Button, onClick = click)
            .padding(8.dp)
    ) {
        cover(row, wifiOnly)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (inShelf) Text("✓", Modifier.testTag("explore-in-shelf-${row.key}"))
            }
            Text(
                stringResource(R.string.author_show, row.author),
                style = MaterialTheme.typography.bodySmall,
            )
            if (row.kinds.isNotEmpty())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.kinds.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
            row.latestChapter
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    Text(
                        stringResource(R.string.lasted_show, it),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            Text(
                row.intro
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { stringResource(R.string.intro_show, it) }
                    ?: stringResource(R.string.intro_show_null),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
