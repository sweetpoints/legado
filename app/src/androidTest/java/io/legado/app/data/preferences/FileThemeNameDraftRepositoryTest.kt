package io.legado.app.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class FileThemeNameDraftRepositoryTest {
    @Test
    fun actualAtomicDraftRestoresLongNameRejectsOldRevisionAndReleaseFencesOtherOwners() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val id = UUID.randomUUID().toString()
            val other = UUID.randomUUID().toString()
            val file = File(context.filesDir, "theme-settings-drafts/$id.json")
            val secondFile = File(file.parentFile, "$other.json")
            val repository = FileThemeNameDraftRepository(context)
            val second = FileThemeNameDraftRepository(context)
            try {
                assertEquals(ThemeNameDraft(), repository.open(id))
                repository.open(other)
                val text = "full saved name".repeat(100000)
                repository.write(id, ThemeNameDraft(text, 9))
                second.write(id, ThemeNameDraft("stale", 1))
                assertEquals(text, second.open(id).value)
                repository.write(other, ThemeNameDraft("other owner", 2))
                withContext(Dispatchers.IO) { check(file.renameTo(File(file.path + ".bak"))) }
                assertEquals(ThemeNameDraft(text, 9), second.open(id))
                withContext(Dispatchers.IO) { File(file.path + ".new").writeText("interrupted") }
                repository.release(id)
                assertFalse(file.exists())
                assertFalse(File(file.path + ".bak").exists())
                assertFalse(File(file.path + ".new").exists())
                try {
                    second.write(id, ThemeNameDraft("late", 99))
                    fail()
                } catch (_: IllegalStateException) {}
                try {
                    second.open(id)
                    fail()
                } catch (_: IllegalStateException) {}
                withContext(Dispatchers.IO) {
                    check(File(file.path + ".closed").renameTo(File(file.path + ".closed.bak")))
                }
                try {
                    second.open(id)
                    fail()
                } catch (_: IllegalStateException) {}
                repository.release(id)
                assertEquals("other owner", second.open(other).value)
            } finally {
                repository.release(other)
                withContext(Dispatchers.IO) {
                    File(file.path + ".closed").delete()
                    File(file.path + ".closed.bak").delete()
                    File(secondFile.path + ".closed").delete()
                }
            }
        }
}
