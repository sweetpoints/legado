package io.legado.app.data.repository

import io.legado.app.data.preferences.BookSearchPreferencesRepository
import io.legado.app.model.webBook.BookSearchDraft
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Public navigation keeps long queries/source URLs in the private session, carrying only its ID.
 */
internal class BookSearchInputRepository(
    private val drafts: BookSearchDraftRepository,
    private val preferences: BookSearchPreferencesRepository,
    private val sessionId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun prepare(query: String?, scope: String?): String {
        val session = sessionId()
        try {
            val original = drafts.open(session)
            val resolvedScope = scope ?: preferences.load().scope
            val text = query.orEmpty()
            drafts.write(
                session,
                BookSearchDraft(
                    revision = original.revision + 1,
                    query = text,
                    selectionStart = text.length,
                    selectionEnd = text.length,
                    scope = resolvedScope,
                    navigationSeed = true,
                ),
            )
            currentCoroutineContext().ensureActive()
            return session
        } catch (error: Throwable) {
            // A canceled preparation owns only this newly allocated input, never another session.
            withContext(NonCancellable) {
                try {
                    drafts.release(session)
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
            }
            throw error
        }
    }

    suspend fun abandon(session: String) {
        drafts.release(session)
    }
}
