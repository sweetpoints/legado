package io.legado.app.data.repository

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExploreResultsSessionRepositoryTest {
    private lateinit var directory: File

    @Before
    fun createDirectory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = File(context.cacheDir, "explore-session-${UUID.randomUUID()}")
    }

    @After
    fun cleanDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun preparedUuidRestoresExactLargeScriptAndCheckpointWithoutBundlePayload() = runBlocking {
        val url = "@js:" + "script content ".repeat(160_000)
        val title = " category title "
        val repository = AppExploreResultsSessionRepository(directory)
        val ticket = repository.prepare("source://exact", title, url)
        assertEquals(UUID.fromString(ticket).toString(), ticket)
        val restoredRepository = AppExploreResultsSessionRepository(directory)
        val initial = restoredRepository.read(ticket)!!
        assertEquals(ExploreResultsRequest("source://exact", title, url), initial.request)
        assertEquals(url, initial.selectedCategory.url)
        val updated = initial.copy(revision = 1, nextPage = 8, displayedPage = 7, scrollIndex = 20)
        assertTrue(restoredRepository.write(ticket, updated))
        assertEquals(updated, repository.read(ticket))
        assertFalse(repository.write(ticket, initial.copy(revision = 1, nextPage = 99)))
        assertEquals(updated, repository.read(ticket))
        assertFalse(
            repository.write(
                ticket,
                updated.copy(
                    revision = 2,
                    request = initial.request.copy(sourceUrl = "wrong-owner"),
                ),
            )
        )
    }

    @Test
    fun releasedSessionFencesLateWritesAndRemovesAllAtomicSidecarsOnlyForOwner() = runBlocking {
        val repository = AppExploreResultsSessionRepository(directory)
        val owned = repository.prepare("source", "Own", "url")
        val neighbor = repository.prepare("other-source", "Neighbor", "other-url")
        val initial = repository.read(owned)!!
        File(directory, "$owned.json.bak").writeText("Backup")
        File(directory, "$owned.json.new").writeText("New")
        repository.release(owned)
        assertNull(repository.read(owned))
        assertFalse(repository.write(owned, initial.copy(revision = 100)))
        listOf("", ".bak", ".new").forEach { suffix ->
            assertFalse(File(directory, "$owned.json$suffix").exists())
        }
        assertEquals("Neighbor", repository.read(neighbor)!!.request.title)
    }

    @Test
    fun canceledPrepareReturnReleasesOnlyUndeliveredUuidAndKeepsNeighbor() = runBlocking {
        val entered = CompletableDeferred<String>()
        val finishPrepare = CompletableDeferred<Unit>()
        val repository =
            AppExploreResultsSessionRepository(directory) { ticket ->
                assertTrue(Looper.myLooper() != Looper.getMainLooper())
                entered.complete(ticket)
                withContext(NonCancellable) { finishPrepare.await() }
            }
        val neighborRepository = AppExploreResultsSessionRepository(directory)
        val neighbor = neighborRepository.prepare("neighbor", "Keep", "url")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var delivered = false
        try {
            val preparing = scope.launch {
                repository.prepare("source", "title", "large-url")
                delivered = true
            }
            val owned = entered.await()
            preparing.cancel()
            finishPrepare.complete(Unit)
            preparing.cancelAndJoin()
            assertFalse(delivered)
            assertNull(neighborRepository.read(owned))
            assertFalse(File(directory, "$owned.json").exists())
            assertEquals("Keep", neighborRepository.read(neighbor)!!.request.title)
        } finally {
            scope.cancel()
        }
    }
}
