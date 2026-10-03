package io.legado.app.ui.config

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R

class BottomBarSkinCatalogActions(
    val close: () -> Unit = {},
    val importPicker: () -> Unit = {},
    val activate: (String) -> Unit = {},
    val menu: (String) -> Unit = {},
    val cancelMenu: () -> Unit = {},
    val edit: (String) -> Unit = {},
    val export: (String) -> Unit = {},
    val share: (String) -> Unit = {},
    val delete: (String) -> Unit = {},
    val confirmDelete: () -> Unit = {},
    val cancelDelete: () -> Unit = {},
    val scroll: (Int, Int) -> Unit = { _, _ -> },
    val retry: () -> Unit = {},
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BottomBarSkinCatalogScreen(
    state: BottomBarSkinCatalogState,
    actions: BottomBarSkinCatalogActions,
    preview: @Composable (String) -> Unit = {},
) {
    val grid = rememberLazyGridState()
    val action by rememberUpdatedState(actions)
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(state.loaded) {
        if (state.loaded && !restored) {
            withFrameNanos {}
            grid.scrollToItem(state.scroll.coerceAtMost(state.names.size), state.offset)
            restored = true
        }
    }
    LaunchedEffect(grid) {
        snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }
            .collect {
                if (restored) action.scroll(it.first, it.second)
            }
    }
    val columns = (LocalConfiguration.current.screenWidthDp / 160).coerceIn(1, 4)
    val accent = MaterialTheme.colorScheme.primary
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Surface(color = accent, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        actions.close,
                        Modifier.testTag("skin-catalog-back"),
                        enabled = !state.closeBlocked,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                    Text(
                        stringResource(R.string.bottom_bar_skin),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TextButton(
                        actions.importPicker,
                        Modifier.testTag("skin-catalog-import"),
                        enabled = !state.busy && state.effect == null,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                    ) {
                        Text(stringResource(R.string.bottom_bar_skin_import))
                    }
                }
            }
            if (!state.loaded || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.loaded)
                TextButton(
                    actions.retry,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("skin-catalog-retry"),
                ) {
                    Text(stringResource(R.string.retry))
                }
            LazyVerticalGrid(
                GridCells.Fixed(columns),
                Modifier.weight(1f).fillMaxWidth().testTag("skin-catalog-grid"),
                state = grid,
                contentPadding = PaddingValues(8.dp),
            ) {
                items(listOf("") + state.names, key = { it }) { name ->
                    val active = state.active == name
                    val shape = RoundedCornerShape(8.dp)
                    Box(
                        Modifier.padding(8.dp)
                            .fillMaxWidth()
                            .heightIn(min = 68.dp)
                            .then(
                                if (active)
                                    Modifier.border(2.dp, accent, shape)
                                        .background(accent.copy(alpha = 0x14 / 255f), shape)
                                else Modifier
                            )
                            .semantics { selected = active }
                            .testTag(
                                if (name.isEmpty()) "skin-catalog-default"
                                else "skin-catalog-item-$name"
                            )
                            .combinedClickable(
                                enabled = state.loaded && !state.busy && state.effect == null,
                                onClick = { actions.activate(name) },
                                onLongClick = { if (name.isNotEmpty()) actions.menu(name) },
                            )
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.heightIn(min = 32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (name.isEmpty())
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        for (icon in
                                            listOf(
                                                R.drawable.ic_bottom_books,
                                                R.drawable.ic_bottom_explore,
                                                R.drawable.ic_bottom_rss_feed,
                                                R.drawable.ic_bottom_person,
                                            )) Icon(
                                            painterResource(icon),
                                            null,
                                            Modifier.size(24.dp),
                                            tint = accent,
                                        )
                                    }
                                else preview(name)
                            }
                            Text(
                                if (name.isEmpty()) stringResource(R.string.bottom_bar_skin_default)
                                else name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        if (active)
                            Icon(
                                painterResource(R.drawable.ic_check),
                                null,
                                Modifier.align(Alignment.TopEnd).size(18.dp),
                                tint = accent,
                            )
                    }
                }
            }
        }
    }
    state.menu?.let { name ->
        AlertDialog(
            onDismissRequest = actions.cancelMenu,
            title = { Text(name) },
            text = {
                Column {
                    for ((tag, title, callback) in
                        listOf(
                            Triple("edit", R.string.edit, actions.edit),
                            Triple("export", R.string.export, actions.export),
                            Triple("share", R.string.share, actions.share),
                            Triple("delete", R.string.delete, actions.delete),
                        )) {
                        TextButton(
                            { callback(name) },
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("skin-catalog-menu-$tag"),
                            enabled = !state.busy,
                        ) {
                            Text(stringResource(title))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(actions.cancelMenu, Modifier.testTag("skin-catalog-menu-cancel")) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    state.delete?.let {
        AlertDialog(
            onDismissRequest = actions.cancelDelete,
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.bottom_bar_skin_delete_confirm)) },
            confirmButton = {
                TextButton(
                    actions.confirmDelete,
                    Modifier.testTag("skin-catalog-delete-confirm"),
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(
                    actions.cancelDelete,
                    Modifier.testTag("skin-catalog-delete-cancel"),
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.no))
                }
            },
        )
    }
}
