package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class RssFavoriteConfigRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ids = mutableListOf<String>()
    @After fun cleanup() { ids.forEach { File(context.filesDir, "rss-favorite-config/$it.json").delete() } }
    private fun stage(title: String?, group: String?) = FileRssFavoriteConfigRepository.stage(context, title, group).also { ids += it }
    @Test fun stagingWaitsForDiskAndPreservesNullableInputsWithoutRoomWrites() = runBlocking {
        val id = stage(null, " "); val loaded = FileRssFavoriteConfigRepository(context).load(id)
        assertNull(loaded.originalTitle); assertEquals(" ", loaded.originalGroup); assertEquals("", loaded.title); assertEquals(" ", loaded.group)
        assertEquals(loaded, FileRssFavoriteConfigRepository(context).load(id))
    }
    @Test fun largeDraftAndPendingCallbackRestoreFromDiskAndOlderRevisionCannotOverwriteThem() = runBlocking {
        val id = stage("Original", "Group"); val repository = FileRssFavoriteConfigRepository(context)
        val initial = repository.load(id); val large = initial.copy(title = "large ".repeat(100000), revision = 5, action = RssFavoriteConfigAction.Save)
        repository.write(id, large); repository.write(id, initial.copy(title = "Old", revision = 4))
        assertEquals(large, FileRssFavoriteConfigRepository(context).load(id)); assertTrue(id.length < 100)
    }
    @Test fun concurrentFlushesKeepLatestRevisionAndDeleteActionAcrossInstances() = runBlocking {
        val id = stage("Title", "Group"); val repository = FileRssFavoriteConfigRepository(context); val initial = repository.load(id)
        coroutineScope { (1..20).map { revision -> async { repository.write(id, initial.copy(title = "Title $revision", revision = revision.toLong(), action = if (revision == 20) RssFavoriteConfigAction.Delete else null)) } }.awaitAll() }
        val actual = FileRssFavoriteConfigRepository(context).load(id)
        assertEquals(20L, actual.revision); assertEquals("Title 20", actual.title); assertEquals(RssFavoriteConfigAction.Delete, actual.action)
    }
}
