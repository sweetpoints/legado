package io.legado.app.ui.file

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.HandleFileChoice
import io.legado.app.data.repository.handleFileChoiceValues

@Composable
fun HandleFileChoicesScreen(
    state: HandleFileChoicesState,
    choose: (Int, String?) -> Unit,
    textChanged: (String, Int, Int) -> Unit,
    confirm: () -> Unit,
    retry: () -> Unit,
    close: () -> Unit,
) {
    if (
        state.finished ||
            (state.phase == "Result" && state.error == null) ||
            (state.phase == "Native" && state.error == null)
    )
        return
    val input = state.input
    val title =
        input?.title
            ?: stringResource(
                when (input?.mode) {
                    HandleFileContract.EXPORT -> R.string.export
                    HandleFileContract.DIR -> R.string.select_folder
                    HandleFileContract.IMAGE -> R.string.select_image
                    else -> R.string.select_file
                }
            )
    val manual = state.loaded && state.phase == "Manual"
    AlertDialog(
        onDismissRequest = { if (!state.busy) close() },
        modifier = Modifier.testTag("handle-file-dialog"),
        title = { Text(if (manual) stringResource(R.string.manual_input) else title) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.testTag("handle-file-progress"))
                }
                state.error?.let { Text(it, Modifier.testTag("handle-file-error")) }
                if (manual) {
                    HandleFileManualField(state, textChanged)
                } else if (state.loaded && state.phase == "Choices") {
                    val choices =
                        handleFileChoiceValues(input!!.mode).map { action ->
                            HandleFileChoice(stringResource(handleFileActionLabel(action)), action)
                        } + input.otherActions
                    choices.forEachIndexed { index, choice ->
                        Text(
                            choice.title,
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(enabled = !state.busy) {
                                    choose(choice.value, choice.title)
                                }
                                .padding(horizontal = 8.dp, vertical = 12.dp)
                                .testTag("handle-file-choice-$index"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (manual) {
                TextButton(
                    onClick = confirm,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("handle-file-confirm"),
                ) {
                    Text(stringResource(R.string.ok))
                }
            } else if (state.error != null) {
                TextButton(
                    onClick = retry,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("handle-file-retry"),
                ) {
                    Text(stringResource(R.string.retry))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = close, enabled = !state.busy) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun HandleFileManualField(
    state: HandleFileChoicesState,
    textChanged: (String, Int, Int) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var editor by remember {
        mutableStateOf(TextFieldValue(state.draft, TextRange(state.start, state.end)))
    }
    LaunchedEffect(state.draft, state.start, state.end) {
        val selection = TextRange(state.start, state.end)
        if (editor.text != state.draft || editor.selection != selection) {
            editor = editor.copy(text = state.draft, selection = selection)
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OutlinedTextField(
        value = editor,
        onValueChange = {
            editor = it
            textChanged(it.text, it.selection.start, it.selection.end)
        },
        enabled = !state.busy,
        label = {
            Text(
                stringResource(
                    if (state.pending?.action == 113) R.string.enter_img_src_path
                    else R.string.enter_directory_path
                )
            )
        },
        modifier =
            Modifier.fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag("handle-file-manual-input"),
    )
}

private fun handleFileActionLabel(action: Int): Int =
    when (action) {
        0 -> R.string.sys_folder_picker
        1 -> R.string.sys_file_picker
        4 -> R.string.sys_image_picker
        10 -> R.string.app_folder_picker
        11 -> R.string.app_file_picker
        111 -> R.string.upload_url
        112 -> R.string.manual_input
        113 -> R.string.manual_input_img_src
        else -> error("Unsupported built-in file action: $action")
    }
