package io.legado.app.ui.login

import android.app.Application
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
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

    private val repository = io.legado.app.data.repository.AppSourceLoginRepository()
    private var loadedRequest: io.legado.app.data.repository.SourceLoginRequest? = null

    internal fun applyInitialized(request: io.legado.app.data.repository.SourceLoginRequest,
        value: io.legado.app.data.repository.SourceLoginSnapshot) {
        loadedRequest = request
        source = value.source; book = value.book; chapter = value.chapter; bookType = value.bookType
        headerMap = value.headers.toMap(); loginInfo = value.loginInfo.toMutableMap()
        initializationState.value = if (value.source != null) SourceLoginInitialization.Ready
            else SourceLoginInitialization.Failed("未找到书源")
    }

    /** Existing form retry and public callers retain their one-shot completion API. */
    fun initData(intent: Intent, success: (bookSource: BaseSource) -> Unit, error: () -> Unit) {
        val request = if (intent.getStringExtra("key") == null && intent.getIntExtra("bookType", 0) == 0 && loadedRequest != null)
            loadedRequest!! else io.legado.app.data.repository.SourceLoginRequest(intent.getIntExtra("bookType", 0),
                intent.getStringExtra("type"), intent.getStringExtra("key"), intent.getStringExtra("bookUrl"))
        initializationState.value = SourceLoginInitialization.Loading
        execute { repository.load(request) }.onSuccess { value ->
            applyInitialized(request, value)
            value.source?.let(success) ?: context.toastOnUi("未找到书源")
        }.onError {
            initializationState.value = SourceLoginInitialization.Failed(it.localizedMessage ?: it.toString())
            error.invoke()
            AppLog.put("登录 UI 初始化失败\n$it", it, true)
        }
    }
}
