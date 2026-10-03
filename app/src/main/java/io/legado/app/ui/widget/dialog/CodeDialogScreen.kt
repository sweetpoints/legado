package io.legado.app.ui.widget.dialog

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
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
import io.legado.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val previousLabel = stringResource(R.string.help_search_prev)
    val nextLabel = stringResource(R.string.help_search_next)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
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
    val syntax by
        produceState(AnnotatedString(state.displayed), state.displayed, colors) {
            this.value =
                withContext(Dispatchers.Default) { projectCodeSyntax(state.displayed, colors) }
        }
    val matchBackground = MaterialTheme.colorScheme.secondary.copy(alpha = .28f)
    val transformation =
        remember(syntax, state.matches, matchBackground) {
            VisualTransformation { text ->
                val annotated = buildAnnotatedString {
                    append(if (syntax.text == text.text) syntax else AnnotatedString(text.text))
                    state.matches.forEach { range ->
                        if (range.first >= 0 && range.last < length)
                            addStyle(
                                SpanStyle(background = matchBackground),
                                range.first,
                                range.last + 1,
                            )
                    }
                }
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
    Surface(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth()) {
                TextButton(
                    onClose,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("code-close"),
                ) {
                    Text(stringResource(R.string.close))
                }
                Text(
                    stringResource(if (editable) R.string.edit_code else R.string.view_code),
                    Modifier.weight(1f),
                    maxLines = 1,
                )
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
            }
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
                        .verticalScroll(scroll)
                        .testTag("code-scroll")
                ) {
                    BasicTextField(
                        value,
                        {
                            composing = it
                            if (it.text == state.displayed)
                                onSelection(it.selection.start, it.selection.end)
                            else onText(it.text, it.selection.start, it.selection.end)
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
                        onTextLayout = { layout = it },
                    )
                }
                CodePositionBar(
                    if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f,
                    scroll.maxValue > 0,
                ) { progress ->
                    scope.launch { scroll.scrollTo((progress * scroll.maxValue).toInt()) }
                }
            }
        }
    }
}
