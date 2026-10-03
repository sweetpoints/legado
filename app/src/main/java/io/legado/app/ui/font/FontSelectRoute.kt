package io.legado.app.ui.font

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.repository.FontEntry
import io.legado.app.data.repository.FontSelectionRepository

@Composable
fun FontSelectRoute(
    viewModel: FontSelectViewModel,
    selectedPath: String,
    onDefault: () -> Unit,
    onFolder: () -> Boolean,
    onImport: () -> Unit,
    onSelected: (String) -> Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(state.selectedPath, state.openFolder) {
        val handledSelection =
            state.selectedPath?.let {
                if (onSelected(it)) {
                    viewModel.selectionHandled()
                    true
                } else false
            } ?: false
        if (!handledSelection && state.selectedPath == null && state.openFolder && onFolder())
            viewModel.folderOpened()
        onPauseOrDispose {}
    }
    FontSelectScreen(
        state,
        selectedPath,
        viewModel::select,
        onDefault,
        viewModel::requestFolder,
        onImport,
        { viewModel.load() },
        viewModel::selectSystemTypeface,
        viewModel::cancelSystemPicker,
        onClose,
        { entry, label, textModifier ->
            FontPreview(entry, label, viewModel.repository, textModifier)
        },
        modifier,
    )
}

@Composable
private fun FontPreview(
    entry: FontEntry,
    label: String,
    repository: FontSelectionRepository,
    modifier: Modifier,
) {
    val family by
        produceState<FontFamily>(FontFamily.Default, entry.path, repository) {
            value = repository.preview(entry)?.let(::FontFamily) ?: FontFamily.Default
        }
    Text(label, modifier, fontFamily = family)
}
