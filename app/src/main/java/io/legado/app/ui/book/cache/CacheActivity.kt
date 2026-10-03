package io.legado.app.ui.book.cache

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.repository.RoomBookCacheRepository
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.observeEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

class CacheActivity : BaseComposeActivity() {
    val viewModel by viewModels<BookCacheViewModel> {
        viewModelFactory { initializer { BookCacheViewModel(RoomBookCacheRepository(applicationContext),
            createSavedStateHandle(), intent.getLongExtra("groupId", -1)) } }
    }
    private val exportDir = registerForActivityResult(HandleFileContract()) { result ->
        val uri = result.uri
        val ticket = result.value ?: viewModel.pendingFolderTicket()
        if (ticket != null) viewModel.folderResult(ticket, if (uri == null) null else if (uri.isContentScheme()) uri.toString() else uri.path)
    }
    override fun onComposeCreated(savedInstanceState: Bundle?) {
        observeEvent<String>(EventBus.EXPORT_BOOK, EventBus.UP_DOWNLOAD, EventBus.UP_DOWNLOAD_STATE) { viewModel.refresh() }
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) -> viewModel.chapterSaved(book.bookUrl, chapter.url) }
    }
    @Composable override fun Content(savedInstanceState: Bundle?) {
        BookCacheRoute(viewModel, { super.finish() }, { request ->
            exportDir.launch {
                value = request.ticket
                otherActions = request.path?.takeIf { it.isNotEmpty() }?.let { arrayListOf(SelectItem(it, -1)) }
            }
        }, { showDialogFragment<AppLogDialog>() }, { toastOnUi(it) }, { !supportFragmentManager.isStateSaved })
    }
    override fun finish() { viewModel.close(); super.finish() }
}
