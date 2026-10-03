package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.*
import io.legado.app.model.Debug
import io.legado.app.utils.GSON
import java.io.File
import java.net.ServerSocket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BookSourceDebugRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var directory: File

    @Before
    fun before() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        directory = File(context.cacheDir, "book-debug-${UUID.randomUUID()}")
    }

    @After
    fun after() {
        database.close()
        directory.deleteRecursively()
    }

    private fun repo() = AppBookSourceDebugRepository(context, database, directory)

    @Test
    fun immutableRoomSnapshotPreservesKeywordAndJsExploreRulesWhenAnotherHostEditsRow() =
        runBlocking {
            val repository = repo()
            val key = "debug-${UUID.randomUUID()}"
            val original =
                BookSource(
                    bookSourceUrl = key,
                    bookSourceName = "Original",
                    exploreUrl =
                        "@js:'Category::https://example.invalid/category&&Other::https://example.invalid/other'",
                    ruleSearch = SearchRule(checkKeyWord = "Custom keyword"),
                )
            withContext(Dispatchers.IO) { database.bookSourceDao.insert(original) }
            val snapshot = repository.load(key)!!
            withContext(Dispatchers.IO) {
                database.bookSourceDao.insert(
                    original.copy(
                        bookSourceName = "Changed",
                        exploreUrl = "Changed::https://changed.invalid",
                    )
                )
            }
            assertEquals("Original", snapshot.name)
            assertEquals("Custom keyword", snapshot.keyword)
            val kinds = repository.sorts(snapshot, true)
            assertEquals(listOf("Category", "Other"), kinds.map { it.name })
            assertEquals("Category::https://example.invalid/category", kinds.first().query)
            withContext(Dispatchers.IO) {
                assertEquals("Changed", database.bookSourceDao.getBookSource(key)!!.bookSourceName)
            }
            assertNull(repository.load("missing"))
        }

    @Test
    fun globalBusyNeverReplacesAnotherOwnerAndReleasedLeaseCannotCancelSuccessor() = runBlocking {
        val repository = repo()
        val source = BookSource(bookSourceUrl = "key", bookSourceName = "Name")
        withContext(Dispatchers.IO) { database.bookSourceDao.insert(source) }
        val snapshot = repository.load("key")!!
        val other =
            object : Debug.Callback {
                override fun printLog(state: Int, msg: String) = Unit
            }
        assertTrue(Debug.startSimpleDebug(other, "other"))
        try {
            assertNull(repository.acquire(snapshot) {})
            assertSame(other, Debug.callback)
        } finally {
            Debug.cancelDebug(other)
        }
        val lease = repository.acquire(snapshot) {}!!
        lease.close()
        lease.awaitStopped()
        assertNull(Debug.callback)
        assertTrue(Debug.startSimpleDebug(other, "other"))
        try {
            lease.close()
            assertSame(other, Debug.callback)
        } finally {
            Debug.cancelDebug(other)
        }
    }

    @Test
    fun actualEmptySearchTerminatesFailureAndReleasesOnlyItsOwnEngineChannel() = runBlocking {
        val repository = repo()
        val source =
            BookSource(
                bookSourceUrl = "key",
                bookSourceName = "Name",
                customOrder = 7,
                lastUpdateTime = 99,
            )
        withContext(Dispatchers.IO) { database.bookSourceDao.insert(source) }
        val events = CopyOnWriteArrayList<BookSourceDebugEvent>()
        val lease = repository.acquire(repository.load("key")!!) { events += it }!!
        try {
            withTimeout(5000) { lease.run("我的") }
        } finally {
            lease.close()
            lease.awaitStopped()
        }
        assertTrue(events.any { it.state == -1 })
        assertNull(Debug.callback)
        withContext(Dispatchers.IO) {
            val row = database.bookSourceDao.getBookSource("key")!!
            assertEquals(7, row.customOrder)
            assertEquals(99L, row.lastUpdateTime)
            assertNull(row.searchUrl)
        }
    }

    @Test
    fun actualHttpFourStagePipelineAndDirectStageQueriesEmitCompleteHtmlWithoutPersistingSource() =
        runBlocking {
            val search = "<html><div class='book'><a href='/info'>Fixture book</a></div></html>"
            val info = "<html><h1>Fixture book</h1><a href='/toc'>Table of contents</a></html>"
            val toc =
                "<html><div class='chapter'><a href='/content'>Fixture chapter</a></div></html>"
            val content = "<html><div class='body'>Fixture body</div></html>"
            FixtureServer(
                    mapOf(
                        "/search" to search,
                        "/info" to info,
                        "/toc" to toc,
                        "/content" to content,
                    )
                )
                .use { server ->
                    val repository = repo()
                    val source =
                        BookSource(
                            bookSourceUrl = server.url + "/source",
                            bookSourceName = "Fixture",
                            searchUrl = server.url + "/search",
                            exploreUrl = "Fixture::" + server.url + "/search",
                            ruleSearch =
                                SearchRule(bookList = ".book", name = "a@text", bookUrl = "a@href"),
                            ruleExplore =
                                ExploreRule(
                                    bookList = ".book",
                                    name = "a@text",
                                    bookUrl = "a@href",
                                ),
                            ruleBookInfo = BookInfoRule(name = "h1@text", tocUrl = "a@href"),
                            ruleToc =
                                TocRule(
                                    chapterList = ".chapter",
                                    chapterName = "a@text",
                                    chapterUrl = "a@href",
                                ),
                            ruleContent = ContentRule(content = ".body@text"),
                        )
                    withContext(Dispatchers.IO) { database.bookSourceDao.insert(source) }
                    val snapshot = repository.load(source.bookSourceUrl)!!
                    val events = CopyOnWriteArrayList<BookSourceDebugEvent>()
                    for ((query, expected) in
                        listOf(
                            "我的" to setOf(10, 20, 30, 40),
                            server.url + "/info" to setOf(20, 30, 40),
                            "++" + server.url + "/toc" to setOf(30, 40),
                            "--" + server.url + "/content" to setOf(40),
                            "Fixture::" + server.url + "/search" to setOf(10, 20, 30, 40),
                        )) {
                        events.clear()
                        val lease = repository.acquire(snapshot) { events += it }!!
                        try {
                            withTimeout(10000) { lease.run(query) }
                        } finally {
                            lease.close()
                            lease.awaitStopped()
                        }
                        assertTrue("Terminal for $query: $events", events.any { it.state == 1000 })
                        assertTrue(
                            "Four html stages for $query: $events",
                            events
                                .filter { it.state in setOf(10, 20, 30, 40) }
                                .map { it.state }
                                .containsAll(expected),
                        )
                        assertTrue(events.any { it.state == 40 && it.text.contains(content) })
                        assertNull(Debug.callback)
                    }
                    withContext(Dispatchers.IO) {
                        assertEquals(
                            GSON.toJson(source),
                            GSON.toJson(database.bookSourceDao.getBookSource(source.bookSourceUrl)),
                        )
                    }
                }
        }

    @Test
    fun diskRestoresAllFourMillionCharacterHtmlBuffersAndQueryAndBoundsOnlyDisplayedLog() =
        runBlocking {
            val repository = repo()
            val session = UUID.randomUUID().toString()
            val html = "html".repeat(250000)
            val record =
                BookSourceDebugRecord(
                    "key",
                    query = "input",
                    help = false,
                    output = "log".repeat(10000),
                    searchHtml = html,
                    bookHtml = "book",
                    tocHtml = "toc",
                    contentHtml = html,
                    running = true,
                    revision = 4,
                )
            repository.write(session, record)
            repo().write(session, record.copy(revision = 3, searchHtml = "old"))
            val restored = repo().read(session)!!
            assertEquals(html, restored.searchHtml)
            assertEquals("book", restored.bookHtml)
            assertEquals("toc", restored.tocHtml)
            assertEquals(html, restored.contentHtml)
            assertEquals("input", restored.query)
            assertEquals(20000, restored.output.length)
            assertTrue(restored.running)
            try {
                repository.read("../outside")
                fail("invalid session")
            } catch (_: IllegalArgumentException) {}
        }

    @Test
    fun realCloseDeletesPrivateHtmlAndAtomicBackupsAndFencesLateWritesAcrossRepositoryInstances() =
        runBlocking {
            val session = UUID.randomUUID().toString()
            val record =
                BookSourceDebugRecord("key", searchHtml = "private".repeat(150000), revision = 1)
            repo().write(session, record)
            File(directory, "$session.json.bak").writeText("private backup")
            File(directory, "$session.json.new").writeText("private pending write")
            repo().release(session)
            assertNull(repo().read(session))
            assertTrue(File(directory, "$session.closed").exists())
            listOf(".json", ".json.bak", ".json.new").forEach {
                assertFalse(File(directory, session + it).exists())
            }
            try {
                repo().write(session, record.copy(revision = 2))
                fail("Late writer recreated closed session")
            } catch (_: IllegalStateException) {}
            val marker = File(directory, "$session.closed")
            assertTrue(marker.renameTo(File(marker.path + ".bak")))
            try {
                repo().write(session, record.copy(revision = 3))
                fail("Backup fence allowed resurrection")
            } catch (_: IllegalStateException) {}
            repo().release(session)
            assertNull(repo().read(session))
        }

    @Test
    fun releaseBeforeInitializationAndConcurrentLargeFlushCannotRecreateClosedSession() =
        runBlocking {
            val session = UUID.randomUUID().toString()
            val record =
                BookSourceDebugRecord("key", contentHtml = "private".repeat(200000), revision = 1)
            val flush = async(Dispatchers.IO) { runCatching { repo().write(session, record) } }
            repo().release(session)
            flush.await()
            assertNull(repo().read(session))
            assertFalse(File(directory, "$session.json").exists())
            val unopened = UUID.randomUUID().toString()
            repo().release(unopened)
            try {
                repo().write(unopened, record)
                fail("Late initialization recreated closed session")
            } catch (_: IllegalStateException) {}
            assertNull(repo().read(unopened))
            assertFalse(File(directory, "$unopened.json").exists())
        }

    private class FixtureServer(private val bodies: Map<String, String>) : AutoCloseable {
        private val server = ServerSocket(0)
        val url = "http://127.0.0.1:${server.localPort}"
        private val worker = Thread {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader()
                    val path =
                        input
                            .readLine()
                            .orEmpty()
                            .split(" ")
                            .getOrNull(1)
                            ?.substringBefore('?')
                            .orEmpty()
                    while (!input.readLine().isNullOrEmpty()) {}
                    val body = bodies[path].orEmpty().toByteArray(Charsets.UTF_8)
                    val header =
                        "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(header.toByteArray(Charsets.UTF_8))
                        write(body)
                        flush()
                    }
                }
            } catch (failure: SocketException) {
                if (!server.isClosed) throw failure
            }
        }
            .apply {
                isDaemon = true
                start()
            }

        override fun close() {
            server.close()
            worker.join(5000)
            check(!worker.isAlive)
        }
    }
}
