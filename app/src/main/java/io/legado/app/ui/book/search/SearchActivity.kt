package io.legado.app.ui.book.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.preferences.AppBookSearchPreferencesStore
import io.legado.app.data.preferences.DefaultBookSearchPreferencesRepository
import io.legado.app.data.repository.AppBookSearchEngineFactory
import io.legado.app.data.repository.AppBookSearchMetadataStore
import io.legado.app.data.repository.BookDetailIdentity
import io.legado.app.data.repository.DefaultBookSearchEngineRepository
import io.legado.app.data.repository.DefaultBookSearchMetadataRepository
import io.legado.app.data.repository.FileBookSearchDraftRepository
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.webBook.BookSearchEffect
import io.legado.app.model.webBook.BookSearchReceipt
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.info.BookInfoNavigation
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import java.util.UUID
import kotlinx.coroutines.Dispatchers

/** Search UI is Compose; platform navigation receives only privately prepared identities. */
class SearchActivity : BaseComposeActivity(), SearchScopeDialog.Callback {
    internal val model by
        viewModels<BookSearchViewModel> {
            viewModelFactory {
                initializer {
                    val application = applicationContext
                    val saved = createSavedStateHandle()
                    saved.remove<String>("key")
                    saved.remove<String>("searchScope")
                    saved.remove<String>(BookSearchNavigation.PREPARED_TICKET)
                    if (saved.get<String>("searchSession") == null) {
                        intent.getStringExtra(BookSearchNavigation.PREPARED_TICKET)?.let { ticket ->
                            UUID.fromString(ticket)
                            saved["searchSession"] = ticket
                            saved["searchPreparedSession"] = true
                        }
                    }
                    BookSearchViewModel(
                        drafts = FileBookSearchDraftRepository(application),
                        preferences =
                            DefaultBookSearchPreferencesRepository(
                                AppBookSearchPreferencesStore(application)
                            ),
                        metadata =
                            DefaultBookSearchMetadataRepository(AppBookSearchMetadataStore()),
                        savedState = saved,
                        cleanupFailure = { error -> AppLog.put("清理搜索输入失败", error) },
                        engineFactory = { owner ->
                            DefaultBookSearchEngineRepository(AppBookSearchEngineFactory(owner))
                        },
                    )
                }
            }
        }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        receiveIntent(intent, newIntent = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveIntent(intent, newIntent = true)
    }

    private fun receiveIntent(received: Intent, newIntent: Boolean) {
        val ticket = received.getStringExtra(BookSearchNavigation.PREPARED_TICKET)
        val query = received.getStringExtra("key")
        val scope = received.getStringExtra("searchScope")
        // Compatible incoming extras are copied to private storage, never default SavedState args.
        received.removeExtra("key")
        received.removeExtra("searchScope")
        if (ticket != null) {
            UUID.fromString(ticket)
            val initialized = model
            if (newIntent) initialized.receiveInput(ticket)
        } else {
            model.receiveLegacyInput(query, scope, newIntent)
        }
    }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        BookSearchRoute(
            model = model,
            available = { !isFinishing && !supportFragmentManager.isStateSaved },
            prepare = ::prepare,
            handle = ::handle,
            abandon = ::abandon,
            close = ::finish,
        )
    }

    private suspend fun prepare(receipt: BookSearchReceipt): PreparedBookSearchEffect {
        val ticket =
            if (receipt.effect == BookSearchEffect.BookInfo) {
                BookInfoNavigation.prepare(
                    applicationContext,
                    BookDetailIdentity(
                        name = receipt.name.orEmpty(),
                        author = receipt.author.orEmpty(),
                        bookUrl = receipt.bookId.orEmpty(),
                    ),
                )
            } else null
        return PreparedBookSearchEffect(receipt, ticket)
    }

    private suspend fun abandon(prepared: PreparedBookSearchEffect) {
        prepared.bookInfoTicket?.let { ticket ->
            BookInfoNavigation.abandon(applicationContext, ticket)
        }
    }

    private fun handle(prepared: PreparedBookSearchEffect) {
        val receipt = prepared.receipt
        when (receipt.effect) {
            BookSearchEffect.BookInfo -> {
                val ticket = requireNotNull(prepared.bookInfoTicket)
                startActivity(BookInfoNavigation.intent(this, ticket))
            }
            BookSearchEffect.Scope -> {
                val previous =
                    supportFragmentManager.findFragmentByTag(SCOPE_TAG) as? SearchScopeDialog
                if (previous?.arguments?.getString(SearchScopeDialog.REQUEST_ID) == receipt.id)
                    return
                previous?.dismissNow()
                SearchScopeDialog()
                    .apply {
                        arguments =
                            Bundle().apply { putString(SearchScopeDialog.REQUEST_ID, receipt.id) }
                    }
                    .show(supportFragmentManager, SCOPE_TAG)
            }
            BookSearchEffect.Sources -> startActivity(Intent(this, BookSourceActivity::class.java))
            BookSearchEffect.Log -> {
                if (supportFragmentManager.findFragmentByTag("AppLogDialog") == null) {
                    showDialogFragment<AppLogDialog>()
                }
            }
            BookSearchEffect.Toast -> receipt.text?.let { toastOnUi(it) }
        }
    }

    override fun onSearchScopeOk(searchScope: SearchScope) {
        model.selectScope(searchScope.toString())
    }

    override fun onSearchScopeOk(searchScope: SearchScope, requestId: String?) {
        if (requestId == null) onSearchScopeOk(searchScope)
        else model.scopeSelected(requestId, searchScope.toString())
    }

    override fun onSearchScopeDismiss(requestId: String?) {
        requestId?.let(model::scopeDismissed)
    }

    override fun onStop() {
        val captured = model
        if (captured.state.value.ready) {
            Coroutine.async(context = Dispatchers.Main.immediate) { captured.checkpoint() }
                .onError { AppLog.put("保存搜索页面草稿失败", it) }
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            val captured = model
            captured.stop()
            Coroutine.async(context = Dispatchers.Main.immediate) { captured.release() }
                .onError { AppLog.put("清理搜索页面草稿失败", it) }
        }
        super.onDestroy()
    }

    companion object {
        private const val SCOPE_TAG = "SearchScopeDialog"

        fun start(context: Context, key: String?, searchScope: String? = null) {
            BookSearchNavigation.start(context, key, searchScope)
        }

        fun start(context: Context, source: BookSource, key: String? = null) {
            start(context, key, SearchScope(source).toString())
        }

        fun start(context: Context, source: BookSourcePart, key: String? = null) {
            start(context, key, SearchScope(source).toString())
        }
    }
}
