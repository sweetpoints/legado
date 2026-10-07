package io.legado.app.ui.file

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.LocalFilePickerIssue
import io.legado.app.ui.file.utils.FilePickerIcon
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalFilePickerScreen(
    state: LocalFilePickerState,
    title: String,
    root: String,
    click: (String) -> Unit,
    navigate: (String) -> Unit,
    confirm: () -> Unit,
    retry: () -> Unit,
    showCreate: () -> Unit,
    folderName: (String, Int, Int) -> Unit,
    create: () -> Unit,
    cancelCreate: () -> Unit,
    position: (String) -> Pair<Int, Int>,
    scrolled: (String, Int, Int) -> Unit,
) {
    val errorText = state.issue?.let { pickerIssueText(it) } ?: state.error
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().testTag("local-file-picker")) {
            TopAppBar(
                title = {
                    Text(
                        title,
                        Modifier.testTag("file-picker-title"),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    IconButton(
                        onClick = showCreate,
                        enabled = state.canAct,
                        modifier = Modifier.testTag("file-picker-create"),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_create_folder_outline),
                            stringResource(R.string.create_folder),
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .testTag("file-picker-breadcrumbs"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { navigate(root) },
                    enabled = state.canAct,
                    modifier = Modifier.testTag("file-picker-root"),
                ) {
                    Text("root")
                }
                state.crumbs.forEach { crumb ->
                    PickerIcon(remember { FilePickerIcon.getArrow() }, Modifier.size(16.dp))
                    TextButton(
                        onClick = { navigate(crumb.path) },
                        enabled = state.canAct,
                        modifier = Modifier.testTag("file-picker-crumb-${crumb.path}"),
                    ) {
                        Text(crumb.name)
                    }
                }
            }
            if (state.loading || state.busy || state.result != null)
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("file-picker-working"))
            if (!state.creating)
                errorText?.let {
                    Text(
                        it,
                        Modifier.padding(16.dp).testTag("file-picker-error"),
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (!state.loading)
                        TextButton(
                            onClick = retry,
                            modifier = Modifier.testTag("file-picker-retry"),
                        ) {
                            Text(stringResource(R.string.retry))
                        }
                }
            if (state.loaded)
                key(state.directory) {
                    val initial = remember(state.directory) { position(state.directory) }
                    val list = rememberLazyListState(initial.first, initial.second)
                    val save by rememberUpdatedState(scrolled)
                    LaunchedEffect(list, state.directory, state.canAct) {
                        if (state.canAct)
                            snapshotFlow {
                                list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
                            }
                                .distinctUntilChanged()
                                .collect { (index, offset) -> save(state.directory, index, offset) }
                    }
                    LazyColumn(Modifier.weight(1f).testTag("file-picker-list"), state = list) {
                        state.parent?.let { path ->
                            item(key = "parent") {
                                PickerRow(
                                    path,
                                    "..",
                                    remember { FilePickerIcon.getUpDir() },
                                    state.canAct,
                                    false,
                                ) {
                                    click(path)
                                }
                                HorizontalDivider()
                            }
                        }
                        items(state.rows, key = { it.path }) { row ->
                            PickerRow(
                                row.path,
                                row.name,
                                remember(row.directory) {
                                    if (row.directory) FilePickerIcon.getFolder()
                                    else FilePickerIcon.getFile()
                                },
                                state.canAct && row.enabled,
                                state.selected == row.path,
                            ) {
                                click(row.path)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            else Spacer(Modifier.weight(1f))
            Button(
                onClick = confirm,
                enabled = state.canAct,
                modifier = Modifier.fillMaxWidth().padding(4.dp).testTag("file-picker-confirm"),
            ) {
                Text(stringResource(R.string.ok))
            }
        }
    }
    if (state.creating) {
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        var value by remember {
            mutableStateOf(
                TextFieldValue(state.folderName, TextRange(state.folderStart, state.folderEnd))
            )
        }
        LaunchedEffect(state.folderName, state.folderStart, state.folderEnd) {
            if (
                value.text != state.folderName ||
                    value.selection != TextRange(state.folderStart, state.folderEnd)
            )
                value =
                    value.copy(
                        text = state.folderName,
                        selection = TextRange(state.folderStart, state.folderEnd),
                        composition =
                            if (value.text == state.folderName) value.composition else null,
                    )
        }
        AlertDialog(
            onDismissRequest = cancelCreate,
            title = { Text(stringResource(R.string.create_folder)) },
            text = {
                // Request within the dialog composition, after its focus owner exists.
                LaunchedEffect(Unit) {
                    focus.requestFocus()
                    keyboard?.show()
                }
                Column {
                    OutlinedTextField(
                        value,
                        {
                            value = it
                            folderName(it.text, it.selection.start, it.selection.end)
                        },
                        singleLine = true,
                        enabled = !state.busy,
                        label = { Text(stringResource(R.string.file_picker_folder_name)) },
                        modifier =
                            Modifier.fillMaxWidth()
                                .focusRequester(focus)
                                .testTag("file-picker-folder-name"),
                    )
                    errorText?.let {
                        Text(
                            it,
                            Modifier.testTag("file-picker-create-error"),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = create,
                    enabled = state.canAct,
                    modifier = Modifier.testTag("file-picker-create-confirm"),
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = cancelCreate,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("file-picker-create-cancel"),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun PickerRow(
    path: String,
    name: String,
    bytes: ByteArray,
    enabled: Boolean,
    selected: Boolean,
    click: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .clickable(enabled = enabled, onClick = click)
            .semantics { this.selected = selected }
            .testTag("file-picker-row-$path")
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PickerIcon(bytes, Modifier.size(24.dp))
        Text(
            name,
            Modifier.padding(start = 8.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f),
        )
    }
}

@Composable
private fun PickerIcon(bytes: ByteArray, modifier: Modifier) {
    val bitmap =
        remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap() }
    Image(remember(bitmap) { BitmapPainter(bitmap) }, null, modifier)
}

@Composable
private fun pickerIssueText(issue: LocalFilePickerIssue) =
    stringResource(
        when (issue) {
            LocalFilePickerIssue.FileRequired -> R.string.file_picker_file_required
            LocalFilePickerIssue.FolderNameRequired -> R.string.file_picker_folder_required
            LocalFilePickerIssue.DirectoryMissing -> R.string.file_picker_directory_missing
            LocalFilePickerIssue.DirectoryUnreadable -> R.string.file_picker_directory_unreadable
            LocalFilePickerIssue.OutsideRoot -> R.string.file_picker_outside_root
            LocalFilePickerIssue.InvalidFolderName -> R.string.file_picker_invalid_name
            LocalFilePickerIssue.CreateFailed -> R.string.file_picker_create_failed
            LocalFilePickerIssue.SelectionInvalid -> R.string.file_picker_selection_invalid
        }
    )
