package io.legado.app.ui.book.info.detail

import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.BookDetailBook
import io.legado.app.data.repository.BookDetailPreferences
import io.legado.app.data.repository.CoverRequest
import io.legado.app.ui.components.LegadoTopAppBar
import io.legado.app.ui.components.cover.ComposeCover

enum class BookDetailAction {
    CustomButton,
    Edit,
    Share,
    Upload,
    Refresh,
    UpdateTask,
    Login,
    Top,
    SourceVariable,
    BookVariable,
    CopyBookUrl,
    CopyTocUrl,
    CanUpdate,
    SplitLong,
    DeleteAlert,
    ClearCache,
    Log,
    Group,
    ChangeSource,
    Toc,
    RefreshToc,
    Shelf,
    Read,
}

enum class BookDetailClick {
    Name,
    Author,
    Kind,
    Origin,
    Cover,
}

class BookDetailActions(
    val back: () -> Unit = {},
    val action: (BookDetailAction) -> Unit = {},
    val click: (BookDetailClick, String?, Boolean) -> Unit = { _, _, _ -> },
    val introExpanded: (Boolean) -> Unit = {},
    val retry: () -> Unit = {},
    val reload: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookDetailScreen(
    state: BookDetailState,
    preferences: BookDetailPreferences,
    actions: BookDetailActions,
    modifier: Modifier = Modifier,
    background: @Composable (BookDetailBook, Modifier) -> Unit = { _, _ -> },
    cover: @Composable (CoverRequest, Modifier) -> Unit = { request, layout ->
        ComposeCover(request, layout)
    },
    intro: @Composable (BookDetailBook, Boolean, (Boolean) -> Unit, Modifier) -> Unit,
) {
    val data = state.data
    val book = data?.book
    var menu by rememberSaveable { mutableStateOf(false) }
    Surface(modifier.fillMaxSize()) {
        Box {
            book?.let { background(it, Modifier.fillMaxSize()) }
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .9f),
                topBar = {
                    LegadoTopAppBar(
                        stringResource(R.string.book_info),
                        actions.back,
                        actions = {
                            if (data?.source?.customButton == true)
                                BookDetailToolbarButton(
                                    R.drawable.ic_custom,
                                    R.string.custom_button,
                                    state.canInteract,
                                    "custom",
                                ) {
                                    actions.action(BookDetailAction.CustomButton)
                                }
                            if (data?.inBookshelf == true)
                                BookDetailToolbarButton(
                                    R.drawable.ic_edit,
                                    R.string.edit,
                                    state.canInteract,
                                    "edit",
                                ) {
                                    actions.action(BookDetailAction.Edit)
                                }
                            BookDetailToolbarButton(
                                R.drawable.ic_share,
                                R.string.share,
                                state.canInteract,
                                "share",
                            ) {
                                actions.action(BookDetailAction.Share)
                            }
                            Box {
                                BookDetailToolbarButton(
                                    R.drawable.ic_more_vert,
                                    R.string.more_menu,
                                    state.loaded && !state.closed,
                                    "more",
                                ) {
                                    menu = true
                                }
                                DropdownMenu(menu, { menu = false }) {
                                    fun choose(action: BookDetailAction) {
                                        menu = false
                                        actions.action(action)
                                    }
                                    if (book?.isLocal == true)
                                        BookDetailMenuItem(
                                            R.string.upload_to_remote,
                                            state.canInteract,
                                        ) {
                                            choose(BookDetailAction.Upload)
                                        }
                                    BookDetailMenuItem(R.string.refresh, state.canInteract) {
                                        choose(BookDetailAction.Refresh)
                                    }
                                    if (
                                        data?.inBookshelf == true &&
                                            data.source != null &&
                                            book?.isLocal == false &&
                                            book.canUpdate
                                    )
                                        BookDetailMenuItem(
                                            R.string.create_book_update_task,
                                            state.canInteract,
                                        ) {
                                            choose(BookDetailAction.UpdateTask)
                                        }
                                    if (data?.source?.hasLogin == true)
                                        BookDetailMenuItem(R.string.login, state.canInteract) {
                                            choose(BookDetailAction.Login)
                                        }
                                    BookDetailMenuItem(R.string.to_top, state.canInteract) {
                                        choose(BookDetailAction.Top)
                                    }
                                    if (data?.source != null) {
                                        BookDetailMenuItem(
                                            R.string.set_source_variable,
                                            state.canInteract,
                                        ) {
                                            choose(BookDetailAction.SourceVariable)
                                        }
                                        BookDetailMenuItem(
                                            R.string.set_book_variable,
                                            state.canInteract,
                                        ) {
                                            choose(BookDetailAction.BookVariable)
                                        }
                                    }
                                    BookDetailMenuItem(R.string.copy_book_url, state.canInteract) {
                                        choose(BookDetailAction.CopyBookUrl)
                                    }
                                    BookDetailMenuItem(R.string.copy_toc_url, state.canInteract) {
                                        choose(BookDetailAction.CopyTocUrl)
                                    }
                                    if (data?.source != null)
                                        BookDetailMenuCheck(
                                            R.string.allow_update,
                                            book?.canUpdate == true,
                                            state.canInteract,
                                        ) {
                                            choose(BookDetailAction.CanUpdate)
                                        }
                                    if (book?.isLocalTxt == true)
                                        BookDetailMenuCheck(
                                            R.string.split_long_chapter,
                                            book.splitLongChapter,
                                            state.canInteract,
                                        ) {
                                            choose(BookDetailAction.SplitLong)
                                        }
                                    BookDetailMenuCheck(
                                        R.string.delete_alert,
                                        preferences.deleteAlert,
                                        state.canInteract,
                                    ) {
                                        choose(BookDetailAction.DeleteAlert)
                                    }
                                    BookDetailMenuItem(R.string.clear_cache, state.canInteract) {
                                        choose(BookDetailAction.ClearCache)
                                    }
                                    BookDetailMenuItem(R.string.log, state.loaded) {
                                        choose(BookDetailAction.Log)
                                    }
                                }
                            }
                        },
                    )
                },
                bottomBar = {
                    if (book != null)
                        Surface(tonalElevation = 3.dp) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                OutlinedButton(
                                    { actions.action(BookDetailAction.Shelf) },
                                    enabled = state.canInteract,
                                    modifier =
                                        Modifier.weight(1f)
                                            .heightIn(min = 48.dp)
                                            .testTag("book-detail-shelf"),
                                ) {
                                    Text(
                                        stringResource(
                                            if (data.inBookshelf == true)
                                                R.string.remove_from_bookshelf
                                            else R.string.add_to_bookshelf
                                        )
                                    )
                                }
                                Button(
                                    { actions.action(BookDetailAction.Read) },
                                    enabled = state.canInteract,
                                    modifier =
                                        Modifier.weight(1f)
                                            .heightIn(min = 48.dp)
                                            .testTag("book-detail-read"),
                                ) {
                                    Text(stringResource(R.string.reading))
                                }
                            }
                        }
                },
            ) { padding ->
                Column(Modifier.padding(padding)) {
                    if (state.loading || state.busy || state.networkLoading)
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().testTag("book-detail-loading")
                        )
                    state.error?.let { error ->
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                error,
                                Modifier.weight(1f).testTag("book-detail-error"),
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Column {
                                TextButton(
                                    actions.retry,
                                    enabled = !state.busy,
                                    modifier = Modifier.testTag("book-detail-retry"),
                                ) {
                                    Text(stringResource(R.string.retry))
                                }
                                if (state.loaded)
                                    TextButton(
                                        actions.reload,
                                        enabled = !state.busy && !state.networkLoading,
                                        modifier = Modifier.testTag("book-detail-reload"),
                                    ) {
                                        Text(stringResource(R.string.refresh))
                                    }
                            }
                        }
                    }
                    PullToRefreshBox(
                        isRefreshing = false,
                        onRefresh = {
                            if (state.canInteract) actions.action(BookDetailAction.Refresh)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(
                            Modifier.fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .testTag("book-detail-content")
                        ) {
                            if (book != null) {
                                Box(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        Modifier.width(110.dp)
                                            .height(160.dp)
                                            .combinedClickable(
                                                enabled = state.canInteract,
                                                onClick = {
                                                    actions.click(
                                                        BookDetailClick.Cover,
                                                        null,
                                                        false,
                                                    )
                                                },
                                                onLongClick = {
                                                    actions.click(BookDetailClick.Cover, null, true)
                                                },
                                            )
                                            .testTag("book-detail-cover")
                                    ) {
                                        cover(book.cover, Modifier.fillMaxSize())
                                    }
                                }
                                Text(
                                    book.name,
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = 20.dp)
                                        .heightIn(min = 48.dp)
                                        .combinedClickable(
                                            enabled = state.canInteract,
                                            onClick = {
                                                actions.click(BookDetailClick.Name, null, false)
                                            },
                                            onLongClick = {
                                                actions.click(BookDetailClick.Name, null, true)
                                            },
                                        )
                                        .testTag("book-detail-name"),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                FlowRow(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    data.kinds.forEachIndexed { index, kind ->
                                        Text(
                                            kind,
                                            Modifier.heightIn(min = 48.dp)
                                                .combinedClickable(
                                                    enabled = state.canInteract,
                                                    onClick = {
                                                        actions.click(
                                                            BookDetailClick.Kind,
                                                            kind,
                                                            false,
                                                        )
                                                    },
                                                    onLongClick = {
                                                        actions.click(
                                                            BookDetailClick.Kind,
                                                            kind,
                                                            true,
                                                        )
                                                    },
                                                )
                                                .padding(horizontal = 8.dp, vertical = 12.dp)
                                                .testTag("book-detail-kind-$index"),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                BookDetailRow(
                                    R.drawable.ic_author,
                                    stringResource(R.string.author_show, book.realAuthor),
                                    "author",
                                    { actions.click(BookDetailClick.Author, null, false) },
                                    { actions.click(BookDetailClick.Author, null, true) },
                                    state.canInteract,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    BookDetailRow(
                                        R.drawable.ic_web_outline,
                                        stringResource(R.string.origin_show, book.originName),
                                        "origin",
                                        { actions.click(BookDetailClick.Origin, null, false) },
                                        { actions.click(BookDetailClick.Origin, null, true) },
                                        state.canInteract,
                                        Modifier.weight(1f),
                                    )
                                    TextButton(
                                        { actions.action(BookDetailAction.ChangeSource) },
                                        enabled = state.canInteract,
                                        modifier = Modifier.testTag("book-detail-change-source"),
                                    ) {
                                        Text(stringResource(R.string.change_origin))
                                    }
                                }
                                BookDetailRow(
                                    R.drawable.ic_groups,
                                    stringResource(
                                        R.string.group_s,
                                        data.groupNames.joinToString().ifBlank {
                                            stringResource(
                                                if (book.isLocal) R.string.local_no_group
                                                else R.string.no_group
                                            )
                                        },
                                    ),
                                    "group",
                                    { actions.action(BookDetailAction.Group) },
                                    enabled = state.canInteract,
                                )
                                if (!book.isWebFile)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val title =
                                            if (state.networkLoading)
                                                stringResource(R.string.loading)
                                            else if (data.chapters.isEmpty())
                                                stringResource(R.string.error_load_toc)
                                            else
                                                listOfNotNull(
                                                        book.chapterTitle?.takeIf {
                                                            it.isNotBlank()
                                                        }
                                                            ?: (data.chapters.getOrNull(
                                                                    book.chapterIndex
                                                                ) ?: data.chapters.last())
                                                                .title
                                                                .takeIf { it.isNotBlank() }
                                                            ?: stringResource(
                                                                R.string.no_last_chapter
                                                            ),
                                                        book.readPercent?.let {
                                                            stringResource(R.string.read_y, "$it%")
                                                        },
                                                    )
                                                    .joinToString("  ·  ")
                                        BookDetailRow(
                                            R.drawable.ic_toc,
                                            stringResource(R.string.toc_s, title),
                                            "toc",
                                            { actions.action(BookDetailAction.Toc) },
                                            enabled = state.canInteract,
                                            modifier = Modifier.weight(1f),
                                        )
                                        IconButton(
                                            { actions.action(BookDetailAction.RefreshToc) },
                                            enabled = state.canInteract,
                                            modifier = Modifier.testTag("book-detail-refresh-toc"),
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.ic_refresh_black_24dp),
                                                stringResource(R.string.refresh),
                                            )
                                        }
                                    }
                                Text(
                                    stringResource(
                                        R.string.lasted_show,
                                        book.latestChapterTitle.orEmpty(),
                                    ),
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 12.dp)
                                        .testTag("book-detail-latest"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                intro(
                                    book,
                                    state.introExpanded,
                                    actions.introExpanded,
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 8.dp),
                                )
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookDetailToolbarButton(
    icon: Int,
    label: Int,
    enabled: Boolean,
    tag: String,
    click: () -> Unit,
) {
    IconButton(click, enabled = enabled, modifier = Modifier.testTag("book-detail-$tag")) {
        Icon(painterResource(icon), stringResource(label))
    }
}

@Composable
private fun BookDetailMenuItem(label: Int, enabled: Boolean, click: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = click,
        enabled = enabled,
        modifier = Modifier.testTag("book-detail-menu-$label"),
    )
}

@Composable
private fun BookDetailMenuCheck(label: Int, checked: Boolean, enabled: Boolean, click: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = click,
        enabled = enabled,
        leadingIcon = { Checkbox(checked, null, enabled = enabled) },
        modifier = Modifier.testTag("book-detail-menu-$label"),
    )
}

@Composable
private fun BookDetailRow(
    icon: Int,
    text: String,
    tag: String,
    click: () -> Unit,
    longClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .combinedClickable(enabled = enabled, onClick = click, onLongClick = longClick)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag("book-detail-$tag"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painterResource(icon),
            null,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text,
            Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
