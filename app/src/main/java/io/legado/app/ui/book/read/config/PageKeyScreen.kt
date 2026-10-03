package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R

@Composable
internal fun PageKeyScreen(
    state: PageKeyUiState,
    background: Color,
    onEdit: (PageKeyField, String) -> Unit,
    onFocus: (PageKeyField, Boolean) -> Unit,
    onReset: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val previousFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    val initialFocus = remember { state.focused ?: PageKeyField.PREVIOUS }
    LaunchedEffect(Unit) {
        if (initialFocus == PageKeyField.PREVIOUS) previousFocus.requestFocus()
        else nextFocus.requestFocus()
    }
    Surface(modifier.imePadding(), color = background) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.custom_page_key),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                state.values.previous,
                { onEdit(PageKeyField.PREVIOUS, it) },
                Modifier.fillMaxWidth()
                    .testTag("page-key-previous")
                    .focusRequester(previousFocus)
                    .onFocusChanged { onFocus(PageKeyField.PREVIOUS, it.isFocused) },
                enabled = !state.finished,
                singleLine = true,
                label = { Text(stringResource(R.string.prev_page_key)) },
            )
            OutlinedTextField(
                state.values.next,
                { onEdit(PageKeyField.NEXT, it) },
                Modifier.fillMaxWidth()
                    .testTag("page-key-next")
                    .focusRequester(nextFocus)
                    .onFocusChanged { onFocus(PageKeyField.NEXT, it.isFocused) },
                enabled = !state.finished,
                singleLine = true,
                label = { Text(stringResource(R.string.next_page_key)) },
            )
            Text(
                stringResource(R.string.page_key_set_help),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.error?.let {
                Text(
                    it,
                    Modifier.testTag("page-key-error"),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onReset,
                    Modifier.weight(1f).testTag("page-key-reset"),
                    enabled = !state.finished,
                ) {
                    Text(stringResource(R.string.reset))
                }
                TextButton(
                    onConfirm,
                    Modifier.weight(1f).testTag("page-key-confirm"),
                    enabled = !state.finished,
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        }
    }
}
