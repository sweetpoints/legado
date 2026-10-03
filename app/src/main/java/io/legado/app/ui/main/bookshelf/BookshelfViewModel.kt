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
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class BookshelfTransferOperation(val id: String, val label: String)

class BookshelfViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
    resumePendingFileImport: Boolean = true,
) : BaseViewModel(application) {
    private val repository = BookshelfTransferRepository(application.applicationContext)
    val transfer = BookshelfTransferSession(savedStateHandle)
    private var addBookJob: Job? = null
    private var exportJob: Job? = null
    private var fileImportJob: Job? = null
    private val mutableOperations = MutableStateFlow<List<BookshelfTransferOperation>>(emptyList())
    internal val operations = mutableOperations.asStateFlow()

    init {
        if (resumePendingFileImport) retryPendingFileImport()
    }

    internal fun retryPendingFileImport() {
        if (fileImportJob?.isActive == true) return
        transfer.pendingFileImport.value?.let(::runFileImport)
    }

    private fun beginOperation(label: String): String =
        UUID.randomUUID().toString().also { id ->
            mutableOperations.value =
                mutableOperations.value + BookshelfTransferOperation(id, label)
        }

    private fun finishOperation(id: String) {
        mutableOperations.value = mutableOperations.value.filterNot { it.id == id }
    }

    fun importBookshelfFile(uri: String, groupId: Long) {
        if (fileImportJob?.isActive == true) return
        runFileImport(transfer.fileReady(uri, groupId))
    }

    private fun runFileImport(request: BookshelfFileImport) {
        if (fileImportJob?.isActive == true) return
        val operationId = beginOperation("正在导入书单")
        fileImportJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.importFile(request.uri, request.groupId) }
                context.toastOnUi(R.string.success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.put("导入书单失败\n${error.localizedMessage}", error, true)
            } finally {
                transfer.fileFinished(request.id)
                finishOperation(operationId)
            }
        }
    }

    fun addBookByUrl(bookUrls: String, groupId: Long) {
        val token = transfer.beginAdd()
        val operationId = beginOperation("正在添加书籍")
        addBookJob?.cancel()
        addBookJob = viewModelScope.launch {
            try {
                val count =
                    withContext(Dispatchers.IO) {
                        repository.addUrls(bookUrls, groupId) { transfer.progress(token, it) }
                    }
                if (count > 0) context.toastOnUi(R.string.success) else context.toastOnUi("添加网址失败")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.put("添加网址出错\n${error.localizedMessage}", error, true)
            } finally {
                transfer.finish(token)
                finishOperation(operationId)
            }
        }
    }

    fun cancelAdd() {
        transfer.cancelAdd()
        addBookJob?.cancel()
    }

    fun exportBookshelf(books: List<Book>?) {
        exportJob?.cancel()
        val operationId = beginOperation("正在准备导出")
        val snapshot = books?.map { it.copy() }
        exportJob = viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { repository.exportBooks(snapshot) }
                transfer.exportReady(file.absolutePath)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                context.toastOnUi("导出书籍出错\n${error.localizedMessage}")
            } finally {
                finishOperation(operationId)
            }
        }
    }

    fun importBookshelf(text: String, groupId: Long) {
        val operationId = beginOperation("正在导入书单")
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.importBooks(text, groupId) }
                context.toastOnUi(R.string.success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.put("导入书单失败\n${error.localizedMessage}", error, true)
            } finally {
                finishOperation(operationId)
            }
        }
    }
}

/** Retained entry point for callers that import a bookshelf directly. */
internal suspend fun importBookshelfJson(json: String, groupId: Long) =
    io.legado.app.data.repository.importBookshelfJson(json, groupId)
