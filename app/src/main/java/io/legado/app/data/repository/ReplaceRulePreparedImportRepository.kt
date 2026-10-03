package io.legado.app.data.repository

import kotlinx.coroutines.*
import java.util.UUID

/** Reuses the existing parser and private import session; only its UUID crosses a host boundary. */
interface ReplaceRulePreparedImportRepository {
    suspend fun prepare(source: String): String
    suspend fun release(session: String)
}
class AppReplaceRulePreparedImportRepository(private val repository: ReplaceRuleImportRepository) : ReplaceRulePreparedImportRepository {
    override suspend fun prepare(source: String): String {
        val session = UUID.randomUUID().toString()
        try {
            val items = repository.read(source)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { repository.stage(session, items) }
            currentCoroutineContext().ensureActive()
            return session
        } catch (error: Throwable) {
            withContext(Dispatchers.IO + NonCancellable) { repository.release(session) }
            throw error
        }
    }
    override suspend fun release(session: String) = repository.release(session)
}
