package io.legado.app.ui.book.bookmark

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookmarkEditorScreen(
    state: BookmarkEditorState,
    bookText: (String, Int, Int) -> Unit,
    content: (String, Int, Int) -> Unit,
    confirm: () -> Unit,
    delete: () -> Unit,
    cancel: () -> Unit,
    retry: () -> Unit,
) {
    val textFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    var focused by rememberSaveable { mutableStateOf("bookText") }
    val restoredFocus = remember { focused }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.loaded) {
        if (state.loaded && state.canEdit) {
            if (restoredFocus == "content") contentFocus.requestFocus()
            else textFocus.requestFocus()
            keyboard?.show()
        }
    }
    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding().testTag("bookmark-editor")) {
        Box(
            Modifier.matchParentSize()
                .clickable(
                    enabled = !state.busy,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = cancel,
                )
                .testTag("bookmark-editor-outside")
        )
        Surface(
            Modifier.align(Alignment.Center).padding(16.dp).fillMaxWidth().pointerInput(Unit) {
                detectTapGestures(onTap = {})
            },
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text(
                        stringResource(R.string.bookmark),
                        Modifier.fillMaxWidth().padding(16.dp).testTag("bookmark-editor-heading"),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Text(
                    state.chapter,
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("bookmark-editor-chapter"),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (state.loading || state.busy)
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().testTag("bookmark-editor-working")
                    )
                Column(
                    Modifier.weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.error?.let {
                        Text(
                            it,
                            Modifier.testTag("bookmark-editor-error"),
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (!state.loaded && !state.loading)
                            TextButton(
                                onClick = retry,
                                modifier = Modifier.testTag("bookmark-editor-retry"),
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                    }
                    BookmarkEditorField(
                        state.bookText,
                        state.textStart,
                        state.textEnd,
                        stringResource(R.string.content),
                        state.canEdit,
                        bookText,
                        Modifier.focusRequester(textFocus)
                            .onFocusChanged { if (it.isFocused) focused = "bookText" }
                            .testTag("bookmark-editor-text"),
                        KeyboardOptions(imeAction = ImeAction.Next),
                        KeyboardActions(onNext = { contentFocus.requestFocus() }),
                    )
                    BookmarkEditorField(
                        state.content,
                        state.contentStart,
                        state.contentEnd,
                        stringResource(R.string.note_content),
                        state.canEdit,
                        content,
                        Modifier.focusRequester(contentFocus)
                            .onFocusChanged { if (it.isFocused) focused = "content" }
                            .testTag("bookmark-editor-content"),
                        KeyboardOptions(imeAction = ImeAction.Done),
                        KeyboardActions(onDone = { keyboard?.hide() }),
                    )
                }
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (state.canDelete)
                        TextButton(
                            onClick = delete,
                            enabled = state.canEdit,
                            modifier = Modifier.testTag("bookmark-editor-delete"),
                        ) {
                            Text(stringResource(R.string.delete))
                        }
                    else Spacer(Modifier.width(64.dp))
                    Row {
                        TextButton(
                            onClick = cancel,
                            enabled = !state.busy,
                            modifier = Modifier.testTag("bookmark-editor-cancel"),
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = confirm,
                            enabled = state.canEdit,
                            modifier = Modifier.testTag("bookmark-editor-confirm"),
                        ) {
                            Text(stringResource(R.string.ok))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkEditorField(
    text: String,
    start: Int,
    end: Int,
    label: String,
    enabled: Boolean,
    change: (String, Int, Int) -> Unit,
    modifier: Modifier,
    keyboard: KeyboardOptions,
    actions: KeyboardActions,
) {
    var value by remember { mutableStateOf(TextFieldValue(text, TextRange(start, end))) }
    LaunchedEffect(text, start, end) {
        if (value.text != text || value.selection != TextRange(start, end))
            value =
                value.copy(
                    text = text,
                    selection = TextRange(start, end),
                    composition = if (value.text == text) value.composition else null,
                )
    }
    OutlinedTextField(
        value,
        onValueChange = { next ->
            value = next
            change(next.text, next.selection.start, next.selection.end)
        },
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        maxLines = 4,
        keyboardOptions = keyboard,
        keyboardActions = actions,
    )
}
