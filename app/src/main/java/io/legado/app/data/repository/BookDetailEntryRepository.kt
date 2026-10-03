package io.legado.app.data.repository

import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Prepares the existing private session before crossing an Activity Binder boundary. */
class BookDetailEntryRepository(
    private val sessions: BookDetailSessionRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun prepare(identity: BookDetailIdentity): String {
        val ticket = UUID.randomUUID().toString()
        try {
            withContext(ioDispatcher) {
                sessions.write(ticket, BookDetailSession(identity))
            }
            return ticket
        } catch (error: Throwable) {
            // A cancelled dispatcher return can follow an accepted disk write. Only this newly
            // allocated, never-delivered ticket is released; no neighbouring session is touched.
            withContext(NonCancellable) { sessions.release(ticket) }
            throw error
        }
    }

    suspend fun abandon(ticket: String) {
        withContext(NonCancellable) { sessions.release(ticket) }
    }
}
