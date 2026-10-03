package io.legado.app.ui.book.info

import android.app.Activity
import android.content.Context
import android.content.Intent
import io.legado.app.data.repository.BookDetailEntryRepository
import io.legado.app.data.repository.BookDetailIdentity
import io.legado.app.data.repository.FileBookDetailSessionRepository
import io.legado.app.data.repository.RoomBookDetailNetworkStorageRepository
import io.legado.app.data.repository.RoomBookDetailRepository
import io.legado.app.data.repository.RoomBookDetailStorageRepository
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** New callers transfer only a private UUID; legacy name/author/URL extras remain compatible. */
object BookInfoNavigation {
    const val PREPARED_TICKET = "bookInfoPreparedTicket"

    suspend fun prepare(context: Context, identity: BookDetailIdentity): String =
        repository(context).prepare(identity)

    fun intent(context: Context, ticket: String): Intent {
        UUID.fromString(ticket)
        return Intent(context, BookInfoActivity::class.java).putExtra(PREPARED_TICKET, ticket)
    }

    suspend fun startPrepared(context: Context, ticket: String) {
        try {
            currentCoroutineContext().ensureActive()
            // Once startActivity accepts the intent, cancellation must not delete the session
            // now owned by the new Activity merely because the return dispatcher changed.
            withContext(NonCancellable + Dispatchers.Main) {
                val preparedIntent = intent(context, ticket)
                if (context !is Activity) preparedIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(preparedIntent)
            }
        } catch (error: Throwable) {
            repository(context).abandon(ticket)
            throw error
        }
    }

    suspend fun abandon(context: Context, ticket: String) {
        repository(context).abandon(ticket)
    }

    private fun repository(context: Context): BookDetailEntryRepository =
        BookDetailEntryRepository(
            FileBookDetailSessionRepository(
                context.applicationContext,
                RoomBookDetailStorageRepository(),
                RoomBookDetailRepository(),
                RoomBookDetailNetworkStorageRepository(),
            )
        )
}
