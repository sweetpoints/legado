package io.legado.app.ui.login

import android.app.Application
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.script.rhino.runScriptWithContext
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.getStoredLoginInfoMap
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.AudioPlay
import io.legado.app.model.AutoTask
import io.legado.app.model.ReadBook
import io.legado.app.model.VideoPlay
import io.legado.app.utils.toastOnUi

sealed interface SourceLoginInitialization {
    data object Idle : SourceLoginInitialization
    data object Loading : SourceLoginInitialization
    data object Ready : SourceLoginInitialization
    data class Failed(val message: String) : SourceLoginInitialization
}

class SourceLoginViewModel(application: Application) : BaseViewModel(application) {

    private val initializationState = MutableStateFlow<SourceLoginInitialization>(SourceLoginInitialization.Idle)
    val initialization = initializationState.asStateFlow()
    var source: BaseSource? = null
    var headerMap: Map<String, String> = emptyMap()
    var book: Book? = null
    var bookType: Int = 0
    var chapter: BookChapter? = null
    var loginInfo: MutableMap<String, String> = mutableMapOf()

    fun initData(intent: Intent, success: (bookSource: BaseSource) -> Unit, error: () -> Unit) {
        initializationState.value = SourceLoginInitialization.Loading
        execute {
            bookType = intent.getIntExtra("bookType", 0)
            when (bookType) {
                BookType.text -> {
                    source = ReadBook.bookSource
                    book = ReadBook.book?.also {
                        chapter = appDb.bookChapterDao.getChapter(it.bookUrl, ReadBook.durChapterIndex)
                    }
                }

                BookType.audio -> {
                    source = AudioPlay.bookSource
                    book = AudioPlay.book
                    chapter = AudioPlay.durChapter
                }

                BookType.video -> {
                    source = VideoPlay.source
                    book = VideoPlay.book
                    chapter = VideoPlay.chapter
                }

                else -> {
                    val sourceKey = intent.getStringExtra("key")
                        ?: throw NoStackTraceException("没有参数")
                    val type = intent.getStringExtra("type")
                    source = when (type) {
                        "bookSource" ->  appDb.bookSourceDao.getBookSource(sourceKey)
                        "rssSource" -> appDb.rssSourceDao.getByKey(sourceKey)
                        "httpTts" -> appDb.httpTTSDao.get(sourceKey.toLong())
                        "autoTask" -> AutoTask.get(sourceKey)?.let(AutoTask::buildSource)
                        else -> null
                    }
                    val bookUrl = intent.getStringExtra("bookUrl")
                    book = bookUrl?.let {
                        appDb.bookDao.getBook(it) ?: appDb.searchBookDao.getSearchBook(it)?.toBook()
                    }
                }
            }
            headerMap = runScriptWithContext {
                source?.getHeaderMap(true) ?: emptyMap()
            }
            loginInfo = source?.getStoredLoginInfoMap() ?: mutableMapOf()
            source
        }.onSuccess {
            if (it != null) {
                initializationState.value = SourceLoginInitialization.Ready
                success.invoke(it)
            } else {
                initializationState.value = SourceLoginInitialization.Failed("未找到书源")
                context.toastOnUi("未找到书源")
            }
        }.onError {
            initializationState.value = SourceLoginInitialization.Failed(it.localizedMessage ?: it.toString())
            error.invoke()
            AppLog.put("登录 UI 初始化失败\n$it", it, true)
        }
    }

}
