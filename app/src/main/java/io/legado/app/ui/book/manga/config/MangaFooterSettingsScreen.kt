package io.legado.app.ui.book.manga.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.preferences.MangaFooterDraft

@Composable
fun MangaFooterSettingsScreen(
    state: MangaFooterDraft,
    onHiddenChange: (MangaFooterField, Boolean) -> Unit,
    onFooterHiddenChange: (Boolean) -> Unit,
    onOrientationChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                stringResource(R.string.manga_footer_config),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Section(R.string.manga_header_chapter)
            HideOption(
                MangaFooterField.ChapterLabel,
                R.string.manga_check_chapter_label,
                state.hideChapterLabel,
                onHiddenChange,
            )
            HideOption(
                MangaFooterField.Chapter,
                R.string.manga_check_chapter,
                state.hideChapter,
                onHiddenChange,
            )
            HideOption(
                MangaFooterField.ChapterName,
                R.string.manga_check_chapter_name,
                state.hideChapterName,
                onHiddenChange,
            )
            Section(R.string.manga_header_page)
            HideOption(
                MangaFooterField.PageLabel,
                R.string.manga_check_page_label,
                state.hidePageNumberLabel,
                onHiddenChange,
            )
            HideOption(
                MangaFooterField.Page,
                R.string.manga_check_page_number,
                state.hidePageNumber,
                onHiddenChange,
            )
            Section(R.string.manga_header_progress)
            HideOption(
                MangaFooterField.ProgressLabel,
                R.string.manga_check_progress_label,
                state.hideProgressRatioLabel,
                onHiddenChange,
            )
            HideOption(
                MangaFooterField.Progress,
                R.string.manga_check_progress,
                state.hideProgressRatio,
                onHiddenChange,
            )
            Section(R.string.manga_header_footer)
            Column(Modifier.selectableGroup()) {
                Choice("manga-footer-show", R.string.show, !state.hideFooter) {
                    onFooterHiddenChange(false)
                }
                Choice("manga-footer-hide", R.string.hide, state.hideFooter) {
                    onFooterHiddenChange(true)
                }
            }
            Column(Modifier.selectableGroup()) {
                Choice(
                    "manga-footer-left",
                    R.string.manga_radio_left,
                    state.footerOrientation != 1,
                ) {
                    onOrientationChange(0)
                }
                Choice(
                    "manga-footer-center",
                    R.string.manga_radio_center,
                    state.footerOrientation == 1,
                ) {
                    onOrientationChange(1)
                }
            }
        }
    }
}

@Composable
private fun Section(label: Int) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun HideOption(
    field: MangaFooterField,
    label: Int,
    checked: Boolean,
    onChange: (MangaFooterField, Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("manga-footer-${field.name}")
            .toggleable(checked, role = Role.Checkbox) { onChange(field, it) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null)
        Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Choice(tag: String, label: Int, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(tag)
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
    }
}
