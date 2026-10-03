package io.legado.app.ui.book.read

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal data class ContentEditorActions(
    val close: () -> Unit,
    val save: () -> Unit,
    val reset: () -> Unit,
    val copy: () -> Unit,
    val plain: () -> Unit,
    val searchVisible: (Boolean) -> Unit,
    val query: (String) -> Unit,
    val regex: (Boolean) -> Unit,
    val matchCase: (Boolean) -> Unit,
    val match: (Int) -> Unit,
    val edit: (String, Int, Int) -> Unit,
    val scroll: (Int) -> Unit,
    val openTitle: () -> Unit,
    val editTitle: (String) -> Unit,
    val dismissTitle: () -> Unit,
    val saveTitle: () -> Unit,
    val retry: () -> Unit,
)

@Composable
internal fun ContentEditorScreen(
    state: ContentEditorState,
    actions: ContentEditorActions,
    chapterPos: Int = 0,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState(state.scrollY ?: 0)
    val scope = rememberCoroutineScope()
    val currentActions by rememberUpdatedState(actions)
    var value by remember {
        mutableStateOf(
            TextFieldValue(state.text, TextRange(state.selectionStart, state.selectionEnd))
        )
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val bodyFocus = remember { FocusRequester() }
    val queryFocus = remember { FocusRequester() }
    var initializedScroll by rememberSaveable { mutableStateOf(false) }
    var handledRequest by rememberSaveable { mutableLongStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(state.text, state.selectionStart, state.selectionEnd) {
        if (
            value.text != state.text ||
                value.selection.start != state.selectionStart ||
                value.selection.end != state.selectionEnd
        ) {
            value = TextFieldValue(state.text, TextRange(state.selectionStart, state.selectionEnd))
        }
    }
    LaunchedEffect(state.searchVisible, state.hasDraft) {
        if (state.searchVisible) queryFocus.requestFocus()
        else if (state.hasDraft) bodyFocus.requestFocus()
    }
    LaunchedEffect(state.hasDraft, layout) {
        val result = layout ?: return@LaunchedEffect
        if (state.hasDraft && !initializedScroll && result.layoutInput.text.text == state.text) {
            val offset =
                if (state.plainText) ContentEditProjection(state.raw).displayOffset(chapterPos)
                else chapterPos.coerceIn(0, state.text.length)
            scroll.scrollTo(
                state.scrollY ?: result.getLineTop(result.getLineForOffset(offset)).toInt()
            )
            initializedScroll = true
        }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.value }
            .distinctUntilChanged()
            .collect { if (initializedScroll) currentActions.scroll(it) }
    }
    LaunchedEffect(state.scrollRequest, layout) {
        val result = layout ?: return@LaunchedEffect
        if (state.scrollRequest > handledRequest && result.layoutInput.text.text == state.text) {
            val line = result.getLineForOffset(state.selectionStart.coerceIn(0, state.text.length))
            scroll.scrollTo(
                (result.getLineTop(line).toInt() - scroll.viewportSize / 3).coerceAtLeast(0)
            )
            handledRequest = state.scrollRequest
        }
    }
    val previousLabel = stringResource(R.string.help_search_prev)
    val nextLabel = stringResource(R.string.help_search_next)
    val highlight = MaterialTheme.colorScheme.secondaryContainer
    val active = state.matches.getOrNull(state.matchIndex)
    val transformation =
        remember(active, highlight) {
            VisualTransformation { input ->
                val text = AnnotatedString.Builder(input)
                if (active != null && !active.isEmpty() && active.last < input.length)
                    text.addStyle(SpanStyle(background = highlight), active.first, active.last + 1)
                TransformedText(text.toAnnotatedString(), OffsetMapping.Identity)
            }
        }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = actions.close,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("content-close"),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_baseline_close),
                        stringResource(R.string.close),
                    )
                }
                Text(
                    state.title,
                    Modifier.weight(1f)
                        .clickable(enabled = !state.busy, onClick = actions.openTitle)
                        .padding(8.dp)
                        .testTag("content-title"),
                    maxLines = 2,
                )
                IconButton(
                    onClick = { actions.searchVisible(!state.searchVisible) },
                    enabled = !state.busy,
                    modifier = Modifier.testTag("content-search"),
                ) {
                    Icon(painterResource(R.drawable.ic_search), stringResource(R.string.search))
                }
                IconButton(
                    onClick = actions.save,
                    enabled = state.hasDraft && !state.busy,
                    modifier = Modifier.testTag("content-save"),
                ) {
                    Icon(painterResource(R.drawable.ic_save), stringResource(R.string.action_save))
                }
                Box {
                    TextButton(
                        onClick = { menu = true },
                        enabled = !state.busy,
                        modifier = Modifier.testTag("content-menu"),
                    ) {
                        Text("⋮", fontSize = 24.sp)
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reset)) },
                            onClick = {
                                menu = false
                                actions.reset()
                            },
                            modifier = Modifier.testTag("content-reset"),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy_all)) },
                            onClick = {
                                menu = false
                                actions.copy()
                            },
                            enabled = state.hasDraft,
                            modifier = Modifier.testTag("content-copy"),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.content_edit_plain_text)) },
                            leadingIcon = { Checkbox(state.plainText, null) },
                            onClick = {
                                menu = false
                                actions.plain()
                            },
                            modifier = Modifier.testTag("content-plain"),
                        )
                    }
                }
            }
            if (state.searchVisible) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        state.query,
                        actions.query,
                        Modifier.weight(1f).focusRequester(queryFocus).testTag("content-query"),
                        singleLine = true,
                        label = { Text(stringResource(R.string.search)) },
                        isError = state.searchInvalid,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { actions.match(1) }),
                    )
                    IconButton(
                        onClick = { actions.match(-1) },
                        enabled = state.matches.isNotEmpty(),
                        modifier = Modifier.testTag("content-prev"),
                    ) {
                        Text("↑", Modifier.semantics { contentDescription = previousLabel })
                    }
                    IconButton(
                        onClick = { actions.match(1) },
                        enabled = state.matches.isNotEmpty(),
                        modifier = Modifier.testTag("content-next"),
                    ) {
                        Text("↓", Modifier.semantics { contentDescription = nextLabel })
                    }
                    IconButton(
                        onClick = {
                            actions.searchVisible(false)
                            bodyFocus.requestFocus()
                        },
                        modifier = Modifier.testTag("content-search-close"),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_baseline_close),
                            stringResource(R.string.close),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f).clickable { actions.regex(!state.regex) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(state.regex, actions.regex, Modifier.testTag("content-regex"))
                        Text(stringResource(R.string.regex), fontSize = 12.sp)
                    }
                    Row(
                        Modifier.weight(1f).clickable { actions.matchCase(!state.matchCase) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            state.matchCase,
                            actions.matchCase,
                            Modifier.testTag("content-case"),
                        )
                        Text(stringResource(R.string.content_search_match_case), fontSize = 12.sp)
                    }
                    Text(
                        "${if (state.matchIndex < 0) 0 else state.matchIndex + 1}/${state.matches.size}",
                        Modifier.testTag("content-count").semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                }
                if (state.searchInvalid)
                    Text(
                        stringResource(R.string.content_search_invalid_regex),
                        Modifier.padding(horizontal = 12.dp).testTag("content-query-error"),
                        color = MaterialTheme.colorScheme.error,
                    )
            }
            state.error?.let { error ->
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        error,
                        Modifier.weight(1f).testTag("content-error"),
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (!state.hasDraft)
                        TextButton(
                            onClick = actions.retry,
                            enabled = !state.busy,
                            modifier = Modifier.testTag("content-retry"),
                        ) {
                            Text(stringResource(R.string.retry))
                        }
                }
            }
            if (state.loading || state.saving)
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("content-loading"))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll)) {
                    BasicTextField(
                        value,
                        onValueChange = {
                            value = it
                            actions.edit(it.text, it.selection.start, it.selection.end)
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(12.dp)
                                .focusRequester(bodyFocus)
                                .testTag("content-body"),
                        enabled = state.hasDraft && !state.busy,
                        textStyle =
                            MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 16.sp,
                            ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        visualTransformation = transformation,
                        onTextLayout = { layout = it },
                    )
                }
                val positionLabel = stringResource(R.string.content_edit_position)
                val color = MaterialTheme.colorScheme.primary
                val maximum = scroll.maxValue.takeUnless { it == Int.MAX_VALUE } ?: 0
                val enabled = maximum > 0 && !state.busy
                val setPosition: (Float) -> Unit = { fraction ->
                    if (enabled)
                        scope.launch {
                            scroll.scrollTo((fraction.coerceIn(0f, 1f) * maximum).toInt())
                        }
                }
                Canvas(
                    Modifier.width(40.dp)
                        .fillMaxHeight()
                        .testTag("content-position")
                        .semantics {
                            contentDescription = positionLabel
                            progressBarRangeInfo =
                                ProgressBarRangeInfo(
                                    if (maximum > 0) scroll.value.toFloat() / maximum else 0f,
                                    0f..1f,
                                )
                            if (!enabled) disabled()
                            setProgress {
                                setPosition(it)
                                enabled
                            }
                        }
                        .focusable(enabled)
                        .pointerInput(enabled, maximum) {
                            detectTapGestures { setPosition(it.y / size.height) }
                        }
                        .pointerInput(enabled, maximum) {
                            detectVerticalDragGestures(
                                onDragStart = { setPosition(it.y / size.height) }
                            ) { change, _ ->
                                if (enabled) {
                                    change.consume()
                                    setPosition(change.position.y / size.height)
                                }
                            }
                        }
                ) {
                    drawLine(
                        color.copy(alpha = if (enabled) 0.3f else 0.1f),
                        androidx.compose.ui.geometry.Offset(size.width / 2, 12.dp.toPx()),
                        androidx.compose.ui.geometry.Offset(
                            size.width / 2,
                            size.height - 12.dp.toPx(),
                        ),
                        2.dp.toPx(),
                    )
                    val fraction = if (maximum > 0) scroll.value.toFloat() / maximum else 0f
                    drawCircle(
                        color.copy(alpha = if (enabled) 1f else 0.3f),
                        7.dp.toPx(),
                        androidx.compose.ui.geometry.Offset(
                            size.width / 2,
                            12.dp.toPx() + fraction * (size.height - 24.dp.toPx()),
                        ),
                    )
                }
            }
        }
    }
    if (state.titleEditor)
        AlertDialog(
            onDismissRequest = actions.dismissTitle,
            title = { Text(stringResource(R.string.edit)) },
            text = {
                OutlinedTextField(
                    state.titleInput,
                    actions.editTitle,
                    Modifier.fillMaxWidth().testTag("content-title-input"),
                    enabled = !state.busy,
                )
            },
            confirmButton = {
                TextButton(
                    actions.saveTitle,
                    enabled = !state.busy && state.titleReady,
                    modifier = Modifier.testTag("content-title-save"),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(actions.dismissTitle, enabled = !state.busy) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
}
