package io.legado.app.ui.main.rss

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.repository.RssArticleImageRepository
import kotlinx.coroutines.flow.distinctUntilChanged

class MainRssActions(val query: (String, Int, Int) -> Unit, val action: (MainRssAction, String?) -> Unit,
    val top: (String) -> Unit, val disable: (String) -> Unit, val delete: (String) -> Unit,
    val cancelDelete: () -> Unit, val confirmDelete: () -> Unit, val retry: () -> Unit,
    val scroll: (Int, Int) -> Unit)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainRssScreen(state: MainRssState, actions: MainRssActions, images: RssArticleImageRepository) {
    val focus = LocalFocusManager.current
    val grid = rememberLazyGridState()
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(state.loaded, state.rows.size) {
        if (!restored && state.loaded && state.rows.isNotEmpty()) {
            grid.scrollToItem(state.scrollIndex.coerceIn(0, state.rows.size), state.scrollOffset); restored = true
        }
    }
    val currentScroll by rememberUpdatedState(actions.scroll)
    LaunchedEffect(grid, restored) { if (restored) snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }
        .distinctUntilChanged().collect { currentScroll(it.first, it.second) } }
    LaunchedEffect(state.active) { if (!state.active) focus.clearFocus() }
    var query by remember { mutableStateOf(TextFieldValue(state.query, TextRange(state.queryStart, state.queryEnd))) }
    LaunchedEffect(state.query, state.queryStart, state.queryEnd) {
        if (query.text != state.query || query.selection != TextRange(state.queryStart, state.queryEnd))
            query = TextFieldValue(state.query, TextRange(state.queryStart, state.queryEnd))
    }
    var groups by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.active) { if (!state.active) { groups = false; menu = null } }
    LaunchedEffect(state.rows) { if (menu != null && state.rows.none { it.id == menu }) menu = null }
    val enabled = state.loaded && state.active && !state.busy && state.pending == null
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.ime))) {
            TopAppBar(title = { OutlinedTextField(query, { value -> query = value; actions.query(value.text, value.selection.start, value.selection.end) },
                singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("main-rss-search"),
                placeholder = { Text(stringResource(R.string.rss)) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() })) }, actions = {
                IconButton({ actions.action(MainRssAction.History, null) }, enabled = enabled, modifier = Modifier.testTag("main-rss-history")) {
                    Icon(painterResource(R.drawable.ic_history), stringResource(R.string.history)) }
                IconButton({ actions.action(MainRssAction.Favorites, null) }, enabled = enabled, modifier = Modifier.testTag("main-rss-favorites")) {
                    Icon(painterResource(R.drawable.ic_star), stringResource(R.string.favorite)) }
                Box {
                    IconButton({ groups = true }, enabled = enabled, modifier = Modifier.testTag("main-rss-groups")) {
                        Icon(painterResource(R.drawable.ic_groups), stringResource(R.string.group)) }
                    DropdownMenu(groups && state.active, { groups = false }) { state.groups.forEach { group ->
                        DropdownMenuItem(text = { Text(group) }, onClick = { groups = false; val text = "group:$group"; actions.query(text, text.length, text.length) })
                    } }
                }
                IconButton({ actions.action(MainRssAction.Settings, null) }, enabled = enabled, modifier = Modifier.testTag("main-rss-settings")) {
                    Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.setting)) }
            })
            val error = state.error ?: state.issue?.let { stringResource(R.string.rss_source_empty) }
            if (error != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(actions.retry, enabled = !state.busy, modifier = Modifier.testTag("main-rss-retry")) { Text(stringResource(R.string.retry)) }
            }
            LazyVerticalGrid(GridCells.Fixed(4), state = grid, modifier = Modifier.weight(1f).fillMaxWidth().testTag("main-rss-grid")) {
                // The old RecyclerAdapter header has span=1 and is the first cell of the four-column grid.
                item(key = "subscriptions") { MainRssCard(stringResource(R.string.rule_subscription), enabled, "main-rss-subscriptions",
                    { actions.action(MainRssAction.Subscriptions, null) }, null) {
                    Image(painterResource(R.drawable.image_legado), null, Modifier.size(50.dp))
                } }
                items(state.rows, key = { it.id }) { row -> Box {
                    MainRssCard(row.name, enabled, "main-rss-card-${row.id}", { actions.action(MainRssAction.Open, row.id) }, { menu = row.id }) {
                        MainRssIcon(row, images)
                    }
                    DropdownMenu(menu == row.id && state.active, { menu = null }) {
                        MainRssMenuItem(R.string.edit, "main-rss-edit-${row.id}") { menu = null; actions.action(MainRssAction.Edit, row.id) }
                        MainRssMenuItem(R.string.to_top, "main-rss-top-${row.id}") { menu = null; actions.top(row.id) }
                        if (row.hasLogin) MainRssMenuItem(R.string.login, "main-rss-login-${row.id}") { menu = null; actions.action(MainRssAction.Login, row.id) }
                        MainRssMenuItem(R.string.disable_source, "main-rss-disable-${row.id}") { menu = null; actions.disable(row.id) }
                        MainRssMenuItem(R.string.delete, "main-rss-delete-${row.id}", true) { menu = null; actions.delete(row.id) }
                    }
                } }
            }
        }
    }
    if (state.active && state.deletingId != null) AlertDialog(onDismissRequest = { if (!state.busy) actions.cancelDelete() },
        title = { Text(stringResource(R.string.draw)) }, text = { Text(stringResource(R.string.sure_del) + "\n" + state.deletingName.orEmpty()) },
        confirmButton = { TextButton(actions.confirmDelete, enabled = !state.busy, modifier = Modifier.testTag("main-rss-delete-confirm")) { Text(stringResource(R.string.yes)) } },
        dismissButton = { TextButton(actions.cancelDelete, enabled = !state.busy, modifier = Modifier.testTag("main-rss-delete-cancel")) { Text(stringResource(R.string.no)) } })
}
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun MainRssCard(name: String, enabled: Boolean, tag: String, click: () -> Unit, longClick: (() -> Unit)?, icon: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag(tag).onFocusChanged { focused = it.isFocused }
        .background(if (focused) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(4.dp))
        .combinedClickable(enabled = enabled, onClick = click, onLongClick = longClick).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        icon(); Spacer(Modifier.height(16.dp))
        Text(name, fontSize = 13.sp, minLines = 2, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun MainRssMenuItem(label: Int, tag: String, danger: Boolean = false, click: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label), color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
        onClick = click, modifier = Modifier.testTag(tag))
}
