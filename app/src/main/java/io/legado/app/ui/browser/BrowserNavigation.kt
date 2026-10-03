package io.legado.app.ui.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import io.legado.app.data.repository.AppBrowserNavigationStore
import io.legado.app.model.browser.BrowserRequest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Browser entry point for new callers; large HTML and request metadata never enter an Intent. */
internal object BrowserNavigation {
    const val PREPARED_TICKET = "browserPreparedNavigation"

    suspend fun prepare(context: Context, request: BrowserRequest): String {
        val ticket = UUID.randomUUID().toString()
        val store = AppBrowserNavigationStore(context)
        try {
            withContext(Dispatchers.IO) { store.prepare(ticket, request) }
            return ticket
        } catch (canceled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { store.abandon(ticket) }
            throw canceled
        }
    }

    fun intent(context: Context, ticket: String): Intent {
        require(UUID.fromString(ticket).toString() == ticket)
        return Intent(context, WebViewActivity::class.java).putExtra(PREPARED_TICKET, ticket)
    }

    fun startPrepared(context: Context, ticket: String) {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            "Prepared browser navigation must start on the main thread"
        }
        val prepared = intent(context, ticket)
        if (context !is Activity) prepared.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(prepared)
    }

    suspend fun abandon(context: Context, ticket: String) {
        withContext(NonCancellable + Dispatchers.IO) {
            AppBrowserNavigationStore(context).abandon(ticket)
        }
    }
}
