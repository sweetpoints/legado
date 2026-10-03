package io.legado.app.ui.book.changesource

import android.content.DialogInterface
import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.preferences.AppChapterSourceSettingsRepository
import io.legado.app.data.repository.*
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi

class ChangeChapterSourceDialog() : BaseComposeDialogFragment(), ChangeSourceWordCountFilterCallback {
    constructor(name: String, author: String, chapterIndex: Int, chapterTitle: String, batchMode: Boolean = false) : this() {
        arguments = Bundle().apply { putString("name", name); putString("author", author); putInt("chapterIndex", chapterIndex)
            putString("chapterTitle", chapterTitle); putBoolean("batchMode", batchMode) }
    }
    private val groupsRepository = RoomChapterSourceGroupRepository()
    private val callBack: CallBack? get() = activity as? CallBack
    private val model by viewModels<ChapterSourceViewModel> {
        viewModelFactory { initializer {
            val context = requireContext().applicationContext
            val args = arguments ?: Bundle(); val name = args.getString("name").orEmpty(); val author = args.getString("author").orEmpty()
            val index = args.getInt("chapterIndex"); val title = args.getString("chapterTitle").orEmpty(); val batch = args.getBoolean("batchMode")
            val book = callBack?.oldBook?.copy(); val fromReader = activity is ReadBookActivity
            val searchStore = AppChapterSourceSearchStore()
            ChapterSourceViewModel(DefaultChapterSourceSearchRepository(searchStore),
                DefaultChapterSourceContentRepository(AppChapterSourceContentStore(context, search = searchStore)),
                AppChapterSourceSettingsRepository(), createSavedStateHandle()) {
                chapterSourceSessionSeed(name, author, index, title, batch, book, fromReader)
            }
        } }
    }
    private val editSourceResult = registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) { model.startSearch() }
    override fun onStart() { super.onStart(); setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT) }
    override fun onCancel(dialog: DialogInterface) { model.stop(); super.onCancel(dialog) }
    override fun onWordCountFilterChanged(reloadMeasurements: Boolean) { model.optionsChanged(reloadMeasurements) }
    override fun observeLiveBus() { observeEvent<String>(EventBus.SOURCE_CHANGED) { model.refresh() } }
    @Composable override fun Content() {
        ChapterSourceRoute(model, { isAdded && !parentFragmentManager.isStateSaved && callBack != null }, ::deliver,
            { action -> when (action) { ChapterSourceMenu.Manage -> startActivity<BookSourceActivity>()
                ChapterSourceMenu.WordCountFilter -> showChangeSourceWordCountFilter(); else -> Unit } },
            { origin -> editSourceResult.launch { putExtra("sourceUrl", origin) } }, ::close, {
                if (model.state.value.batch) toastOnUi(R.string.chapter_source_finished)
                close()
            }, Modifier.fillMaxSize(), groupsRepository)
    }
    private fun close() { model.stop(); dismissAllowingStateLoss() }
    private fun deliver(value: ChapterSourceDelivery) {
        val callback = callBack ?: return
        when (value.receipt.kind) {
            ChapterSourceReceiptKind.Content -> callback.replaceContent(requireNotNull(value.receipt.body))
            ChapterSourceReceiptKind.Cache -> callback.contentCached(requireNotNull(value.receipt.chapterIndex))
            ChapterSourceReceiptKind.Change -> callback.changeTo(requireNotNull(value.source), requireNotNull(value.book), value.chapters) {
                model.completeSourceChange(value.receipt)
            }
        }
    }
    interface CallBack {
        val oldBook: Book?
        fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>, onSuccess: () -> Unit)
        fun replaceContent(content: String)
        fun contentCached(chapterIndex: Int)
    }
}
