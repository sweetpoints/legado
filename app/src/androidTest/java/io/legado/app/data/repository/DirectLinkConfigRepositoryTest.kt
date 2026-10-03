package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.legado.app.help.DirectLinkUpload
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class DirectLinkConfigRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(context.cacheDir, "direct-config-${UUID.randomUUID()}")
    private val original = DirectLinkUpload.Rule("upload", "$.url", "Fixture", true, 21)
    @After fun cleanup() { directory.deleteRecursively() }
    private fun repository(store: (DirectLinkUpload.Rule) -> Unit = {}) = AppDirectLinkConfigRepository(context, directory, { original.copy() }, store, { listOf(original.copy()) }, { "result" })
    @Test fun actualAtomicFileRestoresCompleteRuleAndResultAndRejectsStaleRevisions() = runBlocking {
        val repo = repository(); val first = repo.open(null)
        val second = first.copy(draft = first.draft.copy(downloadRule = "x".repeat(400000)), revision = 2, result = "y".repeat(300000))
        repo.write(second); repository().write(first); assertEquals(second, repository().open(first.id))
        assertEquals(listOf(DirectLinkDraft.from(original)), repo.defaults())
    }
    @Test fun releasePreventsLateWriterRecreatingOwnedFileAndUnrelatedSessionSurvives() = runBlocking {
        val repo = repository(); val first = repo.open(null); val other = repo.open(null); repository().release(first.id)
        assertTrue(runCatching { repo.write(first.copy(revision = 3)) }.isFailure)
        assertFalse(File(directory, "${first.id}.json").exists()); assertEquals(other, repo.open(other.id))
    }
    @Test fun explicitSaveAndTestPreserveOriginalRuleFieldsWithoutImplicitConfigWrite() = runBlocking {
        val stored = mutableListOf<DirectLinkUpload.Rule>(); val repo = repository { stored += it.copy() }; val session = repo.open(null)
        repo.write(session.copy(revision = 2, draft = session.draft.copy(summary = "New"))); assertTrue(stored.isEmpty())
        assertEquals("result", repo.test(session.draft)); assertTrue(stored.isEmpty()); repo.save(session.draft)
        assertEquals(original, stored.single())
    }
}
