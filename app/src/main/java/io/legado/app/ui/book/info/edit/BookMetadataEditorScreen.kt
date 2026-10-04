package io.legado.app.ui.book.info.edit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.*
import io.legado.app.ui.components.LegadoTopAppBar
import io.legado.app.ui.components.cover.ComposeCover

class BookMetadataEditorActions(
    val back: () -> Unit = {},
    val save: () -> Unit = {},
    val text: (BookMetadataField, String, Int, Int) -> Unit = { _, _, _, _ -> },
    val type: (Int) -> Unit = {},
    val refreshCover: () -> Unit = {},
    val navigate: (BookMetadataAction) -> Unit = {},
    val retry: () -> Unit = {},
    val reload: () -> Unit = {},
)

@Composable
fun BookMetadataEditorScreen(
    state: BookMetadataEditorState,
    actions: BookMetadataEditorActions,
    modifier: Modifier = Modifier,
    cover: @Composable (CoverRequest, Modifier) -> Unit = { request, layout ->
        ComposeCover(request, layout)
    },
) {
    var types by rememberSaveable { mutableStateOf(false) }
    val input = state.draft?.input
    Surface(modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.imePadding(),
            topBar = {
                LegadoTopAppBar(
                    stringResource(R.string.book_info_edit),
                    actions.back,
                    actions = {
                        IconButton(
                            actions.save,
                            enabled =
                                state.canEdit &&
                                    state.draft?.pickerOwner == null &&
                                    state.draft?.navigation == null,
                            modifier = Modifier.testTag("book-metadata-save"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_save),
                                stringResource(R.string.action_save),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).navigationBarsPadding()) {
                if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { error ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            error,
                            Modifier.weight(1f).padding(12.dp),
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Column {
                            TextButton(
                                actions.retry,
                                enabled = !state.busy,
                                modifier = Modifier.testTag("book-metadata-retry"),
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                            if (state.loaded)
                                TextButton(
                                    actions.reload,
                                    enabled = !state.busy,
                                    modifier = Modifier.testTag("book-metadata-reload"),
                                ) {
                                    Text(stringResource(R.string.refresh))
                                }
                        }
                    }
                }
                if (state.loaded && input != null)
                    Column(
                        Modifier.fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                            .testTag("book-metadata-form"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            state.draft.preview?.let {
                                cover(
                                    it,
                                    Modifier.size(90.dp, 130.dp).testTag("book-metadata-preview"),
                                )
                            }
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                MetadataTextField(
                                    BookMetadataField.Name,
                                    input.name,
                                    R.string.book_name,
                                    state,
                                    actions,
                                    singleLine = true,
                                )
                                MetadataTextField(
                                    BookMetadataField.Author,
                                    input.author,
                                    R.string.author,
                                    state,
                                    actions,
                                    singleLine = true,
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.book_type), Modifier.padding(end = 12.dp))
                            val entries = stringArrayResource(R.array.book_type)
                            Box {
                                OutlinedButton(
                                    { types = true },
                                    enabled = state.canEdit,
                                    modifier = Modifier.testTag("book-metadata-type"),
                                ) {
                                    Text(entries[input.typeIndex.coerceIn(entries.indices)] + " ▾")
                                }
                                DropdownMenu(types, { types = false }) {
                                    entries.forEachIndexed { index, label ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                types = false
                                                actions.type(index)
                                            },
                                            modifier =
                                                Modifier.testTag("book-metadata-type-$index"),
                                        )
                                    }
                                }
                            }
                        }
                        MetadataTextField(
                            BookMetadataField.Cover,
                            input.cover,
                            R.string.cover_path,
                            state,
                            actions,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MetadataCoverButton(
                                R.string.select_local_image,
                                "book-metadata-pick-cover",
                                state.canEdit && state.draft.pickerOwner == null,
                                Modifier.weight(1f),
                            ) {
                                actions.navigate(BookMetadataAction.PickCover)
                            }
                            MetadataCoverButton(
                                R.string.change_cover_source,
                                "book-metadata-change-cover",
                                state.canEdit,
                                Modifier.weight(1f),
                            ) {
                                actions.navigate(BookMetadataAction.ChangeCover)
                            }
                            MetadataCoverButton(
                                R.string.refresh_cover,
                                "book-metadata-refresh-cover",
                                state.canEdit,
                                Modifier.weight(1f),
                                actions.refreshCover,
                            )
                        }
                        MetadataTextField(
                            BookMetadataField.Intro,
                            input.intro,
                            R.string.book_intro,
                            state,
                            actions,
                            minLines = 4,
                        )
                    }
            }
        }
    }
}

@Composable
private fun MetadataTextField(
    field: BookMetadataField,
    text: String,
    label: Int,
    state: BookMetadataEditorState,
    actions: BookMetadataEditorActions,
    singleLine: Boolean = false,
    minLines: Int = 1,
) {
    val cursor = state.cursors[field] ?: BookMetadataCursor()
    val value =
        TextFieldValue(
            text,
            TextRange(cursor.start.coerceIn(0, text.length), cursor.end.coerceIn(0, text.length)),
        )
    // Text stays in the repository/VM. Composition and cursor are the only editor-local state.
    var composition by remember(field) { mutableStateOf<TextRange?>(null) }
    OutlinedTextField(
        value.copy(composition = composition),
        { next ->
            composition = next.composition
            actions.text(field, next.text, next.selection.start, next.selection.end)
        },
        enabled = state.canEdit,
        label = { Text(stringResource(label)) },
        singleLine = singleLine,
        minLines = minLines,
        modifier = Modifier.fillMaxWidth().testTag("book-metadata-${field.name.lowercase()}"),
    )
}

@Composable
private fun MetadataCoverButton(
    label: Int,
    tag: String,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).testTag(tag),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Text(stringResource(label), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
