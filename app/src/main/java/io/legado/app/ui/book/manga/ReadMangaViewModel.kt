package io.legado.app.ui.book.manga

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.repository.DefaultMangaReaderOperationsRepository
import io.legado.app.data.repository.MangaChapterRefreshRequest
import io.legado.app.data.repository.MangaImageSaveRequest
import io.legado.app.data.repository.MangaReaderEngineRepository
import io.legado.app.data.repository.MangaReaderLaunch
import io.legado.app.model.ReadManga
import io.legado.app.utils.GSON
import io.legado.app.utils.toastOnUi

class ReadMangaViewModel(application: Application) : BaseViewModel(application) {

    private val operations = DefaultMangaReaderOperationsRepository()
    private var initializationGeneration = 0L
    private val engine =
        MangaReaderEngineRepository(
            scope = viewModelScope,
            context = context,
            notifyUser = { context.toastOnUi(it) },
        )

    fun initData(intent: Intent, success: (() -> Unit)? = null) {
        initializationGeneration++
        val launch =
            MangaReaderLaunch(
                bookUrl = intent.getStringExtra("bookUrl"),
                inBookshelf = intent.getBooleanExtra("inBookshelf", true),
                chapterChanged = intent.getBooleanExtra("chapterChanged", false),
            )
        execute {
            engine.initialize(launch)
        }
            .onSuccess {
                success?.invoke()
            }
            .onError {
                AppLog.put("初始化数据失败\n${it.localizedMessage}", it)
            }
            .onFinally {
                ReadManga.saveRead()
            }
    }

    fun syncBookProgress(book: Book, alertSync: ((BookProgress) -> Unit)? = null) {
        engine.syncBookProgress(book, alertSync)
    }

    fun changeTo(book: Book, toc: List<BookChapter>, onSuccess: () -> Unit = {}) {
        engine.changeTo(book, toc, onSuccess)
    }

    fun openChapter(index: Int, durChapterPos: Int = 0) {
        engine.openChapter(index, durChapterPos)
    }

    fun removeFromBookshelf(success: (() -> Unit)?) {
        val bookUrl = ReadManga.book?.bookUrl
        execute {
            if (bookUrl != null) operations.removeFromBookshelf(bookUrl)
        }
            .onSuccess {
                success?.invoke()
            }
    }

    override fun onCleared() {
        super.onCleared()
        engine.cancelSourceChange()
    }

    fun refreshContentDur(book: Book) {
        val generation = initializationGeneration
        val request =
            MangaChapterRefreshRequest(
                bookUrl = book.bookUrl,
                chapterIndex = ReadManga.durChapterIndex,
                pageIndex = ReadManga.durChapterPos,
            )
        execute {
            operations.refreshChapter(request)
        }
            .onSuccess { refreshed ->
                if (
                    refreshed &&
                        generation == initializationGeneration &&
                        ReadManga.book === book &&
                        ReadManga.durChapterIndex == request.chapterIndex &&
                        ReadManga.durChapterPos == request.pageIndex
                ) {
                    openChapter(request.chapterIndex, request.pageIndex)
                }
            }
    }

    fun saveImage(src: String?, uri: Uri) {
        src ?: return
        val book = ReadManga.book ?: return
        val request =
            MangaImageSaveRequest(
                bookUrl = book.bookUrl,
                bookSnapshot = GSON.toJson(book),
                sourceSnapshot = ReadManga.bookSource?.let { GSON.toJson(it) },
                imageUrl = src,
                directoryUri = uri.toString(),
            )
        execute {
            operations.saveImage(request)
        }
            .onError {
                AppLog.put("保存图片出错\n${it.localizedMessage}", it)
                context.toastOnUi("保存图片出错\n${it.localizedMessage}")
            }
    }
}
