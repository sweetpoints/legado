package io.legado.app.ui.book.toc

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.AppTocHostRepository
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.globalExecutor
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.runBlocking

class TocViewModel(application: Application) : BaseViewModel(application) {
    private val repository = AppTocHostRepository()
    var bookUrl: String = ""
    var bookData = MutableLiveData<Book>()
    var chapterListCallBack: ChapterListCallBack? = null
    var bookMarkCallBack: BookmarkCallBack? = null
    var highlightCallBack: HighlightCallBack? = null
    var searchKey: String? = null

    fun initBook(bookUrl: String) {
        this.bookUrl = bookUrl
        execute {
            repository.load(bookUrl)?.let {
                bookData.postValue(it)
            }
        }
    }

    fun upBookTocRule(book: Book, complete: (Throwable?) -> Unit) {
        execute {
            repository.rebuild(book).also { bookData.postValue(it) }
        }
            .onSuccess {
                complete.invoke(null)
            }
            .onError {
                complete.invoke(it)
            }
    }

    fun reverseToc(success: (book: Book) -> Unit) {
        execute {
            bookData.value?.let { current ->
                repository.reverse(current).also { result ->
                    current.readConfig = result.readConfig?.copy()
                    repository.synchronizeReverse(result)
                }
                current
            }
        }
            .onSuccess {
                it?.let(success)
            }
    }

    fun setTocExpanded(expanded: Boolean) {
        val book = bookData.value ?: return
        book.setTocExpanded(expanded)
        updateActiveReaderBooks(book.bookUrl, expanded)
        chapterListCallBack?.upChapterList(
            searchKey,
            resetCollapse = true,
            replaceAll = true,
        )
        globalExecutor.execute {
            runCatching {
                runBlocking { repository.expanded(book.bookUrl, expanded) }
            }
                .onFailure {
                    AppLog.put("保存目录展开设置失败\n${it.localizedMessage}", it)
                }
        }
    }

    private fun updateActiveReaderBooks(bookUrl: String, expanded: Boolean) {
        repository.synchronizeExpanded(bookUrl, expanded)
    }

    fun startChapterListSearch(newText: String?) {
        chapterListCallBack?.upChapterList(newText)
    }

    fun startBookmarkSearch(newText: String?) {
        bookMarkCallBack?.upBookmark(newText)
    }

    fun startHighlightSearch(newText: String?) {
        highlightCallBack?.upHighlight(newText)
    }

    fun upChapterListAdapter() {
        chapterListCallBack?.upAdapter()
    }

    fun saveBookmark(treeUri: Uri) {
        execute {
            val book =
                bookData.value ?: throw NoStackTraceException(context.getString(R.string.no_book))
            repository.export(book, treeUri.toString(), false)
        }
            .onError {
                AppLog.put("导出失败\n${it.localizedMessage}", it, true)
            }
            .onSuccess {
                context.toastOnUi("导出成功")
            }
    }

    fun saveBookmarkMd(treeUri: Uri) {
        execute {
            val book =
                bookData.value ?: throw NoStackTraceException(context.getString(R.string.no_book))
            repository.export(book, treeUri.toString(), true)
        }
            .onError {
                AppLog.put("导出失败\n${it.localizedMessage}", it, true)
            }
            .onSuccess {
                context.toastOnUi("导出成功")
            }
    }

    interface ChapterListCallBack {
        fun upChapterList(
            searchKey: String?,
            resetCollapse: Boolean = false,
            replaceAll: Boolean = false,
        )

        fun clearDisplayTitle()

        fun upAdapter()
    }

    interface BookmarkCallBack {
        fun upBookmark(searchKey: String?)
    }

    interface HighlightCallBack {
        fun upHighlight(searchKey: String?)
    }
}
