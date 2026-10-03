package io.legado.app.ui.book.toc

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import io.legado.app.data.repository.FileTocHostSessionRepository
import io.legado.app.data.repository.TocHostSession
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Full catalogue identities stay in an owned file; only the UUID crosses Android boundaries. */
object TocNavigation {
    const val PREPARED_SESSION = "tocPreparedSession"

    private fun repository(context: Context) =
        FileTocHostSessionRepository(File(context.applicationContext.filesDir, "toc-host-state"))

    suspend fun prepare(context: Context, bookUrl: String): String {
        val session = UUID.randomUUID().toString()
        try {
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    check(repository(context).write(session, TocHostSession(bookUrl, "", 0))) {
                        "目录准备失败"
                    }
                }
            }
            return session
        } catch (error: CancellationException) {
            withContext(Dispatchers.IO + NonCancellable) { repository(context).release(session) }
            throw error
        }
    }

    fun intent(context: Context, session: String): Intent {
        validate(session)
        return Intent(context, TocActivity::class.java).putExtra(PREPARED_SESSION, session)
    }

    suspend fun abandon(context: Context, session: String) {
        validate(session)
        // A host that has claimed the session is protected from an old caller's cleanup.
        repository(context).release(session)
    }

    internal fun validate(session: String) {
        require(UUID.fromString(session).toString() == session)
    }
}

/** Prepared callers keep the established typed chapter result contract. */
class PreparedTocActivityResult : ActivityResultContract<String, Array<Any>?>() {
    override fun createIntent(context: Context, input: String): Intent =
        TocNavigation.intent(context, input)

    override fun parseResult(resultCode: Int, intent: Intent?): Array<Any>? =
        TocActivityResult().parseResult(resultCode, intent)
}
