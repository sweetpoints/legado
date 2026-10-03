package io.legado.app.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class FileWelcomeImageInputRepositoryTest {
    @Test fun actualLongInputRestoresNightRejectsStaleWritesAndClosedBackupFencesLateOwnersWithoutAffectingOtherSession() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val id = UUID.randomUUID().toString(); val other = UUID.randomUUID().toString()
        val repo = FileWelcomeImageInputRepository(context); val second = FileWelcomeImageInputRepository(context)
        val file = File(context.filesDir, "welcome-image-inputs/$id.json"); val otherFile = File(file.parentFile, "$other.json")
        try {
            repo.open(id); repo.open(other)
            val uri = "content:" + "full image input".repeat(100000); val value = WelcomeImageDraft(WelcomeImageInput("receipt", true, uri), 99)
            repo.write(id, value); second.write(id, WelcomeImageDraft(revision = 1)); assertEquals(value, second.open(id))
            repo.write(other, WelcomeImageDraft(WelcomeImageInput("other", false, "content:other"), 5))
            withContext(Dispatchers.IO) { check(file.renameTo(File(file.path + ".bak"))) }
            assertEquals(value, second.open(id))
            withContext(Dispatchers.IO) { File(file.path + ".new").writeText("interrupted") }
            repo.release(id); assertFalse(file.exists()); assertFalse(File(file.path + ".bak").exists()); assertFalse(File(file.path + ".new").exists())
            withContext(Dispatchers.IO) { check(File(file.path + ".closed").renameTo(File(file.path + ".closed.bak"))) }
            try { second.open(id); fail() } catch (_: IllegalStateException) { }
            try { second.write(id, value.copy(revision = 100)); fail() } catch (_: IllegalStateException) { }
            assertEquals("content:other", second.open(other).input!!.uri)
        } finally {
            repo.release(other)
            withContext(Dispatchers.IO) { listOf(file, otherFile).forEach { base -> File(base.path + ".closed").delete(); File(base.path + ".closed.bak").delete() } }
        }
    }
}
