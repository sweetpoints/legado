package io.legado.app.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.legado.app.model.backup.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class FileBackupSettingsDraftRepositoryTest {
    @Test fun actualPrivateFormsCredentialsAndLargeTaskPayloadRestoreRejectStaleWritesAndClosedOwners() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val id = UUID.randomUUID().toString(); val other = UUID.randomUUID().toString()
        val repo = FileBackupSettingsDraftRepository(context); val second = FileBackupSettingsDraftRepository(context)
        val file = File(context.filesDir, "backup-settings-drafts/$id.json"); val otherFile = File(file.parentFile, "$other.json")
        try {
            repo.open(id); repo.open(other)
            val value = BackupSettingsDraft(97, BackupForm.Password, "synthetic-private-fixture", false, false, "",
                mapOf("books" to false, "cookies" to true), listOf("restore-file", "large".repeat(100000)),
                BackupTaskDraft("task", BackupTaskKind.LanReceive, "synthetic-qr".repeat(100000), false, BackupTaskPhase.Running))
            repo.write(id, value); second.write(id, value.copy(revision = 2, text = "stale")); assertEquals(value, second.open(id))
            val otherValue = BackupSettingsDraft(revision = 5, form = BackupForm.Automatic, autoEnabled = false, intervalText = "37")
            repo.write(other, otherValue)
            withContext(Dispatchers.IO) { check(file.renameTo(File(file.path + ".bak"))); File(file.path + ".new").writeText("interrupted") }
            assertEquals(value, second.open(id)); repo.release(id)
            assertFalse(file.exists()); assertFalse(File(file.path + ".bak").exists()); assertFalse(File(file.path + ".new").exists())
            withContext(Dispatchers.IO) { check(File(file.path + ".closed").renameTo(File(file.path + ".closed.bak"))) }
            assertTrue(runCatching { second.open(id) }.isFailure); assertTrue(runCatching { second.write(id, value.copy(revision = 98)) }.isFailure)
            assertEquals(otherValue, second.open(other))
        } finally {
            repo.release(other)
            withContext(Dispatchers.IO) { listOf(file, otherFile).forEach { base -> File(base.path + ".closed").delete(); File(base.path + ".closed.bak").delete() } }
        }
    }
}
