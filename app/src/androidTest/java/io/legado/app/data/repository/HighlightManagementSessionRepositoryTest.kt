package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.utils.GSON
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class HighlightManagementSessionRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var directory: File

    @Before
    fun before() {
        directory = File(context.cacheDir, "highlight-session-test-" + UUID.randomUUID())
        directory.mkdirs()
    }

    @After
    fun after() {
        directory.deleteRecursively()
    }

    private fun repo() = FileHighlightManagementSessionRepository(context, directory)

    @Test
    fun actualAtomicFileRestoresLargeGroupConfirmationAndFullExportPayload() = runBlocking {
        val ticket = UUID.randomUUID().toString()
        val large = "group".repeat(200000)
        val row =
            HighlightRule(
                id = 2,
                name = "N",
                pattern = "P",
                style = "style",
                scope = large,
                group = large,
                isEnabled = false,
                applyToTitle = true,
            )
        val effect =
            HighlightManagementEffect(
                UUID.randomUUID().toString(),
                HighlightManagementAction.Export,
                rules = listOf(HighlightManagedRule.from(row)),
            )
        val draft =
            HighlightManagementDraft(
                filter = large,
                selection = setOf(row.uuid),
                deletion = setOf(row.uuid),
                deletionName = large,
                effects = listOf(effect),
                revision = 5,
            )
        repo().write(ticket, draft)
        assertEquals(draft, repo().read(ticket))
        assertEquals(
            draft,
            FileHighlightManagementSessionRepository(context, directory).read(ticket),
        )
    }

    @Test
    fun independentRepositoriesDoNotLetOlderRevisionOverwriteLatestDurableClaim() = runBlocking {
        val ticket = UUID.randomUUID().toString()
        val first = repo()
        val second = repo()
        val pending =
            HighlightManagementEffect(
                UUID.randomUUID().toString(),
                HighlightManagementAction.Import,
            )
        first.write(ticket, HighlightManagementDraft(effects = listOf(pending), revision = 1))
        second.write(ticket, HighlightManagementDraft(revision = 2))
        first.write(ticket, HighlightManagementDraft(effects = listOf(pending), revision = 1))
        assertTrue(second.read(ticket)!!.effects.isEmpty())
        assertEquals(2L, second.read(ticket)!!.revision)
    }

    @Test
    fun releaseRemovesBodyAndAtomicBackupsAndFencesLateWriterAcrossInstances() = runBlocking {
        val ticket = UUID.randomUUID().toString()
        val first = repo()
        val second = repo()
        first.write(ticket, HighlightManagementDraft(filter = "private", revision = 1))
        File(directory, "$ticket.json.bak").writeText("backup")
        File(directory, "$ticket.json.new").writeText("pending")
        second.release(ticket)
        assertNull(first.read(ticket))
        assertTrue(
            runCatching { first.write(ticket, HighlightManagementDraft(revision = 99)) }.isFailure
        )
        assertTrue(listOf("", ".bak", ".new").none { File(directory, "$ticket.json$it").exists() })
    }

    @Test
    fun closedMarkerBackupAlsoBlocksWriterAndReleaseDoesNotRestoreData() = runBlocking {
        val ticket = UUID.randomUUID().toString()
        val store = repo()
        store.write(ticket, HighlightManagementDraft(revision = 1))
        store.release(ticket)
        assertTrue(
            File(directory, "$ticket.closed").renameTo(File(directory, "$ticket.closed.bak"))
        )
        assertTrue(
            runCatching { repo().write(ticket, HighlightManagementDraft(revision = 2)) }.isFailure
        )
        repo().release(ticket)
        assertNull(store.read(ticket))
        assertFalse(File(directory, "$ticket.json").exists())
    }

    @Test
    fun realExportAndShareUseSameFullEnvelopeAndShareFileSurvivesRecipientDispatch() = runBlocking {
        val row =
            HighlightRule(
                id = 9,
                name = "Fixture",
                pattern = "[A]",
                isRegex = true,
                scope = "book",
                style = "{\"textColor\":123}",
                group = "A,B",
                applyToTitle = true,
                applyToBody = false,
                timeoutMillisecond = 999,
            )
        val rows = listOf(HighlightManagedRule.from(row))
        val store = FileHighlightManagementTransferRepository(context)
        val bytes =
            store.export(
                HighlightManagementEffect(
                    UUID.randomUUID().toString(),
                    HighlightManagementAction.Export,
                    rules = rows,
                )
            )
        val file =
            store.share(
                HighlightManagementEffect(
                    UUID.randomUUID().toString(),
                    HighlightManagementAction.Share,
                    rules = rows,
                )
            )
        try {
            assertEquals(bytes.toString(Charsets.UTF_8), file.readText())
            val parsed = GSON.fromJson(file.readText(), HighlightRuleFile::class.java)
            assertEquals(GSON.toJson(row), GSON.toJson(parsed.rules!!.single()))
            assertTrue(file.exists())
        } finally {
            file.delete()
        }
    }
}
