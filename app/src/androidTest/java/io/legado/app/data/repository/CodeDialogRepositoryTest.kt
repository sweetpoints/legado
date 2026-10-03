package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CodeDialogRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ids = mutableListOf<String>()

    private fun id() = UUID.randomUUID().toString().also { ids += it }

    @After
    fun cleanup() {
        ids.forEach { AtomicFile(File(context.filesDir, "code-dialog-drafts/$it.json")).delete() }
    }

    @Test
    fun largeDraftRoundTripsAcrossRepositoryInstancesAndOlderRevisionCannotOverwrite() =
        runBlocking {
            val id = id()
            val draft = CodeDialogDraft("body".repeat(150_000), "alternate".repeat(40_000), 9)
            AtomicCodeDialogRepository(context).write(id, draft)
            val restored = AtomicCodeDialogRepository(context)
            assertEquals(draft, restored.read(id))
            restored.write(id, CodeDialogDraft("stale", null, 3))
            assertEquals(draft, restored.read(id))
        }

    @Test
    fun clearedAlternateIsPersistedAndAtomicBackupCanBeRecovered() = runBlocking {
        val id = id()
        val repo = AtomicCodeDialogRepository(context)
        repo.write(id, CodeDialogDraft("original", "derived", 1))
        repo.write(id, CodeDialogDraft("edited", null, 2))
        val file = File(context.filesDir, "code-dialog-drafts/$id.json")
        assertTrue(file.renameTo(File(file.path + ".bak")))
        assertEquals(
            CodeDialogDraft("edited", null, 2),
            AtomicCodeDialogRepository(context).read(id),
        )
    }

    @Test
    fun traversalIsRejectedWithoutCreatingFilesOutsideDraftDirectory() = runBlocking {
        val repo = AtomicCodeDialogRepository(context)
        assertTrue(
            runCatching { repo.write("../invalid", CodeDialogDraft("bad", null, 1)) }.isFailure
        )
        assertTrue(runCatching { repo.read("../invalid") }.isFailure)
    }
}
