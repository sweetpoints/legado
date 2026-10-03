package io.legado.app.ui.book.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.IntentData
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookSourcePreparedCheckTest {
    @Test
    fun cancellingPreparedOwnerReleasesItsPayloadWithoutStartingService() = runBlocking {
        val source =
            BookSource(
                bookSourceUrl = "https://prepared.invalid/${UUID.randomUUID()}",
                bookSourceName = "Prepared owner",
            )
        appDb.bookSourceDao.insert(source)
        val sessionId = checkNotNull(Debug.tryStartCheckSession())
        val preparedResult = CompletableDeferred<CheckSource.PreparedCheck>()
        try {
            val owner = launch {
                val prepared =
                    CheckSource.prepare(
                        listOf(appDb.bookSourceDao.getBookSourcePart(source.bookSourceUrl)!!),
                        sessionId,
                    )
                try {
                    preparedResult.complete(prepared)
                    awaitCancellation()
                } finally {
                    CheckSource.release(prepared)
                }
            }
            val prepared = withTimeout(5_000) { preparedResult.await() }
            assertTrue(Debug.isChecking(sessionId))
            assertFalse(Debug.isCheckServiceStarted(sessionId))
            owner.cancelAndJoin()
            assertFalse(Debug.isChecking(sessionId))
            assertFalse(Debug.isCheckServiceStarted(sessionId))
            assertNull(IntentData.get<Any>(prepared.selectedSourcesKey))
            CheckSource.release(prepared)
        } finally {
            Debug.finishChecking(sessionId)
            appDb.bookSourceDao.delete(source)
        }
    }
}
