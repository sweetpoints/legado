package io.legado.app.ui.main.bookshelf.style1

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import io.legado.app.ui.theme.LocalLegadoColors
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.main.bookshelf.components.BookshelfHeader
import kotlinx.coroutines.flow.distinctUntilChanged

private val shelfMenu = listOf(R.id.menu_update_toc to R.string.update_toc,
    R.id.menu_add_local to R.string.book_local, R.id.menu_remote to R.string.add_remote_book,
    R.id.menu_add_url to R.string.add_url, R.id.menu_bookshelf_manage to R.string.bookshelf_management,
    R.id.menu_download to R.string.cache_export, R.id.menu_group_manage to R.string.group_manage,
    R.id.menu_bookshelf_layout to R.string.bookshelf_layout, R.id.menu_export_bookshelf to R.string.export_bookshelf,
    R.id.menu_import_bookshelf to R.string.import_bookshelf, R.id.menu_log to R.string.log)

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun BookshelfHomeScreen(state: BookshelfHomeState, onSelect: (Long) -> Unit,
    onReselect: (Long) -> Unit, onGroupInfo: (Long) -> Unit, onMenu: (Int) -> Unit,
    onContinue: () -> Unit, onRecentInfo: () -> Unit, onRetry: () -> Unit,
    page: @Composable (BookshelfHomeGroup, Int, Boolean, Modifier) -> Unit) {
    val pages = rememberSaveableStateHolder()
    val colors = LocalLegadoColors.current
    val menuLabel = stringResource(R.string.menu)
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(title = { Text(stringResource(R.string.bookshelf)) }, actions = {
                IconButton(onClick = { onMenu(R.id.menu_search) }, modifier = Modifier.testTag("shelf-search")) {
                    Icon(painterResource(R.drawable.ic_search), stringResource(R.string.search))
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("shelf-menu").semantics { contentDescription = menuLabel }) {
                        Icon(painterResource(R.drawable.ic_more_vert), null)
                    }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        shelfMenu.forEach { (id, label) -> DropdownMenuItem(text = { Text(stringResource(label)) },
                            onClick = { menuOpen = false; onMenu(id) }, modifier = Modifier.testTag("shelf-menu-$id")) }
                    }
                }
            }, windowInsets = WindowInsets(0, 0, 0, 0), colors = TopAppBarDefaults.topAppBarColors(
                containerColor = colors.primary, titleContentColor = colors.onPrimary, actionIconContentColor = colors.onPrimary))
            BookshelfHeader(state.header, onContinue, onRecentInfo)
            if (state.groups.isEmpty()) {
                if (state.loading) CircularProgressIndicator(Modifier.padding(24.dp))
                state.error?.let { Text(it); TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) } }
                return@Column
            }
            val pager = rememberPagerState(initialPage = state.selectedIndex) { state.groups.size }
            val select by rememberUpdatedState(onSelect)
            val groups by rememberUpdatedState(state.groups)
            LaunchedEffect(state.selectedId, state.groups.map { it.id }) {
                if (pager.currentPage != state.selectedIndex) pager.scrollToPage(state.selectedIndex)
            }
            LaunchedEffect(pager) {
                snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { index ->
                    groups.getOrNull(index)?.let { select(it.id) }
                }
            }
            ScrollableTabRow(selectedTabIndex = state.selectedIndex, edgePadding = 0.dp,
                containerColor = colors.primary, contentColor = colors.onPrimary, indicator = { positions ->
                    TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[state.selectedIndex]), color = colors.accent)
                }) {
                state.groups.forEach { group ->
                    // One click target supports both reselect and group editing with accessibility actions.
                    Box(Modifier.heightIn(min = 48.dp).testTag("shelf-tab-${group.id}")
                        .semantics { selected = state.selectedId == group.id; role = Role.Tab }
                        .combinedClickable(onClick = { if (state.selectedId == group.id) onReselect(group.id) else onSelect(group.id) },
                            onLongClick = { onGroupInfo(group.id) }).padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text(group.name, color = colors.onPrimary, fontWeight = if (state.selectedId == group.id) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            HorizontalPager(pager, Modifier.weight(1f).testTag("shelf-pager"), beyondViewportPageCount = 1,
                key = { state.groups[it].id }) { index ->
                pages.SaveableStateProvider(state.groups[index].id) {
                    page(state.groups[index], index, index == pager.currentPage, Modifier.fillMaxSize())
                }
            }
        }
    }
}
