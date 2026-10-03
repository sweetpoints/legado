package io.legado.app.ui.book.changesource

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.legado.app.R
import kotlinx.coroutines.launch

internal enum class ChapterSourceMenu {
    Manage,
    Refresh,
    Author,
    WordCount,
    ResponseTime,
    WordCountFilter,
    Info,
    Toc,
    Automation,
    Group,
    Close,
}

internal val chapterSourceMenuOrder =
    listOf(
        ChapterSourceMenu.Manage,
        ChapterSourceMenu.Automation,
        ChapterSourceMenu.Refresh,
        ChapterSourceMenu.Author,
        ChapterSourceMenu.WordCount,
        ChapterSourceMenu.ResponseTime,
        ChapterSourceMenu.WordCountFilter,
        ChapterSourceMenu.Info,
        ChapterSourceMenu.Toc,
        ChapterSourceMenu.Group,
        ChapterSourceMenu.Close,
    )

internal enum class ChapterSourceRowAction {
    Top,
    Bottom,
    Edit,
    Disable,
    Delete,
}

internal val chapterSourceRowActions = ChapterSourceRowAction.entries.toList()

internal data class ChapterSourceScreenActions(
    val close: () -> Unit,
    val startStop: () -> Unit,
    val searchOpen: () -> Unit,
    val query: (String) -> Unit,
    val retry: () -> Unit,
    val recover: () -> Unit,
    val menu: (ChapterSourceMenu) -> Unit,
    val group: (String) -> Unit,
    val openToc: (String) -> Unit,
    val hideToc: () -> Unit,
    val chapter: (Int) -> Unit,
    val skip: () -> Unit,
    val cache: () -> Unit,
    val rowAction: (String, ChapterSourceRowAction) -> Unit,
    val score: (String, Int) -> Unit,
    val dismissEmpty: () -> Unit,
    val startAutomation: (Int, Int) -> Boolean,
    val range: () -> IntRange?,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ChapterSourceScreen(
    state: ChapterSourceState,
    actions: ChapterSourceScreenActions,
    modifier: Modifier = Modifier,
    scrollTarget: Pair<String, Int>? = null,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    var groupMenu by rememberSaveable { mutableStateOf(false) }
    var selectedRow by remember { mutableStateOf<String?>(null) }
    var rangeOpen by rememberSaveable { mutableStateOf(false) }
    var rangeStart by rememberSaveable { mutableStateOf("") }
    var rangeEnd by rememberSaveable { mutableStateOf("") }
    var rangeError by rememberSaveable { mutableStateOf(false) }
    var rangeNotice by rememberSaveable { mutableStateOf<Int?>(null) }
    val sources = rememberLazyListState()
    val toc = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val busy =
        state.loading || state.persistError || state.busy || state.tocLoading || state.caching
    val automatic = state.automation
    LaunchedEffect(state.rows.firstOrNull()?.id) {
        if (state.rows.isNotEmpty()) sources.scrollToItem(0)
    }
    LaunchedEffect(state.toc?.key) {
        state.toc?.let {
            if (it.chapters.isNotEmpty())
                toc.scrollToItem((it.currentIndex - 5).coerceIn(0, it.chapters.lastIndex))
        }
    }
    LaunchedEffect(scrollTarget) {
        scrollTarget?.let { (_, position) ->
            state.toc
                ?.takeIf { it.chapters.isNotEmpty() }
                ?.let { toc.scrollToItem(position.coerceIn(0, it.chapters.lastIndex)) }
        }
    }
    LaunchedEffect(automatic?.stage, automatic?.position) {
        automatic
            ?.takeIf { it.stage == "Paused" }
            ?.positions
            ?.firstOrNull()
            ?.let { toc.scrollToItem(it) }
    }
    fun openRange() {
        val range = actions.range()
        if (state.toc == null) rangeNotice = R.string.chapter_source_automation_select_target
        else if (range == null) rangeNotice = R.string.chapter_list_empty
        else {
            rangeStart = range.first.toString()
            rangeEnd = range.last.toString()
            rangeError = false
            rangeOpen = true
        }
    }
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().imePadding().testTag("chapter-source-screen")) {
            TopAppBar(
                title = {
                    Column {
                        Text(state.chapterTitle, maxLines = 1)
                        if (state.batch)
                            Text(
                                when {
                                    automatic?.stage == "Paused" ->
                                        when (automatic.reason) {
                                            "Ambiguous" ->
                                                stringResource(
                                                    R.string
                                                        .chapter_source_automation_paused_ambiguous
                                                )
                                            "Missing" ->
                                                stringResource(
                                                    R.string
                                                        .chapter_source_automation_paused_missing
                                                )
                                            else ->
                                                stringResource(
                                                    R.string.chapter_source_automation_paused_error,
                                                    automatic.reason.orEmpty(),
                                                )
                                        }
                                    automatic != null ->
                                        stringResource(
                                            R.string.chapter_source_automation_progress,
                                            automatic.position + 1,
                                            automatic.chapters.size,
                                        )
                                    else ->
                                        stringResource(
                                            R.string.chapter_source_selected_count,
                                            state.selected.size,
                                        )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier =
                                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                    }
                },
                navigationIcon = {
                    TextButton(actions.close, Modifier.testTag("chapter-source-close")) {
                        Text(stringResource(R.string.close))
                    }
                },
                actions = {
                    TextButton(actions.searchOpen, Modifier.testTag("chapter-source-filter")) {
                        Text(stringResource(R.string.screen))
                    }
                    TextButton(
                        actions.startStop,
                        Modifier.testTag("chapter-source-search"),
                        enabled =
                            !state.loading &&
                                !state.persistError &&
                                !state.caching &&
                                !state.changing,
                    ) {
                        Text(
                            stringResource(if (state.searching) R.string.stop else R.string.refresh)
                        )
                    }
                    Box {
                        TextButton({ menu = true }, Modifier.testTag("chapter-source-menu")) {
                            Text("⋮")
                        }
                        DropdownMenu(menu, { menu = false }) {
                            chapterSourceMenuOrder
                                .filter { it != ChapterSourceMenu.Automation || state.batch }
                                .forEach { action ->
                                    val label =
                                        when (action) {
                                            ChapterSourceMenu.Manage -> R.string.book_source_manage
                                            ChapterSourceMenu.Refresh -> R.string.refresh_list
                                            ChapterSourceMenu.Author -> R.string.checkAuthor
                                            ChapterSourceMenu.WordCount -> R.string.load_word_count
                                            ChapterSourceMenu.ResponseTime ->
                                                R.string.change_source_sort_respond_time
                                            ChapterSourceMenu.WordCountFilter ->
                                                R.string.change_source_word_count_filter
                                            ChapterSourceMenu.Info -> R.string.load_info
                                            ChapterSourceMenu.Toc -> R.string.load_toc
                                            ChapterSourceMenu.Automation ->
                                                if (automatic == null)
                                                    R.string.chapter_source_automation
                                                else R.string.chapter_source_automation_stop
                                            ChapterSourceMenu.Group -> R.string.group
                                            ChapterSourceMenu.Close -> R.string.close
                                        }
                                    val checked =
                                        when (action) {
                                            ChapterSourceMenu.Author -> state.request?.checkAuthor
                                            ChapterSourceMenu.WordCount ->
                                                state.request?.loadWordCount
                                            ChapterSourceMenu.ResponseTime ->
                                                state.request?.sortResponseTime
                                            ChapterSourceMenu.Info -> state.request?.loadInfo
                                            ChapterSourceMenu.Toc -> state.request?.loadToc
                                            ChapterSourceMenu.WordCountFilter ->
                                                state.request?.filterMode?.let { it != 0 }
                                            else -> null
                                        }
                                    DropdownMenuItem(
                                        {
                                            Text(
                                                (if (checked == true) "✓ " else "") +
                                                    stringResource(label)
                                            )
                                        },
                                        {
                                            menu = false
                                            when {
                                                action == ChapterSourceMenu.Group ->
                                                    groupMenu = true
                                                action == ChapterSourceMenu.Automation &&
                                                    automatic == null -> openRange()
                                                else -> actions.menu(action)
                                            }
                                        },
                                        modifier =
                                            Modifier.testTag("chapter-source-menu-${action.name}"),
                                    )
                                }
                        }
                    }
                },
            )
            if (state.searchOpen)
                OutlinedTextField(
                    state.request?.query.orEmpty(),
                    actions.query,
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .testTag("chapter-source-query"),
                    label = { Text(stringResource(R.string.screen)) },
                    singleLine = true,
                )
            if (
                state.searching ||
                    state.loading ||
                    state.tocLoading ||
                    state.contentLoading ||
                    state.caching ||
                    state.changing
            ) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("chapter-source-progress"))
                if (state.searching)
                    Text(
                        "${state.completed}/${state.total} ${state.sourceName}",
                        Modifier.padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
            state.error?.let { message ->
                Column(Modifier.fillMaxWidth().padding(8.dp).testTag("chapter-source-error")) {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    TextButton(
                        if (state.cacheRecoveryError) actions.recover else actions.retry,
                        Modifier.testTag("chapter-source-retry"),
                        enabled = !state.loading,
                    ) {
                        Text(
                            if (state.cacheRecoveryError) "重新获取正文"
                            else stringResource(R.string.retry)
                        )
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = sources,
                    modifier = Modifier.fillMaxSize().testTag("chapter-source-results"),
                ) {
                    items(state.rows, key = { it.id }) { row ->
                        Column(
                            Modifier.fillMaxWidth()
                                .combinedClickable(
                                    enabled = !busy && automatic == null,
                                    onClick = { actions.openToc(row.id) },
                                    onLongClick = { selectedRow = row.id },
                                )
                                .padding(12.dp)
                                .testTag("chapter-source-row-${row.id}")
                        ) {
                            Text(
                                (if (row.id == state.request?.currentBookUrl) "✓ " else "") +
                                    row.originName,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(row.author)
                            Text(row.latest)
                            if (state.request?.loadWordCount == true) {
                                row.wordCountText?.takeIf { it.isNotBlank() }?.let { Text(it) }
                                if (row.responseTime >= 0)
                                    Text(stringResource(R.string.respondTime, row.responseTime))
                            }
                            Row {
                                if (row.score >= 0)
                                    TextButton(
                                        { actions.score(row.id, if (row.score == 1) 0 else 1) },
                                        Modifier.testTag("chapter-source-good-${row.id}"),
                                        enabled = !busy,
                                    ) {
                                        Text(if (row.score == 1) "👍 ✓" else "👍")
                                    }
                                if (row.score <= 0)
                                    TextButton(
                                        { actions.score(row.id, if (row.score == -1) 0 else -1) },
                                        Modifier.testTag("chapter-source-bad-${row.id}"),
                                        enabled = !busy,
                                    ) {
                                        Text(if (row.score == -1) "👎 ✓" else "👎")
                                    }
                            }
                        }
                        HorizontalDivider()
                    }
                }
                if (state.tocVisible)
                    Surface(Modifier.fillMaxSize(), tonalElevation = 4.dp) {
                        Column {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    actions.hideToc,
                                    Modifier.testTag("chapter-source-hide-toc"),
                                    enabled =
                                        !(state.batch &&
                                            (state.tocLoading ||
                                                state.caching ||
                                                automatic != null)),
                                ) {
                                    Text("收起目录")
                                }
                                Text(state.toc?.chapters?.size?.toString().orEmpty())
                            }
                            LazyColumn(
                                state = toc,
                                modifier = Modifier.fillMaxSize().testTag("chapter-source-toc"),
                            ) {
                                itemsIndexed(
                                    state.toc?.chapters.orEmpty(),
                                    key = { _, it -> it.key },
                                ) { position, chapter ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .combinedClickable(
                                                enabled =
                                                    !chapter.volume &&
                                                        !state.tocLoading &&
                                                        !state.caching &&
                                                        !state.contentLoading &&
                                                        (!state.batch ||
                                                            automatic == null ||
                                                            automatic.stage == "Paused"),
                                                onClick = { actions.chapter(position) },
                                            )
                                            .padding(12.dp)
                                            .testTag("chapter-source-chapter-$position")
                                            .semantics {
                                                if (chapter.volume) heading()
                                                if (state.batch)
                                                    selected = chapter.index in state.selected
                                            },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (state.batch && !chapter.volume)
                                            Checkbox(chapter.index in state.selected, null)
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                chapter.title,
                                                color =
                                                    if (position == state.toc?.currentIndex)
                                                        MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurface,
                                            )
                                            if (!chapter.volume && !chapter.tag.isNullOrEmpty())
                                                Text(
                                                    chapter.tag,
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                        }
                                        if (!state.batch && position == state.toc?.currentIndex)
                                            Text("✓")
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    {
                        state.rows
                            .indexOfFirst { it.id == state.request?.currentBookUrl }
                            .takeIf { it >= 0 }
                            ?.let { scope.launch { sources.scrollToItem(it, -60) } }
                    },
                    Modifier.weight(1f).testTag("chapter-source-current"),
                ) {
                    Text(state.originName)
                }
                TextButton(
                    { scope.launch { if (state.rows.isNotEmpty()) sources.scrollToItem(0) } },
                    Modifier.testTag("chapter-source-top"),
                ) {
                    Text("↑")
                }
                TextButton(
                    {
                        scope.launch {
                            if (state.rows.isNotEmpty()) sources.scrollToItem(state.rows.lastIndex)
                        }
                    },
                    Modifier.testTag("chapter-source-bottom"),
                ) {
                    Text("↓")
                }
            }
            if (state.batch)
                Row(Modifier.fillMaxWidth()) {
                    TextButton(
                        actions.skip,
                        Modifier.weight(1f).testTag("chapter-source-skip"),
                        enabled = state.currentOriginal != null && !busy,
                    ) {
                        Text(stringResource(R.string.chapter_source_skip))
                    }
                    TextButton(
                        actions.cache,
                        Modifier.weight(1f).testTag("chapter-source-cache"),
                        enabled =
                            state.currentOriginal != null &&
                                state.selected.isNotEmpty() &&
                                !busy &&
                                (automatic == null || automatic.stage == "Paused"),
                    ) {
                        Text(stringResource(R.string.chapter_source_cache_next))
                    }
                    TextButton(
                        actions.close,
                        Modifier.weight(1f).testTag("chapter-source-finish"),
                        enabled = !state.caching,
                    ) {
                        Text(stringResource(R.string.finish))
                    }
                }
        }
    }
    if (groupMenu)
        AlertDialog(
            { groupMenu = false },
            title = { Text(stringResource(R.string.group)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(listOf("") + state.groups, key = { it }) { group ->
                        TextButton(
                            {
                                groupMenu = false
                                actions.group(group)
                            },
                            Modifier.fillMaxWidth().testTag("chapter-source-group-$group"),
                        ) {
                            Text(
                                (if (state.request?.group == group) "✓ " else "") +
                                    if (group.isEmpty()) stringResource(R.string.all_source)
                                    else group
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({ groupMenu = false }) { Text(stringResource(R.string.close)) }
            },
        )
    selectedRow?.let { id ->
        AlertDialog(
            { selectedRow = null },
            title = { Text(state.rows.firstOrNull { it.id == id }?.originName.orEmpty()) },
            text = {
                Column {
                    chapterSourceRowActions.forEach { action ->
                        val label =
                            when (action) {
                                ChapterSourceRowAction.Top -> R.string.to_top
                                ChapterSourceRowAction.Bottom -> R.string.to_bottom
                                ChapterSourceRowAction.Edit -> R.string.edit_source
                                ChapterSourceRowAction.Disable -> R.string.disable_source
                                ChapterSourceRowAction.Delete -> R.string.delete_source
                            }
                        TextButton(
                            {
                                selectedRow = null
                                actions.rowAction(id, action)
                            },
                            Modifier.fillMaxWidth().testTag("chapter-source-action-${action.name}"),
                        ) {
                            Text(
                                stringResource(label),
                                color =
                                    if (action == ChapterSourceRowAction.Delete)
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({ selectedRow = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (state.emptyGroup)
        AlertDialog(
            actions.dismissEmpty,
            title = { Text("搜索结果为空") },
            text = { Text("${state.request?.group}分组搜索结果为空,是否切换到全部分组") },
            confirmButton = {
                TextButton(
                    {
                        actions.dismissEmpty()
                        actions.group("")
                    },
                    Modifier.testTag("chapter-source-empty-all"),
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(actions.dismissEmpty) { Text(stringResource(R.string.cancel)) }
            },
        )
    rangeNotice?.let { message ->
        AlertDialog(
            { rangeNotice = null },
            text = { Text(stringResource(message)) },
            confirmButton = {
                TextButton({ rangeNotice = null }) { Text(stringResource(R.string.ok)) }
            },
        )
    }
    if (rangeOpen)
        AlertDialog(
            { rangeOpen = false },
            title = { Text(stringResource(R.string.chapter_source_automation)) },
            text = {
                Column(Modifier.heightIn(max = 250.dp).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        rangeStart,
                        {
                            rangeStart = it
                            rangeError = false
                        },
                        Modifier.testTag("chapter-source-range-start"),
                        label = { Text("开始章节") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        rangeEnd,
                        {
                            rangeEnd = it
                            rangeError = false
                        },
                        Modifier.testTag("chapter-source-range-end"),
                        label = { Text("结束章节") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    if (rangeError)
                        Text(
                            stringResource(R.string.chapter_source_automation_invalid_range),
                            color = MaterialTheme.colorScheme.error,
                        )
                }
            },
            confirmButton = {
                TextButton(
                    {
                        val start = rangeStart.toIntOrNull()
                        val end = rangeEnd.toIntOrNull()
                        if (start != null && end != null && actions.startAutomation(start, end))
                            rangeOpen = false
                        else rangeError = true
                    },
                    Modifier.testTag("chapter-source-range-confirm"),
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton({ rangeOpen = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
}
