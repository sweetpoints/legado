package io.legado.app.ui.book.searchContent

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.preferences.AppContentSearchAppearanceRepository
import io.legado.app.data.preferences.ProcessContentSearchOptionsRepository
import io.legado.app.data.repository.*
import io.legado.app.help.IntentData
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import kotlinx.coroutines.*

/**
 * Full-text search keeps the existing reader result contract while its entire page uses Compose.
 */
class SearchContentActivity : BaseComposeActivity() {
    private var incoming: List<SearchResult>? = null
    private var eInk by mutableStateOf(false)
    private val model by
        viewModels<ContentSearchViewModel> {
            viewModelFactory {
                initializer {
                    val repository =
                        DefaultContentSearchRepository(AppContentSearchStore(applicationContext))
                    val bookUrl = intent.getStringExtra("bookUrl").orEmpty()
                    val query = intent.getStringExtra("searchWord").orEmpty()
                    val position = intent.getIntExtra("searchResultIndex", 0)
                    val results = incoming
                    val saved =
                        createSavedStateHandle().apply {
                            // ComponentActivity's default arguments include Intent extras;
                            // query/results belong on disk.
                            remove<String>("searchWord")
                            remove<String>("bookUrl")
                            remove<Int>("searchResultIndex")
                        }
                    ContentSearchViewModel(
                        repository,
                        ProcessContentSearchOptionsRepository,
                        saved,
                    ) {
                        withContext(Dispatchers.Default) {
                            ContentSearchSession(
                                bookUrl,
                                query,
                                results
                                    ?.mapIndexed { index, result -> result.contentMatch(index) }
                                    .orEmpty(),
                                ProcessContentSearchOptionsRepository.current(),
                                position,
                                searchOpen = results == null,
                                initialSubmit = results == null && query.isNotBlank(),
                            )
                        }
                    }
                }
            }
        }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        if (savedInstanceState == null)
            incoming = IntentData.get<List<SearchResult>>("searchResultList")?.toList()
        lifecycleScope.launch { eInk = AppContentSearchAppearanceRepository().eInk() }
    }

    override fun observeLiveBus() {
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            model.cached(book.bookUrl, chapter.getFileName())
        }
    }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        val background = bottomBackground
        ContentSearchRoute(
            model,
            { !isFinishing && !isDestroyed },
            ::deliver,
            ::finish,
            eInk = eInk,
            bottomColor = Color(background),
            bottomForeground = Color(getPrimaryTextColor(ColorUtils.isColorLight(background))),
        )
    }

    fun startContentSearch(query: String) {
        model.query(query.trim())
        model.submit()
    }

    private fun deliver(value: ReaderContentSearchDelivery) {
        postEvent(EventBus.SEARCH_RESULT, value.results)
        val key = System.currentTimeMillis()
        IntentData.put("searchResult$key", value.selected)
        IntentData.put("searchResultList$key", value.results)
        setResult(RESULT_OK, Intent().putExtra("key", key).putExtra("index", value.index))
    }

    override fun onStop() {
        if (!isFinishing) {
            val captured = model
            Coroutine.async(context = Dispatchers.Main.immediate) { captured.flush() }
                .onError { AppLog.put("保存全文搜索会话失败\n${it.localizedMessage}", it) }
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
            val captured = model
            // This bounded application-owned IO cleanup survives clearing the Activity's
            // ViewModelStore.
            captured.stop()
            Coroutine.async(context = Dispatchers.Main.immediate) { captured.releaseOwnedSession() }
                .onError { AppLog.put("清理全文搜索会话失败\n${it.localizedMessage}", it) }
        }
        super.onDestroy()
    }
}
