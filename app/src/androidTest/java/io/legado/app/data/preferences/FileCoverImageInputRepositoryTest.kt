package io.legado.app.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class FileCoverImageInputRepositoryTest {
    @Test
    fun actualLongInputRestoresRecordNightRejectsStaleWritesAndClosedBackupFencesLateOwnersWithoutAffectingOtherSession() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val id = UUID.randomUUID().toString()
            val other = UUID.randomUUID().toString()
            val repo = FileCoverImageInputRepository(context)
            val second = FileCoverImageInputRepository(context)
            val file = File(context.filesDir, "cover-image-inputs/$id.json")
            val otherFile = File(file.parentFile, "$other.json")
            try {
                repo.open(id)
                repo.open(other)
                val uri = "content:" + "full image input".repeat(100000)
                val value =
                    CoverImageDraft(
                        CoverImageInput(
                            "receipt",
                            io.legado.app.model.cover.CoverSettingImage.RecordNight,
                            uri,
                        ),
                        99,
                    )
                repo.write(id, value)
                second.write(id, CoverImageDraft(revision = 1))
                assertEquals(value, second.open(id))
                repo.write(
                    other,
                    CoverImageDraft(
                        CoverImageInput(
                            "other",
                            io.legado.app.model.cover.CoverSettingImage.Day,
                            "content:other",
                        ),
                        5,
                    ),
                )
                withContext(Dispatchers.IO) { check(file.renameTo(File(file.path + ".bak"))) }
                assertEquals(value, second.open(id))
                withContext(Dispatchers.IO) { File(file.path + ".new").writeText("interrupted") }
                repo.release(id)
                assertFalse(file.exists())
                assertFalse(File(file.path + ".bak").exists())
                assertFalse(File(file.path + ".new").exists())
                withContext(Dispatchers.IO) {
                    check(File(file.path + ".closed").renameTo(File(file.path + ".closed.bak")))
                }
                try {
                    second.open(id)
                    fail()
                } catch (_: IllegalStateException) {}
                try {
                    second.write(id, value.copy(revision = 100))
                    fail()
                } catch (_: IllegalStateException) {}
                assertEquals("content:other", second.open(other).input!!.uri)
            } finally {
                repo.release(other)
                withContext(Dispatchers.IO) {
                    listOf(file, otherFile).forEach { base ->
                        File(base.path + ".closed").delete()
                        File(base.path + ".closed.bak").delete()
                    }
                }
            }
        }
}
