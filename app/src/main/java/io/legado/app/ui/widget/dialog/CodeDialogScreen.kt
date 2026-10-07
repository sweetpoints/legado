package io.legado.app.ui.widget.dialog

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import io.legado.app.BuildConfig
import io.legado.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodeDialogScreen(
    state: CodeDialogState,
    editable: Boolean,
    sourcePreview: Boolean,
    manualEnabled: Boolean,
    onText: (String, Int, Int) -> Unit,
    onSelection: (Int, Int) -> Unit,
    onPreview: (Boolean) -> Unit,
    onSearchOpen: (Boolean) -> Unit,
    onSearch: (String) -> Unit,
    onMatch: (Int) -> Unit,
    onAction: (CodeDialogAction) -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val performance = remember { if (BuildConfig.DEBUG) CodePreviewPerformance() else null }
    val compositionStart = performance?.start()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val previousLabel = stringResource(R.string.help_search_prev)
    val nextLabel = stringResource(R.string.help_search_next)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var composing by remember { mutableStateOf(TextFieldValue()) }
    var menuOpen by remember { mutableStateOf(false) }
    val selection = TextRange(state.selectionStart, state.selectionEnd)
    val value =
        if (composing.text == state.displayed && composing.selection == selection) composing
        else TextFieldValue(state.displayed, selection)
    val colors =
        CodeSyntaxColors(
            colorResource(R.color.md_orange_900),
            colorResource(R.color.md_blue_800),
            colorResource(R.color.md_blue_grey_500),
            colorResource(R.color.md_orange_900),
            colorResource(R.color.md_light_blue_600),
        )
    // Android's immutable SpannableString scans every span when drawing each text run.
    // Keep the document and UTF-16 offsets intact, but style the settled viewport plus
    // one screen of overscan without recoloring the entire document during scroll motion.
    var projection by remember {
        mutableStateOf(CodeViewportSyntax(AnnotatedString(state.displayed), IntRange.EMPTY, null))
    }
    LaunchedEffect(state.displayed, colors, state.selectionStart, state.selectionEnd) {
        snapshotFlow {
            val result = layout
            if (
                result == null ||
                    result.layoutInput.text.text != state.displayed ||
                    viewportHeight == 0
            ) {
                val start =
                    minOf(state.selectionStart, state.selectionEnd)
                        .coerceIn(0, state.displayed.length)
                val end =
                    maxOf(state.selectionStart, state.selectionEnd)
                        .coerceIn(start, state.displayed.length)
                CodeSyntaxViewport(
                    start until end,
                    (start - 2048).coerceAtLeast(0) until
                        (end + 2048).coerceAtMost(state.displayed.length),
                    scroll.isScrollInProgress,
                )
            } else {
                fun range(top: Int, bottom: Int): IntRange {
                    val first = result.getLineForVerticalPosition(top.coerceAtLeast(0).toFloat())
                    val last = result.getLineForVerticalPosition(bottom.toFloat())
                    return result.getLineStart(first) until result.getLineEnd(last)
                }
                CodeSyntaxViewport(
                    range(scroll.value, scroll.value + viewportHeight),
                    range(scroll.value - viewportHeight, scroll.value + viewportHeight * 2),
                    scroll.isScrollInProgress,
                )
            }
        }
            .collectLatest { viewport ->
                val current = projection
                val freshTextOrColors =
                    current.text.text != state.displayed || current.colors != colors
                // Animated caret relocation and dragging must not continually recolor and lay out
                // the entire document. New input/theme still gets styled, then motion's final
                // viewport publishes its accurate overscan as soon as the scroll mutation ends.
                if (viewport.scrolling && !freshTextOrColors) return@collectLatest
                // The existing screen of overscan already styles these visible lines. Moving inside
                // it must not publish another AnnotatedString and lay out the whole document again.
                if (
                    !freshTextOrColors &&
                        current.settledViewport &&
                        current.range.first <= viewport.visible.first &&
                        current.range.last >= viewport.visible.last
                )
                    return@collectLatest
                val targetRange =
                    if (viewport.scrolling) {
                        val start = minOf(state.selectionStart, state.selectionEnd)
                        val end = maxOf(state.selectionStart, state.selectionEnd)
                        (start - 2048).coerceAtLeast(0) until
                            (end + 2048).coerceAtMost(state.displayed.length)
                    } else viewport.overscan
                val projectionStart = performance?.start()
                performance?.record(
                    "syntax-start",
                    state.displayed.length,
                    "range=$targetRange scrolling=${viewport.scrolling}",
                )
                var computeMs = 0L
                val projected =
                    withContext(Dispatchers.Default) {
                        val computeStart = performance?.start()
                        projectCodeSyntax(state.displayed, colors, targetRange).also {
                            if (computeStart != null)
                                computeMs = checkNotNull(performance).start() - computeStart
                        }
                    }
                performance?.record(
                    "syntax-ready",
                    projected.length,
                    "range=$targetRange spans=${projected.spanStyles.size} computeMs=$computeMs",
                    projectionStart,
                )
                projection = CodeViewportSyntax(projected, targetRange, colors, !viewport.scrolling)
            }
    }
    val syntax = projection.text
    val syntaxRange = projection.range
    val matchBackground = MaterialTheme.colorScheme.secondary.copy(alpha = .28f)
    val transformation =
        remember(syntax, state.matches, matchBackground, syntaxRange) {
            VisualTransformation { text ->
                val transformStart = performance?.start()
                val source = if (syntax.text == text.text) syntax else text
                val annotated =
                    if (state.matches.isEmpty()) source
                    else
                        buildAnnotatedString {
                            append(source)
                            state.matches.forEach { range ->
                                val start = maxOf(range.first, syntaxRange.first)
                                val end = minOf(range.last + 1, syntaxRange.last + 1, length)
                                if (start >= 0 && start < end)
                                    addStyle(
                                        SpanStyle(background = matchBackground),
                                        start,
                                        end,
                                    )
                            }
                        }
                performance?.record(
                    "transform",
                    text.length,
                    "syntaxCurrent=${syntax.text == text.text} spans=${annotated.spanStyles.size}",
                    transformStart,
                )
                TransformedText(annotated, OffsetMapping.Identity)
            }
        }
    LaunchedEffect(state.selectionStart, state.selectionEnd, state.matchIndex, layout) {
        if (state.searchOpen && state.matchIndex >= 0)
            layout?.let { result ->
                val rectangle =
                    result.getCursorRect(
                        state.selectionStart.coerceIn(0, result.layoutInput.text.length)
                    )
                scroll.animateScrollTo(rectangle.top.toInt().coerceIn(0, scroll.maxValue))
            }
    }
    SideEffect {
        performance?.record(
            "compose",
            state.displayed.length,
            "selection=${state.selectionStart}..${state.selectionEnd} viewport=$viewportHeight",
            compositionStart,
        )
    }
    Surface(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (editable) R.string.edit_code else R.string.view_code),
                        Modifier,
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClose,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("code-close"),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_baseline_close),
                            stringResource(R.string.close),
                        )
                    }
                },
                actions = {
                    IconButton(
                        { onSearchOpen(!state.searchOpen) },
                        Modifier.testTag("code-search-toggle"),
                    ) {
                        Icon(painterResource(R.drawable.ic_search), stringResource(R.string.search))
                    }
                    if (editable && !state.searchOpen) {
                        IconButton(
                            { onAction(CodeDialogAction.Editor) },
                            enabled = state.loaded && !state.busy,
                            modifier = Modifier.testTag("code-fullscreen"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_edit),
                                stringResource(R.string.view_in_code_editor),
                            )
                        }
                        if (!state.showingAlternate || sourcePreview)
                            IconButton(
                                { onAction(CodeDialogAction.Save) },
                                enabled = state.loaded && !state.busy,
                                modifier = Modifier.testTag("code-save"),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_save),
                                    stringResource(R.string.action_save),
                                )
                            }
                    }
                    if (sourcePreview)
                        Box {
                            IconButton(
                                { menuOpen = true },
                                enabled = state.loaded && !state.busy,
                                modifier = Modifier.testTag("code-menu"),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_more_vert),
                                    stringResource(R.string.menu),
                                )
                            }
                            DropdownMenu(menuOpen, { menuOpen = false }) {
                                listOf(
                                        CodeDialogAction.ReplaceRules to R.string.menu_replace_rule,
                                        CodeDialogAction.Effective to R.string.effective_replaces,
                                        CodeDialogAction.Manual to R.string.manual_replace_rule,
                                    )
                                    .forEach { (action, label) ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(label)) },
                                            onClick = {
                                                menuOpen = false
                                                onAction(action)
                                            },
                                            enabled =
                                                action != CodeDialogAction.Manual || manualEnabled,
                                            modifier = Modifier.testTag("code-action-$action"),
                                        )
                                    }
                            }
                        }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
            if (state.searchOpen)
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    OutlinedTextField(
                        state.query,
                        onSearch,
                        Modifier.weight(1f).testTag("code-query"),
                        singleLine = true,
                        label = { Text(stringResource(R.string.search)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions =
                            KeyboardActions(onSearch = { onMatch(state.matchIndex + 1) }),
                    )
                    TextButton(
                        { onMatch(state.matchIndex - 1) },
                        enabled = state.matches.isNotEmpty(),
                        modifier =
                            Modifier.testTag("code-previous").semantics {
                                contentDescription = previousLabel
                            },
                    ) {
                        Text("↑")
                    }
                    TextButton(
                        { onMatch(state.matchIndex + 1) },
                        enabled = state.matches.isNotEmpty(),
                        modifier =
                            Modifier.testTag("code-next").semantics {
                                contentDescription = nextLabel
                            },
                    ) {
                        Text("↓")
                    }
                }
            if (editable && state.alternate != null)
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(
                        state.showingAlternate,
                        onPreview,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("code-preview-toggle"),
                    )
                    Text(stringResource(R.string.show_source_replacement))
                }
            if (!state.loaded || state.busy)
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("code-working"))
            state.error?.let { error ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        error,
                        Modifier.weight(1f).testTag("code-error"),
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (!state.loaded)
                        TextButton(onRetry, Modifier.testTag("code-retry")) {
                            Text(stringResource(R.string.retry))
                        }
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .onSizeChanged { viewportHeight = it.height }
                        .verticalScroll(scroll)
                        .testTag("code-scroll")
                ) {
                    BasicTextField(
                        value,
                        {
                            val textChanged = it.text != state.displayed
                            if (textChanged) performance?.input(it.text.length)
                            val callbackStart = performance?.start()
                            composing = it
                            if (it.text == state.displayed)
                                onSelection(it.selection.start, it.selection.end)
                            else onText(it.text, it.selection.start, it.selection.end)
                            performance?.record(
                                if (textChanged) "input-model" else "selection-model",
                                it.text.length,
                                "selection=${it.selection}",
                                callbackStart,
                            )
                        },
                        Modifier.fillMaxWidth().padding(12.dp).testTag("code-body"),
                        enabled = state.loaded,
                        readOnly = !editable || state.showingAlternate || state.busy,
                        textStyle =
                            MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.Monospace,
                            ),
                        keyboardOptions =
                            KeyboardOptions(
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Text,
                            ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary),
                        visualTransformation = transformation,
                        onTextLayout = {
                            performance?.layout(it)
                            layout = it
                        },
                    )
                }
                CodeDialogPositionBar(scroll) { progress ->
                    scope.launch { scroll.scrollTo((progress * scroll.maxValue).toInt()) }
                }
            }
        }
    }
}

private data class CodeSyntaxViewport(
    val visible: IntRange,
    val overscan: IntRange,
    val scrolling: Boolean,
)

private data class CodeViewportSyntax(
    val text: AnnotatedString,
    val range: IntRange,
    val colors: CodeSyntaxColors?,
    val settledViewport: Boolean = false,
)

@Composable
private fun CodeDialogPositionBar(scroll: ScrollState, onProgress: (Float) -> Unit) {
    CodePositionBar(
        if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f,
        scroll.maxValue > 0,
        onProgress,
    )
}

/** Debug-only timings begin in the actual input callback, excluding test/IME synchronization. */
internal data class CodePreviewInputTiming(
    val session: String,
    val edit: Int,
    val characters: Int,
    val modelMs: Long? = null,
    val firstLayoutMs: Long? = null,
)

internal object CodePreviewInputMetrics {
    @Volatile
    var last: CodePreviewInputTiming? = null
        internal set
}

/** Debug-only metadata; never records code, and bounds logging for each long-document edit. */
private class CodePreviewPerformance {
    private val session = System.identityHashCode(this).toString(16)
    private var edit = 0
    private var editStart = 0L
    private var layouts = 0
    private var samples = 0

    fun start(): Long = SystemClock.uptimeMillis()

    fun input(length: Int) {
        edit++
        editStart = start()
        CodePreviewInputMetrics.last = CodePreviewInputTiming(session, edit, length)
        layouts = 0
        samples = 0
        record("input", length, "")
    }

    fun layout(result: TextLayoutResult) {
        val input = CodePreviewInputMetrics.last
        if (
            input?.session == session &&
                input.edit == edit &&
                input.characters == result.layoutInput.text.length &&
                input.firstLayoutMs == null
        ) {
            CodePreviewInputMetrics.last = input.copy(firstLayoutMs = start() - editStart)
        }
        layouts++
        record(
            "layout",
            result.layoutInput.text.length,
            "count=$layouts lines=${result.lineCount} spans=${result.layoutInput.text.spanStyles.size} " +
                "size=${result.size}",
        )
    }

    fun record(phase: String, length: Int, details: String, started: Long? = null) {
        if (phase == "input-model" && started != null) {
            val input = CodePreviewInputMetrics.last
            if (input?.session == session && input.edit == edit) {
                CodePreviewInputMetrics.last = input.copy(modelMs = start() - started)
            }
        }
        if (length < 100_000 || samples >= 120) return
        samples++
        val now = start()
        Log.d(
            "CodePreviewPerf",
            "session=$session edit=$edit phase=$phase chars=$length " +
                "editMs=${if (editStart == 0L) -1 else now - editStart} " +
                "phaseMs=${started?.let { now - it } ?: -1} $details",
        )
    }
}
