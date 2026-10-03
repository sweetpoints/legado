package io.legado.app.data.repository

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.ui.replace.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class ReplaceManagementNativeRepositoryTest {
    private val directory =
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "replace-native-${UUID.randomUUID()}",
        )

    @After
    fun after() {
        directory.deleteRecursively()
    }

    @Test
    fun actualPassphraseHasReplacementTypeAndLocalUrlsDoNotAcquireUploadMetadata() = runBlocking {
        val repository =
            AppReplaceManagementSharingRepository(
                summary = { "Actual upload summary" },
                expiryDays = { 0 },
            )
        assertEquals(
            ReplaceManagementShareFeedback("content://local"),
            repository.feedback("content://local"),
        )
        val url = "https://example.com/export.json"
        val feedback = repository.feedback(url)
        assertEquals("Actual upload summary", feedback.summary)
        assertTrue(feedback.canEncode)
        val decoded =
            SourceSharePassphrase.decode(repository.passphrase(url))
                as SourceSharePassphrase.DecodeResult.Success
        assertEquals(url, decoded.value.url)
        assertEquals(SourceSharePassphrase.Type.REPLACE_RULE, decoded.value.type)
    }

    @Test
    fun realAtomicCheckpointRestoresFullPendingNativeInputAndExportFeedbackWithoutBundle() =
        runBlocking {
            val repository = FileReplaceManagementSessionRepository(directory)
            val id = UUID.randomUUID().toString()
            val export = ReplaceManagementExport("owned-file.json")
            val value =
                ReplaceManagementCheckpoint(
                    revision = 11,
                    pending =
                        ReplaceManagementPrepared(
                            "ImportInput",
                            UUID.randomUUID().toString(),
                            input = "Q".repeat(2000000),
                            returningNonce = "old",
                        ),
                    exportFile = export,
                    feedback =
                        ReplaceManagementShareFeedback("https://exact", "Summary", true, "Phrase"),
                    returnedNonce = "return",
                )
            repository.write(id, value)
            assertEquals(value, FileReplaceManagementSessionRepository(directory).read(id))
        }

    @Test
    fun realIoRepeatedDeliveredExportsAndFailedDraftRetryReleaseOnlyCurrentOwnerFiles() =
        runBlocking {
            val exports = File(directory, "exports")
            val sessions = FileReplaceManagementSessionRepository(File(directory, "sessions"))
            val repository = AppReplaceManagementRepository(exportDirectory = exports)
            val neighbor = repository.export(emptyList())
            var failure = false
            val draft =
                object : ReplaceManagementSessionRepository by sessions {
                    override suspend fun write(
                        session: String,
                        value: ReplaceManagementCheckpoint,
                    ) {
                        if (failure) error("Draft failed")
                        sessions.write(session, value)
                    }
                }
            val store = ViewModelStore()
            val model =
                withContext(Dispatchers.Main) {
                    ReplaceManagementViewModel(repository, draft, SavedStateHandle()).also {
                        store.put("owner", it)
                        it.bind(ReplaceManagementLabels("Enabled", "Disabled", "No group"))
                    }
                }
            val own = mutableListOf<String>()
            try {
                withTimeout(10000) { model.state.first { it.loaded } }
                suspend fun deliver() {
                    val effect =
                        withTimeout(10000) { model.state.first { it.pending != null } }.pending!!
                    own += checkNotNull(model.native(effect.nonce)?.export?.path)
                    withContext(Dispatchers.Main) {
                        model.delivered(effect.nonce)
                        model.returned(effect.nonce, null)
                    }
                    withTimeout(10000) {
                        model.state.first { !it.busy && !it.waitingNative && it.pending == null }
                    }
                }
                repeat(2) {
                    withContext(Dispatchers.Main) { model.effect(ReplaceManagementAction.Export) }
                    deliver()
                }
                failure = true
                withContext(Dispatchers.Main) { model.effect(ReplaceManagementAction.Export) }
                withTimeout(10000) { model.state.first { it.error != null } }
                failure = false
                withContext(Dispatchers.Main) { model.retry() }
                deliver()
                assertEquals(3, own.distinct().size)
                assertTrue(own.all { File(it).exists() })
                withContext(Dispatchers.Main) { store.clear() }
                withTimeout(10000) { while (own.any { File(it).exists() }) delay(10) }
                assertTrue(File(neighbor.path).exists())
            } finally {
                withContext(Dispatchers.Main) { store.clear() }
                repository.releaseExport(neighbor.path)
            }
        }
}
