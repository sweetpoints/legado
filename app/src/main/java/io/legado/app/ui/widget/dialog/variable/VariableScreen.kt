package io.legado.app.ui.widget.dialog.variable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.components.LegadoTopAppBar

@Composable
fun VariableScreen(
    state: VariableUiState,
    onInput: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val closeLabel = stringResource(R.string.close)
    BoxWithConstraints(modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize()
                .testTag("variable-backdrop")
                .clickable(onClickLabel = closeLabel, onClick = onClose)
        )
        Surface(
            onClick = {},
            modifier =
                Modifier.padding(16.dp)
                    .fillMaxWidth()
                    .heightIn(max = (maxHeight - 32.dp).coerceAtLeast(1.dp))
                    .testTag("variable-card"),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LegadoTopAppBar(
                    state.title,
                    onClose,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    actions = {
                        IconButton(
                            onSave,
                            enabled = !state.finished && !state.saveRequested,
                            modifier = Modifier.testTag("variable-save"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_save),
                                stringResource(R.string.action_save),
                            )
                        }
                    },
                )
                OutlinedTextField(
                    state.input,
                    onInput,
                    enabled = !state.finished && !state.saveRequested,
                    minLines = 3,
                    maxLines = 8,
                    label = { Text(stringResource(R.string.variable_dialog_input_label)) },
                    modifier =
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            .fillMaxWidth()
                            .testTag("variable-input"),
                )
                Text(
                    stringResource(R.string.variable_comment),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                SelectionContainer(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        .testTag("variable-comment")
                ) {
                    Text(state.comment, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
