package io.legado.app.ui.main.bookshelf

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.BookshelfTransferRepository
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookshelfViewModel(application: Application, savedStateHandle: SavedStateHandle) : BaseViewModel(application) {
    private val repository = BookshelfTransferRepository(application.applicationContext)
    val transfer = BookshelfTransferSession(savedStateHandle)
    private var addBookJob: Job? = null
    private var exportJob: Job? = null
    init { transfer.pendingFileImport.value?.let(::runFileImport) }
    fun importBookshelfFile(uri: String, groupId: Long) { runFileImport(transfer.fileReady(uri, groupId)) }
    private fun runFileImport(request: BookshelfFileImport) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.importFile(request.uri, request.groupId) }
                context.toastOnUi(R.string.success)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { AppLog.put("导入书单失败\n${error.localizedMessage}", error, true) }
            finally { transfer.fileFinished(request.id) }
        }
    }
    fun addBookByUrl(bookUrls: String, groupId: Long) {
        val token = transfer.beginAdd()
        addBookJob?.cancel()
        addBookJob = viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { repository.addUrls(bookUrls, groupId) { transfer.progress(token, it) } }
                if (count > 0) context.toastOnUi(R.string.success) else context.toastOnUi("添加网址失败")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { AppLog.put("添加网址出错\n${error.localizedMessage}", error, true) }
            finally { transfer.finish(token) }
        }
    }
    fun cancelAdd() { transfer.cancelAdd(); addBookJob?.cancel() }
    fun exportBookshelf(books: List<Book>?) {
        exportJob?.cancel()
        val snapshot = books?.map { it.copy() }
        exportJob = viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { repository.exportBooks(snapshot) }
                transfer.exportReady(file.absolutePath)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { context.toastOnUi("导出书籍出错\n${error.localizedMessage}") }
        }
    }
    fun importBookshelf(text: String, groupId: Long) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.importBooks(text, groupId) }
                context.toastOnUi(R.string.success)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { AppLog.put("导入书单失败\n${error.localizedMessage}", error, true) }
        }
    }
}

/** Retained entry point for callers that import a bookshelf directly. */
internal suspend fun importBookshelfJson(json: String, groupId: Long) =
    io.legado.app.data.repository.importBookshelfJson(json, groupId)
