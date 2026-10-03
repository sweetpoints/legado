package io.legado.app.ui.book.toc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.repository.*
import io.legado.app.help.config.AppConfig
import io.legado.app.model.AudioCacheStateChanged
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.read.HighlightNoteDialog
import io.legado.app.ui.book.toc.rule.TxtTocRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.observeEvent
import io.legado.app.utils.showDialogFragment

/** Direct Compose pager host; standalone fragment entry points remain available to legacy callers. */
class TocActivity : BaseComposeActivity(), TxtTocRuleDialog.CallBack {
    internal val sessionModel by viewModels<TocHostSessionViewModel> {
        viewModelFactory { initializer { TocHostSessionViewModel(FileTocHostSessionRepository(), createSavedStateHandle().apply { remove<String>("bookUrl") }) } }
    }
    internal val hostModel by viewModels<TocHostViewModel> {
        viewModelFactory { initializer { TocHostViewModel(AppTocHostRepository(), createSavedStateHandle().apply { remove<String>("bookUrl") }) } }
    }
    internal val chapterModel by viewModels<TocChapterViewModel> {
        viewModelFactory { initializer { TocChapterViewModel(AppTocChapterRepository(), createSavedStateHandle().apply { remove<String>("bookUrl") }) } }
    }
    internal val bookmarkModel by viewModels<TocBookmarksViewModel> {
        viewModelFactory { initializer { TocBookmarksViewModel(RoomTocBookmarksRepository(), createSavedStateHandle().apply { remove<String>("bookUrl") }) } }
    }
    internal val highlightModel by viewModels<TocHighlightsViewModel> {
        viewModelFactory { initializer { TocHighlightsViewModel(RoomTocHighlightsRepository(), createSavedStateHandle().apply { remove<String>("bookUrl") }) } }
    }
    private var exportRequestCode = 0
    private val exportDir = registerForActivityResult(HandleFileContract()) {
        // The contract keeps its request code in memory, so restore the sole outstanding ticket after recreation.
        val code = it.requestCode.takeIf { value -> value != 0 } ?: exportRequestCode
        if (code == exportRequestCode) exportRequestCode = 0
        hostModel.picked(code, it.uri?.toString())
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tocHost.exportRequestCode", exportRequestCode)
        super.onSaveInstanceState(outState)
    }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        exportRequestCode = savedInstanceState?.getInt("tocHost.exportRequestCode") ?: 0
        // A restored pre-migration pager may contain page fragments. Preserve all independent dialogs.
        supportFragmentManager.fragments.filter { it is ChapterListFragment || it is BookmarkFragment || it is HighlightFragment }
            .takeIf { it.isNotEmpty() }?.let { old -> supportFragmentManager.beginTransaction().apply { old.forEach(::remove) }.commitNow() }
        val bookUrl = intent.getStringExtra("bookUrl").orEmpty()
        hostModel.load(bookUrl)
        sessionModel.bind(bookUrl)
        onBackPressedDispatcher.addCallback(this) { closeOrCollapseSearch() }
    }
    private fun closeOrCollapseSearch() {
        if (hostModel.state.value.busy) return
        if (sessionModel.state.value.searchOpen) { sessionModel.query(""); sessionModel.search(false) } else finish()
    }
    @Composable override fun Content(savedInstanceState: Bundle?) {
        TocHostRoute(sessionModel, hostModel, chapterModel, bookmarkModel, highlightModel,
            { !isFinishing && !supportFragmentManager.isStateSaved }, ::closeOrCollapseSearch, ::deliverEffect,
            { value ->
                value.title?.let { longToastOnUi(it.ifBlank { if (chapterModel.state.value.pdf) getString(R.string.pdf_outline_untitled) else "" }) }
                value.navigation?.let { setResult(Activity.RESULT_OK, chapterResultIntent(it)); finish() }
            }, { row, edit, position ->
                if (edit) BookmarkDialog(row, position).show(supportFragmentManager, "toc-bookmark-editor")
                else { setResult(Activity.RESULT_OK, Intent().putExtra("index", row.chapterIndex).putExtra("chapterPos", row.chapterPos)); finish() }
            }, { target, edit ->
                if (edit) HighlightNoteDialog(target.highlight).show(supportFragmentManager, "toc-highlight-editor")
                else highlightResultIntent(target)?.let { setResult(Activity.RESULT_OK, it); finish() }
            })
    }
    private fun deliverEffect(value: TocHostEffect) {
        when (value.kind) {
            TocHostEffectKind.Regex -> showDialogFragment(TxtTocRuleDialog(hostModel.snapshot()?.tocUrl))
            TocHostEffectKind.Log -> showDialogFragment<AppLogDialog>()
            TocHostEffectKind.PickJson, TocHostEffectKind.PickMarkdown -> { exportRequestCode = value.requestCode; exportDir.launch { requestCode = value.requestCode } }
            TocHostEffectKind.ExportSuccess -> longToastOnUi(getString(R.string.export_success))
            TocHostEffectKind.ExportFailure -> longToastOnUi(hostModel.state.value.error.orEmpty())
        }
    }
    override fun onTocRegexDialogResult(tocRegex: String) = hostModel.regex(tocRegex)
    override fun observeLiveBus() {
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) -> chapterModel.contentSaved(book.bookUrl, chapter) }
        observeEvent<AudioCacheStateChanged>(EventBus.AUDIO_CACHE_CHANGED) { chapterModel.audioChanged(it, AppConfig.audioCacheTreeUri) }
    }
}
