package io.legado.app.ui.book.manga.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.legado.app.data.preferences.MangaFooterDraft
import io.legado.app.data.preferences.MangaFooterJson
import io.legado.app.data.preferences.MangaFooterSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MangaFooterField {
    ChapterLabel,
    Chapter,
    ChapterName,
    PageLabel,
    Page,
    ProgressLabel,
    Progress,
}

class MangaFooterSettingsViewModel(
    private val repository: MangaFooterSettingsRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val restored = savedState.get<String>(DRAFT_KEY)
    private val mutableState =
        MutableStateFlow(restored?.let(MangaFooterJson::decode) ?: repository.load())
    val state = mutableState.asStateFlow()
    private var finished = false

    init {
        savedState[DRAFT_KEY] = MangaFooterJson.encode(state.value)
    }

    fun setHidden(field: MangaFooterField, hidden: Boolean) = change {
        when (field) {
            MangaFooterField.ChapterLabel -> it.copy(hideChapterLabel = hidden)
            MangaFooterField.Chapter -> it.copy(hideChapter = hidden)
            MangaFooterField.ChapterName -> it.copy(hideChapterName = hidden)
            MangaFooterField.PageLabel -> it.copy(hidePageNumberLabel = hidden)
            MangaFooterField.Page -> it.copy(hidePageNumber = hidden)
            MangaFooterField.ProgressLabel -> it.copy(hideProgressRatioLabel = hidden)
            MangaFooterField.Progress -> it.copy(hideProgressRatio = hidden)
        }
    }

    fun setFooterHidden(hidden: Boolean) = change { it.copy(hideFooter = hidden) }

    fun setOrientation(orientation: Int) {
        if (orientation == 0 || orientation == 1)
            change { it.copy(footerOrientation = orientation) }
    }

    private fun change(update: (MangaFooterDraft) -> MangaFooterDraft) {
        if (finished) return
        val draft = update(state.value)
        if (draft == state.value) return
        savedState[DRAFT_KEY] = MangaFooterJson.encode(draft)
        mutableState.value = draft
        repository.preview(draft)
    }

    fun reapplyPreview() {
        if (!finished) repository.preview(state.value)
    }

    /** Called for actual dismissal only; view destruction and rotation keep the draft unsaved. */
    fun saveOnDismiss(isChangingConfigurations: Boolean = false) {
        if (isChangingConfigurations || finished) return
        repository.save(state.value)
        finished = true
    }

    companion object {
        private const val DRAFT_KEY = "manga.footer.draft"
    }
}
