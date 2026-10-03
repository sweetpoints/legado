package io.legado.app.ui.book.search

import android.app.Activity
import android.content.Context
import android.content.Intent
import io.legado.app.constant.AppLog
import io.legado.app.data.preferences.AppBookSearchPreferencesStore
import io.legado.app.data.preferences.DefaultBookSearchPreferencesRepository
import io.legado.app.data.repository.BookSearchInputRepository
import io.legado.app.data.repository.FileBookSearchDraftRepository
import java.lang.ref.WeakReference
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Public starts retain their API while complete input is prepared before a small Intent. */
internal object BookSearchNavigation {
    const val PREPARED_TICKET = "bookSearchPreparedTicket"
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun intent(context: Context, ticket: String): Intent {
        UUID.fromString(ticket)
        return Intent(context, SearchActivity::class.java).putExtra(PREPARED_TICKET, ticket)
    }

    fun start(context: Context, query: String?, scope: String?) {
        val application = context.applicationContext
        val activity = (context as? Activity)?.let { WeakReference(it) }
        val inputs =
            BookSearchInputRepository(
                FileBookSearchDraftRepository(application),
                DefaultBookSearchPreferencesRepository(AppBookSearchPreferencesStore(application)),
            )
        owner.launch {
            var unreturned: String? = null
            try {
                val ticket = inputs.prepare(query, scope)
                unreturned = ticket
                currentCoroutineContext().ensureActive()
                val destination =
                    if (activity != null) {
                        activity.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                            ?: return@launch
                    } else {
                        application
                    }
                val prepared = intent(destination, ticket)
                if (destination !is Activity) prepared.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                destination.startActivity(prepared)
                // A successful synchronous platform launch transfers the private file ownership.
                unreturned = null
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                AppLog.put("准备搜索页面失败", error)
            } finally {
                unreturned?.let { ticket ->
                    withContext(NonCancellable) {
                        try {
                            inputs.abandon(ticket)
                        } catch (error: Exception) {
                            AppLog.put("清理未交付搜索输入失败", error)
                        }
                    }
                }
            }
        }
    }
}
