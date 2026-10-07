package io.legado.app.ui.rss.favorites

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
internal fun RssFavoriteConfigScreen(
    state: RssFavoriteConfigState,
    title: (String, Int, Int) -> Unit,
    group: (String, Int, Int) -> Unit,
    confirm: () -> Unit,
    delete: () -> Unit,
    cancel: () -> Unit,
    retry: () -> Unit,
) {
    val titleFocus = remember { FocusRequester() }
    val groupFocus = remember { FocusRequester() }
    var focused by rememberSaveable { mutableStateOf("title") }
    val restoredFocus = remember { focused }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.loaded) {
        if (state.loaded && state.canEdit) {
            if (restoredFocus == "group") groupFocus.requestFocus() else titleFocus.requestFocus()
            keyboard?.show()
        }
    }
    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding().testTag("rss-favorite-config")) {
        Box(
            Modifier.matchParentSize()
                .clickable(
                    enabled = !state.busy,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = cancel,
                )
                .testTag("favorite-config-outside")
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
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Text(
                        stringResource(R.string.favorite),
                        Modifier.fillMaxWidth().padding(16.dp).testTag("favorite-config-heading"),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                if (state.loading || state.busy)
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().testTag("favorite-config-working")
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
                            Modifier.testTag("favorite-config-error"),
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (!state.loaded && !state.loading)
                            TextButton(
                                onClick = retry,
                                modifier = Modifier.testTag("favorite-config-retry"),
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                    }
                    FavoriteConfigField(
                        state.title,
                        state.titleStart,
                        state.titleEnd,
                        stringResource(R.string.title),
                        state.canEdit,
                        title,
                        Modifier.focusRequester(titleFocus)
                            .onFocusChanged { if (it.isFocused) focused = "title" }
                            .testTag("favorite-config-title"),
                        KeyboardOptions(imeAction = ImeAction.Next),
                        KeyboardActions(onNext = { groupFocus.requestFocus() }),
                    )
                    FavoriteConfigField(
                        state.group,
                        state.groupStart,
                        state.groupEnd,
                        stringResource(R.string.group_name),
                        state.canEdit,
                        group,
                        Modifier.focusRequester(groupFocus)
                            .onFocusChanged { if (it.isFocused) focused = "group" }
                            .testTag("favorite-config-group"),
                        KeyboardOptions(imeAction = ImeAction.Done),
                        KeyboardActions(onDone = { keyboard?.hide() }),
                    )
                }
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        onClick = delete,
                        enabled = state.canEdit,
                        modifier = Modifier.testTag("favorite-config-delete"),
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                    Row {
                        TextButton(
                            onClick = cancel,
                            enabled = !state.busy,
                            modifier = Modifier.testTag("favorite-config-cancel"),
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = confirm,
                            enabled = state.canEdit,
                            modifier = Modifier.testTag("favorite-config-confirm"),
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
private fun FavoriteConfigField(
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
