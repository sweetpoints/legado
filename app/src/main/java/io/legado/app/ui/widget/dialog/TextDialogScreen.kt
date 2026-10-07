package io.legado.app.ui.widget.dialog

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.MarkdownImageRepository
import io.legado.app.ui.components.markdown.*
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextDialogScreen(
    state: TextDialogState,
    images: MarkdownImageRepository,
    simpleMarkdown: List<MarkdownBlock>?,
    link: (String) -> Unit,
    inspect: (String) -> Unit,
    close: () -> Unit,
    edit: () -> Unit,
    search: () -> Unit,
    query: (String) -> Unit,
    move: (Int) -> Unit,
    toc: (Boolean) -> Unit,
    section: (Int) -> Unit,
    retry: () -> Unit,
    scrolled: (Long, Int) -> Unit,
    position: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val direction = LocalLayoutDirection.current
    val scroll = rememberScrollState()
    var menu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val rendererMatches =
        remember(state.matches) {
            state.matches.map {
                RichTextMatch(
                    it.parts.map { part -> RichTextMatchPart(part.leaf, part.start, part.end) }
                )
            }
        }
    val positions = remember(state.document) { mutableMapOf<Pair<Int, Int>, RichTextGeometry>() }
    var geometryVersion by remember(state.document) { mutableIntStateOf(0) }
    var laidOut by remember { mutableStateOf<RichDocument?>(null) }
    val currentScrolled by rememberUpdatedState(scrolled)
    val currentPosition by rememberUpdatedState(position)
    LaunchedEffect(state.tocVisible) { if (state.tocVisible) drawer.open() else drawer.close() }
    LaunchedEffect(drawer) {
        snapshotFlow { drawer.currentValue }
            .distinctUntilChanged()
            .collect { toc(it == DrawerValue.Open) }
    }
    LaunchedEffect(state.searchVisible) {
        if (state.searchVisible) {
            withFrameNanos {}
            focus.requestFocus()
            keyboard?.show()
        } else keyboard?.hide()
    }
    LaunchedEffect(state.scroll?.id, state.loading, geometryVersion, scroll.maxValue, laidOut) {
        val request = state.scroll ?: return@LaunchedEffect
        if (state.loading || laidOut !== state.document) return@LaunchedEffect
        val y =
            request.y
                ?: positions.values
                    .firstOrNull {
                        it.leaf == request.leaf &&
                            request.offset >= it.start &&
                            request.offset < it.end
                    }
                    ?.let {
                        val offset =
                            (request.offset - it.start).coerceIn(
                                0,
                                it.layout.layoutInput.text.length,
                            )
                        (it.y + it.layout.getLineTop(it.layout.getLineForOffset(offset))).toInt()
                    }
                ?: return@LaunchedEffect
        scroll.scrollTo(y.coerceIn(0, scroll.maxValue))
        currentScrolled(request.id, scroll.value)
    }
    LaunchedEffect(scroll, state.document) {
        snapshotFlow { scroll.value }.distinctUntilChanged().collect { currentPosition(it) }
    }
    CompositionLocalProvider(
        LocalLayoutDirection provides
            if (direction == LayoutDirection.Ltr) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = state.help && !state.loading,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    ModalDrawerSheet(Modifier.widthIn(max = 320.dp).testTag("text-toc")) {
                        Text(
                            stringResource(R.string.chapter_list),
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        LazyColumn {
                            item {
                                NavigationDrawerItem(
                                    { Text(stringResource(R.string.all)) },
                                    state.selectedSection == 0,
                                    { section(0) },
                                    Modifier.testTag("text-toc-0"),
                                )
                            }
                            itemsIndexed(state.sections) { index, value ->
                                NavigationDrawerItem(
                                    label = {
                                        Text(
                                            value.title,
                                            Modifier.padding(start = (value.depth * 16).dp),
                                        )
                                    },
                                    selected = state.selectedSection == index + 1,
                                    onClick = { section(index + 1) },
                                    modifier = Modifier.testTag("text-toc-${index + 1}"),
                                )
                            }
                        }
                    }
                }
            },
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Surface(modifier.fillMaxSize()) {
                    Column {
                        TopAppBar(
                            title = {
                                Text(
                                    state.request?.title.orEmpty(),
                                    modifier = Modifier.testTag("text-title"),
                                )
                            },
                            actions = {
                                if (state.remaining > 0)
                                    Text(
                                        (state.remaining / 1000).toString(),
                                        Modifier.padding(vertical = 16.dp)
                                            .testTag("text-countdown"),
                                    )
                                Box {
                                    IconButton({ menu = true }, Modifier.testTag("text-menu")) {
                                        Icon(
                                            painterResource(R.drawable.ic_more_vert),
                                            stringResource(R.string.menu),
                                        )
                                    }
                                    DropdownMenu(menu, { menu = false }) {
                                        if (state.help) {
                                            DropdownMenuItem(
                                                { Text(stringResource(R.string.search)) },
                                                {
                                                    menu = false
                                                    search()
                                                },
                                                Modifier.testTag("text-menu-search"),
                                                enabled = !state.loading,
                                            )
                                            DropdownMenuItem(
                                                { Text(stringResource(R.string.chapter_list)) },
                                                {
                                                    menu = false
                                                    toc(true)
                                                },
                                                Modifier.testTag("text-menu-toc"),
                                                enabled = !state.loading,
                                            )
                                        }
                                        DropdownMenuItem(
                                            { Text(stringResource(R.string.edit_content)) },
                                            {
                                                menu = false
                                                edit()
                                            },
                                            Modifier.testTag("text-menu-edit"),
                                            enabled = !state.loading && state.request != null,
                                        )
                                        DropdownMenuItem(
                                            { Text(stringResource(R.string.close)) },
                                            {
                                                menu = false
                                                close()
                                            },
                                            Modifier.testTag("text-menu-close"),
                                        )
                                    }
                                }
                            },
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            colors = TopAppBarDefaults.topAppBarColors(),
                        )
                        if (state.searchVisible && state.help)
                            Row(Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    state.query,
                                    query,
                                    Modifier.weight(1f)
                                        .focusRequester(focus)
                                        .testTag("text-search-input"),
                                    singleLine = true,
                                    placeholder = { Text(stringResource(R.string.search)) },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { move(1) }),
                                )
                                Text(
                                    if (state.query.isBlank()) ""
                                    else "${state.matchIndex + 1}/${state.matches.size}",
                                    Modifier.padding(12.dp).testTag("text-search-count"),
                                )
                                IconButton(
                                    { move(-1) },
                                    Modifier.testTag("text-search-prev"),
                                    enabled =
                                        !state.loading &&
                                            !state.searching &&
                                            state.matches.isNotEmpty(),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_arrow_down),
                                        stringResource(R.string.help_search_prev),
                                        Modifier.rotate(180f),
                                    )
                                }
                                IconButton(
                                    { move(1) },
                                    Modifier.testTag("text-search-next"),
                                    enabled =
                                        !state.loading &&
                                            !state.searching &&
                                            state.matches.isNotEmpty(),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_arrow_down),
                                        stringResource(R.string.help_search_next),
                                    )
                                }
                            }
                        if (state.loading || state.searching)
                            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("text-working"))
                        state.error?.let {
                            Text(
                                it,
                                Modifier.padding(12.dp).testTag("text-error"),
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(retry, Modifier.testTag("text-retry")) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                        Box(
                            Modifier.weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(scroll)
                                .testTag("text-scroll")
                        ) {
                            if (!state.loading)
                                Box(
                                    Modifier.fillMaxWidth().padding(12.dp).onGloballyPositioned {
                                        laidOut = state.document
                                    }
                                ) {
                                    if (simpleMarkdown != null)
                                        ComposeMarkdown(
                                            simpleMarkdown,
                                            images,
                                            link,
                                            Modifier.testTag("text-body"),
                                        )
                                    else
                                        SearchableRichText(
                                            state.document,
                                            rendererMatches,
                                            state.matchIndex,
                                            images,
                                            link,
                                            inspect,
                                            { value ->
                                                val key = value.leaf to value.start
                                                if (positions[key] != value) {
                                                    positions[key] = value
                                                    geometryVersion++
                                                }
                                            },
                                            Modifier.testTag("text-body"),
                                        )
                                }
                        }
                    }
                }
            }
        }
    }
}
