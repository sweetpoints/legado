package io.legado.app.ui.book.source.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.model.jsSource.JsSourceUpsert
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsSourceEditAcceptedIoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = FileJsSourceEditRepository(context)
    private val sourceUrls = mutableListOf<String>()
    private val sessions = mutableListOf<String>()

    @After
    fun cleanup() = runBlocking {
        withContext(Dispatchers.IO) {
            sourceUrls.forEach { sourceUrl -> appDb.bookSourceDao.delete(sourceUrl) }
            sessions.forEach { sessionId ->
                android.util
                    .AtomicFile(File(context.filesDir, "js-source-edit-drafts/$sessionId.json"))
                    .delete()
            }
        }
    }

    @Test
    fun realRoomAcceptAndAtomicReceiptFinishWhenCallerIsCanceled() = runBlocking {
        val sourceUrl = sourceUrl()
        val sessionId = UUID.randomUUID().toString().also { sessions += it }
        val enteredReceipt = CompletableDeferred<Unit>()
        val releaseReceipt = CompletableDeferred<Unit>()
        val operation =
            async(Dispatchers.IO) {
                JsSourceUpsert.save(
                    script(sourceUrl),
                    onAccepted = { source ->
                        assertNotNull(appDb.bookSourceDao.getBookSource(sourceUrl))
                        enteredReceipt.complete(Unit)
                        releaseReceipt.await()
                        repository.write(
                            sessionId,
                            JsSourceDraft(
                                text = "",
                                sourceUrl = source.bookSourceUrl,
                                stage = JsSourceEditStage.READY,
                                finished = true,
                                saved = true,
                            ),
                        )
                    },
                )
            }
        withTimeout(10_000) { enteredReceipt.await() }
        operation.cancel()
        releaseReceipt.complete(Unit)
        operation.join()
        assertTrue(operation.isCancelled)
        val receipt = FileJsSourceEditRepository(context).read(sessionId)
        assertTrue(receipt!!.saved)
        assertTrue(receipt.finished)
        assertEquals(sourceUrl, receipt.sourceUrl)
        assertNotNull(withContext(Dispatchers.IO) { appDb.bookSourceDao.getBookSource(sourceUrl) })
    }

    @Test
    fun originalRhinoParsingRemainsCancelableBeforeRoomAccept() = runBlocking {
        val sourceUrl = sourceUrl()
        var receipts = 0
        val failure = runCatching {
            withTimeout(200) {
                withContext(Dispatchers.IO) {
                    JsSourceUpsert.save(
                        script(sourceUrl) + "\nwhile (true) {}",
                        onAccepted = { receipts++ },
                    )
                }
            }
        }
            .exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertEquals(0, receipts)
        assertNull(withContext(Dispatchers.IO) { appDb.bookSourceDao.getBookSource(sourceUrl) })
    }

    @Test
    fun unchangedSourceStillWritesReceiptWithoutRestampingSource() = runBlocking {
        val sourceUrl = sourceUrl()
        val original = JsSourceUpsert.save(script(sourceUrl))
        val sessionId = UUID.randomUUID().toString().also { sessions += it }
        var receipts = 0
        val unchanged =
            JsSourceUpsert.save(
                original.mainJs!!,
                sourceUrl,
                onAccepted = { source ->
                    receipts++
                    repository.write(
                        sessionId,
                        JsSourceDraft(
                            "",
                            source.bookSourceUrl,
                            JsSourceEditStage.READY,
                            saved = true,
                        ),
                    )
                },
            )
        assertEquals(1, receipts)
        assertEquals(original.lastUpdateTime, unchanged.lastUpdateTime)
        assertEquals(original.mainJs, unchanged.mainJs)
        assertTrue(repository.read(sessionId)!!.saved)
    }

    @Test
    fun realAtomicSavingCheckpointRequiresExplicitRetryBeforeRoomMutation() = runBlocking {
        val sourceUrl = sourceUrl()
        val sessionId = UUID.randomUUID().toString().also { sessions += it }
        val interrupted =
            JsSourceDraft(
                text = script(sourceUrl),
                sourceUrl = null,
                stage = JsSourceEditStage.SAVING,
                revision = 8,
            )
        repository.write(sessionId, interrupted)
        val store = ViewModelStore()
        val model =
            withContext(Dispatchers.Main) {
                JsSourceEditViewModel(
                        FileJsSourceEditRepository(context),
                        SavedStateHandle(mapOf("jsSourceDraftId" to sessionId)),
                        null,
                    )
                    .also { store.put("restored", it) }
            }
        try {
            val restoredState =
                withTimeout(10_000) {
                    model.state.first { it.loaded && !it.busy }
                }
            assertTrue(restoredState.error!!.contains("点击重试"))
            assertEquals(JsSourceEditStage.SAVING, restoredState.stage)
            assertEquals(interrupted, repository.read(sessionId))
            assertNull(withContext(Dispatchers.IO) { appDb.bookSourceDao.getBookSource(sourceUrl) })
            withContext(Dispatchers.Main) { model.load() }
            withTimeout(10_000) { model.state.first { it.finished } }
            assertNotNull(
                withContext(Dispatchers.IO) { appDb.bookSourceDao.getBookSource(sourceUrl) }
            )
            assertTrue(repository.read(sessionId)!!.saved)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
        }
    }

    private fun sourceUrl(): String {
        return "https://compose-js-test.invalid/${UUID.randomUUID()}".also { sourceUrls += it }
    }

    private fun script(sourceUrl: String): String {
        return """
            var config = {
                bookSourceUrl: "$sourceUrl",
                bookSourceName: "Compose test"
            };
            function search(key, page) { return []; }
            function getChapters(book) { return []; }
            function getContent(chapter, book) { return "content"; }
        """
            .trimIndent()
    }
}
