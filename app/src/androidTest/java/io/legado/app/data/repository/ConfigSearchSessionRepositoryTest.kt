package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConfigSearchSessionRepositoryTest {
    @Test
    fun completeLargeQueryAndSelectionRestoreFromAtomicBackupAndRejectOldRevision() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = FileConfigSearchSessionRepository(context)
        val id = UUID.randomUUID().toString()
        val file = File(context.filesDir, "config-search/$id.json")
        try {
            val query = "query".repeat(300000)
            val draft =
                ConfigSearchDraft(
                    8,
                    query,
                    2,
                    9,
                    true,
                    ConfigSearchRequest("owned", query),
                    "owned",
                )
            repository.write(id, draft)
            repository.write(id, draft.copy(revision = 7, text = "stale"))
            assertTrue(file.length() > 1_000_000)
            assertEquals(draft, FileConfigSearchSessionRepository(context).read(id))
            file.copyTo(File(file.path + ".bak"), overwrite = true)
            file.writeText("interrupted")
            assertEquals(draft, repository.read(id))
        } finally {
            AtomicFile(file).delete()
            AtomicFile(File(file.path + ".closed")).delete()
        }
    }

    @Test
    fun closeAndBackupCloseFenceLateWritersWithoutTouchingAnotherSession() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = FileConfigSearchSessionRepository(context)
        val id = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "config-search")
        try {
            repository.write(id, ConfigSearchDraft(8, "owned"))
            repository.write(other, ConfigSearchDraft(8, "other"))
            repository.release(id)
            assertTrue(
                File(directory, "$id.json.closed").renameTo(File(directory, "$id.json.closed.bak"))
            )
            assertTrue(runCatching { repository.write(id, ConfigSearchDraft(9, "late")) }.isFailure)
            assertTrue(runCatching { repository.read(id) }.isFailure)
            assertFalse(File(directory, "$id.json").exists())
            assertEquals("other", repository.read(other).text)
        } finally {
            listOf(id, other).forEach {
                AtomicFile(File(directory, "$it.json")).delete()
                AtomicFile(File(directory, "$it.json.closed")).delete()
            }
        }
    }
}
