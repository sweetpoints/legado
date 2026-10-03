package io.legado.app.ui.main.bookshelf.style1

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.main.bookshelf.components.BookshelfHeader
import io.legado.app.ui.main.bookshelf.components.BookshelfToolbar
import io.legado.app.ui.theme.LocalLegadoColors
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookshelfHomeScreen(
    state: BookshelfHomeState,
    onSelect: (Long) -> Unit,
    onReselect: (Long) -> Unit,
    onGroupInfo: (Long) -> Unit,
    onMenu: (Int) -> Unit,
    onContinue: () -> Unit,
    onRecentInfo: () -> Unit,
    onRetry: () -> Unit,
    page: @Composable (BookshelfHomeGroup, Int, Boolean, Modifier) -> Unit,
) {
    val pages = rememberSaveableStateHolder()
    val colors = LocalLegadoColors.current
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            BookshelfToolbar(stringResource(R.string.bookshelf), onMenu)
            BookshelfHeader(state.header, onContinue, onRecentInfo)
            if (state.groups.isEmpty()) {
                if (state.loading) CircularProgressIndicator(Modifier.padding(24.dp))
                state.error?.let {
                    Text(it)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
                return@Column
            }
            val pager = rememberPagerState(initialPage = state.selectedIndex) { state.groups.size }
            val select by rememberUpdatedState(onSelect)
            val groups by rememberUpdatedState(state.groups)
            LaunchedEffect(state.selectedId, state.groups.map { it.id }) {
                if (pager.currentPage != state.selectedIndex)
                    pager.scrollToPage(state.selectedIndex)
            }
            LaunchedEffect(pager) {
                snapshotFlow { pager.settledPage }
                    .distinctUntilChanged()
                    .collect { index ->
                        groups.getOrNull(index)?.let { select(it.id) }
                    }
            }
            ScrollableTabRow(
                selectedTabIndex = state.selectedIndex,
                edgePadding = 0.dp,
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
                indicator = { positions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(positions[state.selectedIndex]),
                        color = colors.accent,
                    )
                },
            ) {
                state.groups.forEach { group ->
                    // One click target supports both reselect and group editing with accessibility
                    // actions.
                    Box(
                        Modifier.heightIn(min = 48.dp)
                            .testTag("shelf-tab-${group.id}")
                            .semantics {
                                selected = state.selectedId == group.id
                                role = Role.Tab
                            }
                            .combinedClickable(
                                onClick = {
                                    if (state.selectedId == group.id) onReselect(group.id)
                                    else onSelect(group.id)
                                },
                                onLongClick = { onGroupInfo(group.id) },
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Text(
                            group.name,
                            color = colors.onPrimary,
                            fontWeight =
                                if (state.selectedId == group.id) FontWeight.Bold
                                else FontWeight.Normal,
                        )
                    }
                }
            }
            HorizontalPager(
                pager,
                Modifier.weight(1f).testTag("shelf-pager"),
                beyondViewportPageCount = 1,
                key = { state.groups[it].id },
            ) { index ->
                pages.SaveableStateProvider(state.groups[index].id) {
                    page(
                        state.groups[index],
                        index,
                        index == pager.currentPage,
                        Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
