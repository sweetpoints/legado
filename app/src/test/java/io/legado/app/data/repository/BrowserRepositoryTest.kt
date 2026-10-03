package io.legado.app.data.repository

import io.legado.app.model.browser.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserRepositoryTest {
    private fun request() =
        BrowserRequest(
            "https://request.invalid",
            "Title",
            "Source",
            "origin",
            1,
            "html",
            true,
            false,
            "verification",
        )

    private fun page() =
        BrowserPage(
            request(),
            "https://resolved.invalid",
            "body",
            true,
            mapOf("User-Agent" to "custom", "Cookie" to "cookie", "X-Custom" to "header"),
            "default",
            BrowserSource(1, "all metadata"),
        )

    @Test
    fun allPreparationImagesSourceAndCookieOperationsRunOffCallerAndPreserveFullArguments() =
        runBlocking {
            val caller = Thread.currentThread()
            val data = Data()
            val sessions = Sessions()
            val repo = DefaultBrowserRepository(data, sessions)
            val prepared = repo.prepare(request())
            assertEquals(page(), prepared)
            assertEquals(BrowserVerification("body", prepared.baseUrl), repo.refetch(prepared))
            assertEquals(
                BrowserVerification("captured", "navigated"),
                repo.captured("captured", "navigated"),
            )
            repo.imageDirectory("folder")
            assertEquals("folder", repo.imageDirectory())
            repo.saveImage("data:image/base64", "folder")
            assertEquals(BrowserWebCookies("base", listOf("a=1", "b=2")), repo.webCookies("url"))
            repo.forgetImageDirectory("folder")
            repo.disableSource("source", 1)
            repo.deleteSource("source", 0)
            repo.cookie("url", "cookie")
            assertEquals(
                listOf(
                    "save:data:image/base64:folder",
                    "forget:folder",
                    "disable:source:1",
                    "delete:source:0",
                    "cookie:url:cookie",
                ),
                data.actions,
            )
            assertTrue(data.threads.isNotEmpty())
            assertTrue(data.threads.all { it !== caller })
        }

    @Test
    fun initializationIsSeparateFromUpdatesAndLatestRevisionAndOwnedReleaseAreDelegatedOnIo() =
        runBlocking {
            val caller = Thread.currentThread()
            val sessions = Sessions()
            val repo = DefaultBrowserRepository(Data(), sessions)
            val snapshot = BrowserSession(request(), page(), revision = 20)
            val firstOwner = UUID.randomUUID().toString()
            val otherOwner = UUID.randomUUID().toString()
            repo.claim("owner", firstOwner)
            assertNull(repo.read("owner", firstOwner))
            assertEquals(snapshot, repo.create("owner", firstOwner, snapshot))
            assertFalse(repo.write("owner", firstOwner, snapshot.copy(revision = 19, page = null)))
            assertEquals(snapshot, repo.read("owner", firstOwner))
            assertTrue(repo.write("owner", firstOwner, snapshot.copy(revision = 21)))
            assertEquals(21L, repo.read("owner", firstOwner)!!.revision)
            repo.claim("other", otherOwner)
            repo.create("other", otherOwner, snapshot)
            repo.release("owner", firstOwner)
            assertEquals(snapshot, repo.read("other", otherOwner))
            try {
                repo.write("owner", firstOwner, snapshot.copy(revision = 100))
                fail("Closed session")
            } catch (_: BrowserSessionClosedException) {}
            assertTrue(sessions.threads.all { it !== caller })
        }

    @Test
    fun cancellationAfterNonCooperativePreparationNeverReturnsAReadyPage() = runTest {
        val gate = CompletableDeferred<Unit>()
        var published = false
        val data = Data().apply { prepareGate = gate }
        val repo = DefaultBrowserRepository(data, Sessions(), StandardTestDispatcher(testScheduler))
        val task = launch {
            repo.prepare(request())
            published = true
        }
        runCurrent()
        task.cancel()
        gate.complete(Unit)
        task.join()
        assertFalse(published)
    }

    @Test
    fun atomicWritesAndOwnedCleanupFinishTheirIoEvenIfCallingOwnerIsCancelled() = runTest {
        val sessions = Sessions()
        val repo = DefaultBrowserRepository(Data(), sessions, StandardTestDispatcher(testScheduler))
        val snapshot = BrowserSession(request(), revision = 1)
        val owner = UUID.randomUUID().toString()
        repo.claim("owner", owner)
        repo.create("owner", owner, snapshot)
        sessions.gate = CompletableDeferred()
        val writer = launch { repo.write("owner", owner, snapshot.copy(revision = 2)) }
        runCurrent()
        writer.cancel()
        sessions.gate!!.complete(Unit)
        writer.join()
        assertEquals(2L, sessions.values["owner"]!!.revision)
        sessions.gate = CompletableDeferred()
        val cleanup = launch { repo.release("owner", owner) }
        runCurrent()
        cleanup.cancel()
        sessions.gate!!.complete(Unit)
        cleanup.join()
        assertTrue("owner" in sessions.closed)
        assertNull(sessions.values["owner"])
    }

    private class Data : BrowserDataStore {
        val threads = CopyOnWriteArrayList<Thread>()
        val actions = mutableListOf<String>()
        var directory: String? = null
        var prepareGate: CompletableDeferred<Unit>? = null

        private fun touch() {
            threads += Thread.currentThread()
        }

        override suspend fun prepare(request: BrowserRequest): BrowserPage {
            touch()
            prepareGate?.let { withContext(NonCancellable) { it.await() } }
            return BrowserPage(
                request,
                "https://resolved.invalid",
                "body",
                true,
                mapOf("User-Agent" to "custom", "Cookie" to "cookie", "X-Custom" to "header"),
                "default",
                BrowserSource(1, "all metadata"),
            )
        }

        override suspend fun refetch(page: BrowserPage): BrowserVerification {
            touch()
            return BrowserVerification(page.html.orEmpty(), page.baseUrl)
        }

        override suspend fun captured(htmlJson: String, url: String): BrowserVerification {
            touch()
            return BrowserVerification(htmlJson, url)
        }

        override suspend fun saveImage(data: String, directory: String) {
            touch()
            actions += "save:$data:$directory"
        }

        override suspend fun imageDirectory(): String? {
            touch()
            return directory
        }

        override suspend fun imageDirectory(value: String) {
            touch()
            directory = value
        }

        override suspend fun forgetImageDirectory(expected: String) {
            touch()
            actions += "forget:$expected"
            if (directory == expected) directory = null
        }

        override suspend fun disableSource(origin: String, type: Int) {
            touch()
            actions += "disable:$origin:$type"
        }

        override suspend fun deleteSource(origin: String, type: Int) {
            touch()
            actions += "delete:$origin:$type"
        }

        override suspend fun webCookies(url: String): BrowserWebCookies {
            touch()
            return BrowserWebCookies("base", listOf("a=1", "b=2"))
        }

        override suspend fun cookie(url: String, value: String?) {
            touch()
            actions += "cookie:$url:$value"
        }
    }

    private class Sessions : BrowserSessionStore {
        val values = mutableMapOf<String, BrowserSession>()
        val owners = mutableMapOf<String, String>()
        val closed = mutableSetOf<String>()
        val threads = CopyOnWriteArrayList<Thread>()
        var gate: CompletableDeferred<Unit>? = null

        private fun touch(session: String) {
            threads += Thread.currentThread()
            if (session in closed) throw BrowserSessionClosedException()
        }

        override suspend fun claim(session: String, owner: String) {
            touch(session)
            owners[session] = owner
        }

        override suspend fun read(session: String, owner: String): BrowserSession? {
            touch(session)
            if (owners[session] != owner) throw BrowserSessionClosedException()
            return values[session]
        }

        override suspend fun create(
            session: String,
            owner: String,
            seed: BrowserSession,
        ): BrowserSession {
            touch(session)
            if (owners[session] != owner) throw BrowserSessionClosedException()
            return values.getOrPut(session) { seed }
        }

        override suspend fun write(
            session: String,
            owner: String,
            snapshot: BrowserSession,
        ): Boolean {
            touch(session)
            gate?.await()
            if (owners[session] != owner) return false
            val previous = values[session] ?: error("Missing owner")
            if (snapshot.revision > previous.revision) {
                values[session] = snapshot
                return true
            }
            return snapshot.revision == previous.revision && snapshot == previous
        }

        override suspend fun release(session: String, owner: String) {
            threads += Thread.currentThread()
            gate?.await()
            if (owners[session] != owner) return
            closed += session
            values.remove(session)
        }
    }
}
