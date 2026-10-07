package io.legado.app.model

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.model.sourceEngine.DartSourceEngine
import io.legado.app.model.sourceEngine.SourceHostCallbacks
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.ui.widget.dialog.BottomWebViewDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.isTrue
import java.util.Collections
import kotlin.String
import kotlin.onFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

object SourceCallBack {
    private data class CustomButtonRequest(
        val activity: AppCompatActivity,
        val sourceKey: String,
        val bookUrl: String,
        val chapterIndex: Int?,
        val bookType: Int,
        val event: String,
    )

    private val pendingCustomButtons =
        Collections.synchronizedSet(mutableSetOf<CustomButtonRequest>())

    const val CLICK_AUTHOR = "clickAuthor"
    const val LONG_CLICK_AUTHOR = "longClickAuthor"
    const val CLICK_BOOK_NAME = "clickBookName"
    const val LONG_CLICK_BOOK_NAME = "longClickBookName"
    const val CLICK_CUSTOM_BUTTON = "clickCustomButton"
    const val LONG_CLICK_CUSTOM_BUTTON = "longClickCustomButton"
    const val CLICK_SHARE_BOOK = "clickShareBook"
    const val CLICK_CLEAR_CACHE = "clickClearCache"
    const val CLICK_COPY_BOOK_URL = "clickCopyBookUrl"
    const val CLICK_COPY_TOC_URL = "clickCopyTocUrl"
    const val CLICK_COPY_PLAY_URL = "clickCopyPlayUrl"
    const val CLICK_BOOK_LABEL = "clickBookLabel"
    const val LONG_CLICK_BOOK_LABEL = "longClickBookLabel"

    const val ADD_BOOK_SHELF = "addBookShelf"
    const val DEL_BOOK_SHELF = "delBookShelf"
    const val SAVE_READ = "saveRead"
    const val START_READ = "startRead"
    const val END_READ = "endRead"
    const val START_SHELF_REFRESH = "startShelfRefresh"
    const val END_SHELF_REFRESH = "endShelfRefresh"

    fun callBackBtn(
        activity: AppCompatActivity,
        event: String,
        source: BookSource?,
        book: Book,
        chapter: BookChapter?,
        bookType: Int = 0,
        result: String? = null,
        noCall: (() -> Unit)? = null,
    ): Job? {
        if (source == null || !source.eventListener) {
            noCall?.invoke()
            return null
        }
        val jsStr = source.getContentRule().callBackJs
        if (jsStr.isNullOrEmpty()) {
            noCall?.invoke()
            return null
        }
        val request =
            if (event == CLICK_CUSTOM_BUTTON || event == LONG_CLICK_CUSTOM_BUTTON) {
                CustomButtonRequest(
                    activity,
                    source.getKey(),
                    book.bookUrl,
                    chapter?.index,
                    bookType,
                    event,
                )
            } else null
        if (request != null && !pendingCustomButtons.add(request)) return null
        val browserKey = request?.let {
            GSON.toJson(listOf(it.sourceKey, it.bookUrl, it.chapterIndex, it.bookType, it.event))
        }
        // Finish on Main after any showBrowser work posted by the script.
        return activity.lifecycleScope
            .launch(start = CoroutineStart.LAZY) {
                if (
                    browserKey != null &&
                        activity.supportFragmentManager.fragments.any {
                            it is BottomWebViewDialog && it.handlesCustomButton(browserKey)
                        }
                )
                    return@launch
                withContext(IO) {
                    val java =
                        SourceLoginJsExtensions(activity, source, bookType).apply {
                            customButtonKey = browserKey
                        }
                    kotlin
                        .runCatching {
                            val result =
                                withContext(
                                    SourceHostCallbacks { method, arguments ->
                                        withContext(Dispatchers.Main.immediate) {
                                            when (method) {
                                                "browser.show" ->
                                                    java.showBrowser(
                                                        arguments[0] as String,
                                                        arguments.getOrNull(1) as? String,
                                                        arguments.getOrNull(2) as? String,
                                                        arguments.getOrNull(3) as? String,
                                                    )
                                                "browser.start" ->
                                                    SourceVerificationHelp.startBrowser(
                                                        source,
                                                        arguments[0] as String,
                                                        arguments[1] as String,
                                                        html = arguments.getOrNull(2) as? String,
                                                    )
                                                "browser.openUrl" ->
                                                    java.openUrl(
                                                        arguments[0] as String,
                                                        arguments.getOrNull(1) as? String,
                                                    )
                                                else ->
                                                    error(
                                                        "Unsupported source callback host API $method"
                                                    )
                                            }
                                        }
                                        null
                                    }
                                ) {
                                    DartSourceEngine.evaluate(
                                            source,
                                            jsStr,
                                            bindings(event, book, chapter, result),
                                        )
                                        .toString()
                                }
                            if (!result.isTrue()) {
                                withContext(Dispatchers.Main) {
                                    noCall?.invoke()
                                }
                            }
                        }
                        .onFailure {
                            currentCoroutineContext().ensureActive()
                            AppLog.put(
                                "${source.bookSourceName}\n书源执行回调事件${event}出错\n${it.localizedMessage}",
                                it,
                                true,
                            )
                        }
                }
            }
            .apply {
                // Also releases the claim when a destroyed host cancels before the lazy block
                // starts.
                invokeOnCompletion { if (request != null) pendingCustomButtons.remove(request) }
                start()
            }
    }

    fun callBackBook(
        event: String,
        source: BookSource?,
        book: Book?,
        chapter: BookChapter? = null,
        result: String? = null,
    ) {
        Coroutine.async {
            callBackBookInternal(event, source, book, chapter, result)
        }
    }

    fun callBackBooks(
        event: String,
        books: List<Pair<BookSource?, Book>>,
    ) {
        if (books.isEmpty()) return
        Coroutine.async {
            books.forEach { (source, book) ->
                callBackBookInternal(event, source, book)
            }
        }
    }

    private suspend fun callBackBookInternal(
        event: String,
        source: BookSource?,
        book: Book?,
        chapter: BookChapter? = null,
        result: String? = null,
    ) {
        if (source == null || book == null || !source.eventListener) return
        val jsStr = source.getContentRule().callBackJs
        if (jsStr.isNullOrEmpty()) return
        kotlin
            .runCatching {
                withTimeout(60000L) {
                    DartSourceEngine.evaluate(source, jsStr, bindings(event, book, chapter, result))
                }
            }
            .onFailure {
                currentCoroutineContext().ensureActive()
                AppLog.put(
                    "${source.bookSourceName}\n书源执行回调事件${event}出错\n${it.localizedMessage}",
                    it,
                    true,
                )
            }
    }

    fun callBackSource(scope: CoroutineScope, event: String, source: BookSource) {
        val jsStr = source.getContentRule().callBackJs
        if (jsStr.isNullOrEmpty()) return
        scope.launch(IO) {
            kotlin
                .runCatching {
                    withTimeout(30000L) {
                        DartSourceEngine.evaluate(source, jsStr, bindings(event, null, null, null))
                    }
                }
                .onFailure {
                    currentCoroutineContext().ensureActive()
                    AppLog.put(
                        "${source.bookSourceName}\n书源执行回调事件${event}出错\n${it.localizedMessage}",
                        it,
                        true,
                    )
                }
        }
    }

    private fun bindings(event: String, book: Book?, chapter: BookChapter?, result: String?) =
        mapOf(
            "event" to event,
            "result" to result,
            "book" to book?.let(DartSourceEngine::jsonObject),
            "chapter" to chapter?.let(DartSourceEngine::jsonObject),
        )
}
