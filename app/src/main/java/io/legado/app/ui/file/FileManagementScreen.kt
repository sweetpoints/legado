package io.legado.app.ui.file

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.ManagedFileKind
import io.legado.app.ui.file.utils.FilePickerIcon

class FileManagementActions(
    val query: (String, Int, Int) -> Unit = { _, _, _ -> },
    val navigate: (String?) -> Unit = {},
    val click: (String) -> Unit = {},
    val delete: (String) -> Unit = {},
    val retry: () -> Unit = {},
    val back: () -> Unit = {},
    val close: () -> Unit = {},
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileManagementScreen(
    state: FileManagementState,
    actions: FileManagementActions,
    modifier: Modifier = Modifier,
) {
    val enabled = state.canAct && !state.loading
    var menu by remember { mutableStateOf<String?>(null) }
    val deleteLabel = stringResource(R.string.delete)
    LaunchedEffect(state.directory) { menu = null }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            if (state.canAct) actions.back() else if (!state.busy) actions.close()
                        },
                        enabled = !state.busy,
                        modifier = Modifier.testTag("file-management-back"),
                    ) {
                        Text(
                            stringResource(R.string.back),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Text(
                        stringResource(R.string.file_manage),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.width(16.dp))
                }
            }
            OutlinedTextField(
                TextFieldValue(state.query, TextRange(state.queryStart, state.queryEnd)),
                { actions.query(it.text, it.selection.start, it.selection.end) },
                enabled = enabled,
                label = {
                    Text(
                        stringResource(R.string.screen) +
                            " • " +
                            stringResource(R.string.file_manage)
                    )
                },
                singleLine = true,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .testTag("file-management-query"),
            )
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .testTag("file-management-path"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.crumbs.forEach { crumb ->
                    TextButton(
                        onClick = { actions.navigate(crumb.path) },
                        enabled = state.canAct,
                        modifier = Modifier.testTag("file-management-crumb-${crumb.path}"),
                    ) {
                        Text(crumb.name)
                    }
                    FileManagementIcon(remember { FilePickerIcon.getArrow() }, Modifier.size(16.dp))
                }
            }
            if (state.loading || state.busy)
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("file-management-loading"))
            state.error?.let {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        it,
                        Modifier.weight(1f).testTag("file-management-error"),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        onClick = actions.retry,
                        enabled = !state.busy && !state.loading,
                        modifier = Modifier.testTag("file-management-retry"),
                    ) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.rows.isEmpty() && !state.loading)
                    Text(
                        stringResource(R.string.empty),
                        Modifier.align(Alignment.Center)
                            .padding(16.dp)
                            .testTag("file-management-empty"),
                    )
                if (state.loaded && !state.loading)
                    key(state.directory) {
                        val list = rememberLazyListState()
                        LazyColumn(
                            Modifier.fillMaxSize().testTag("file-management-list"),
                            state = list,
                            contentPadding = PaddingValues(bottom = 16.dp),
                        ) {
                            items(state.rows, key = { it.kind.name + ":" + it.path }) { item ->
                                Box {
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .heightIn(min = 56.dp)
                                            .combinedClickable(
                                                enabled = enabled,
                                                onClick = {
                                                    menu = null
                                                    actions.click(item.path)
                                                },
                                                onLongClick = {
                                                    if (item.kind != ManagedFileKind.Parent)
                                                        menu = item.path
                                                },
                                            )
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                            .testTag("file-management-row-${item.path}")
                                            .semantics {
                                                if (item.kind != ManagedFileKind.Parent)
                                                    customActions =
                                                        listOf(
                                                            CustomAccessibilityAction(
                                                                "${item.name}: $deleteLabel"
                                                            ) {
                                                                if (enabled) {
                                                                    menu = item.path
                                                                    true
                                                                } else false
                                                            }
                                                        )
                                            },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        val bytes =
                                            remember(item.kind) {
                                                when (item.kind) {
                                                    ManagedFileKind.Parent ->
                                                        FilePickerIcon.getUpDir()
                                                    ManagedFileKind.Directory ->
                                                        FilePickerIcon.getFolder()
                                                    ManagedFileKind.File -> FilePickerIcon.getFile()
                                                }
                                            }
                                        FileManagementIcon(bytes, Modifier.size(28.dp))
                                        Text(
                                            item.name,
                                            Modifier.weight(1f).padding(start = 12.dp),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    DropdownMenu(
                                        menu == item.path && item.kind != ManagedFileKind.Parent,
                                        { menu = null },
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(R.string.delete),
                                                    color = MaterialTheme.colorScheme.error,
                                                )
                                            },
                                            onClick = {
                                                menu = null
                                                actions.delete(item.path)
                                            },
                                            enabled = enabled,
                                            modifier =
                                                Modifier.testTag(
                                                    "file-management-delete-${item.path}"
                                                ),
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                        FileManagementFastScroll(list, Modifier.align(Alignment.CenterEnd))
                    }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun FileManagementIcon(bytes: ByteArray, modifier: Modifier) {
    val image =
        remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    image?.let { Image(it, contentDescription = null, modifier = modifier) }
}
