package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BookDetailChildRepositoryTest {
    private lateinit var context: Context
    private lateinit var directory: File

    @Before
    fun before() {
        context = ApplicationProvider.getApplicationContext()
        directory = File(context.cacheDir, "book-detail-children-test-${UUID.randomUUID()}")
    }

    @After
    fun after() {
        directory.deleteRecursively()
    }

    private fun repo(beforeWrite: (BookDetailChildren) -> Unit = {}) =
        FileBookDetailChildRepository(context, directory, beforeWrite)

    private fun ticket() = UUID.randomUUID().toString()

    private fun owner() =
        BookDetailChildOwner(UUID.randomUUID().toString(), BookDetailChildKind.Toc, "book")

    @Test
    fun largeResultWrittenBeforeVmInitializationRestoresAcrossRepositoriesAndCompletesExactlyOnce() =
        runBlocking {
            val id = ticket()
            val owner = owner()
            val result =
                BookDetailChildResult(
                    owner,
                    highlightAnchor = "anchor".repeat(200_000),
                    position = BookDetailPosition(10, 5, 2, 3),
                )
            repo().owner(id, owner)
            repo().result(id, result)
            assertEquals(result, repo().read(id).pending.single())
            repo().complete(id, owner.token)
            repo().result(id, result)
            assertTrue(repo().read(id).pending.isEmpty())
            assertEquals(listOf(owner.token), repo().read(id).completed)
        }

    @Test
    fun nativeResultWriteFailureRetainsOwnerAndAllowsExplicitRetryWithoutInventingAResult() =
        runBlocking {
            val id = ticket()
            val owner = owner()
            val result = BookDetailChildResult(owner, canceled = true)
            repo().owner(id, owner)
            assertTrue(
                runCatching {
                    repo { if (it.pending.isNotEmpty()) error("disk unavailable") }
                        .result(id, result)
                }
                    .isFailure
            )
            assertEquals(owner, repo().read(id).owners.single())
            assertTrue(repo().read(id).pending.isEmpty())
            repo().result(id, result)
            assertTrue(repo().read(id).pending.single().canceled)
        }

    @Test
    fun closeAcrossRepoInstancesSerializesWithNonCancellableWriterAndLateResultsCannotRecreateFiles() =
        runBlocking {
            val id = ticket()
            val owner = owner()
            repo().owner(id, owner)
            val entered = CountDownLatch(1)
            val gate = CountDownLatch(1)
            val writer =
                async(Dispatchers.Default) {
                    repo {
                            if (it.pending.isNotEmpty()) {
                                entered.countDown()
                                check(gate.await(10, TimeUnit.SECONDS))
                            }
                        }
                        .result(id, BookDetailChildResult(owner))
                }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
                val close = async(Dispatchers.Default) { repo().release(id) }
                gate.countDown()
                writer.await()
                close.await()
                assertTrue(repo().read(id).pending.isEmpty())
                assertTrue(
                    runCatching { repo().result(id, BookDetailChildResult(owner)) }.isFailure
                )
                listOf("", ".bak", ".new").forEach {
                    assertFalse(File(directory, "$id.json$it").exists())
                }
            } finally {
                gate.countDown()
                writer.cancelAndJoin()
            }
        }

    @Test
    fun backupCloseMarkerAlsoFencesOwnerAndResultWrites() = runBlocking {
        val id = ticket()
        val owner = owner()
        repo().owner(id, owner)
        repo().release(id)
        assertTrue(File(directory, "$id.closed").renameTo(File(directory, "$id.closed.bak")))
        assertTrue(runCatching { repo().owner(id, owner.copy(token = "new")) }.isFailure)
        assertTrue(runCatching { repo().result(id, BookDetailChildResult(owner)) }.isFailure)
        assertTrue(repo().read(id).owners.isEmpty())
    }

    @Test
    fun cancellationDuringDurableResultWritePreservesReceiptForRecreatedOwner() = runBlocking {
        val id = ticket()
        val owner = owner()
        repo().owner(id, owner)
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val job =
            launch(Dispatchers.Default) {
                repo {
                        if (it.pending.isNotEmpty()) {
                            entered.countDown()
                            check(gate.await(10, TimeUnit.SECONDS))
                        }
                    }
                    .result(id, BookDetailChildResult(owner, value = "returned"))
            }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            job.cancel()
            gate.countDown()
            job.join()
            assertEquals("returned", repo().read(id).pending.single().value)
        } finally {
            gate.countDown()
            job.cancelAndJoin()
        }
    }
}
