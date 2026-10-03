package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.RssSource
import io.legado.app.model.Debug
import java.io.File
import java.net.ServerSocket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class RssSourceDebugRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var directory: File

    @Before
    fun before() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        directory = File(context.cacheDir, "rss-debug-${UUID.randomUUID()}")
    }

    @After
    fun after() {
        database.close()
        directory.deleteRecursively()
    }

    private fun repo() = AppRssSourceDebugRepository(context, database, directory)

    @Test
    fun immutableRoomSnapshotKeepsOriginalRulesAndJsCategoryResolutionWhenAnotherHostEditsRow() =
        runBlocking {
            val repository = repo()
            val key = "rss-debug-${UUID.randomUUID()}"
            val original =
                RssSource(
                    key,
                    "Original",
                    sortUrl =
                        "@js:'Category::https://example.invalid/category&&Other::https://example.invalid/other'",
                    ruleContent = ".body",
                )
            withContext(Dispatchers.IO) { database.rssSourceDao.insert(original) }
            val snapshot = repository.load(key)!!
            withContext(Dispatchers.IO) {
                database.rssSourceDao.insert(
                    original.copy(
                        sourceName = "Changed",
                        sortUrl = "Changed::https://changed.invalid",
                    )
                )
            }
            assertEquals("Original", snapshot.name)
            val sorts = repository.sorts(snapshot)
            assertEquals(listOf("Category", "Other"), sorts.map { it.name })
            assertEquals("https://example.invalid/category", sorts.first().url)
            withContext(Dispatchers.IO) {
                assertEquals("Changed", database.rssSourceDao.getByKey(key)!!.sourceName)
            }
            assertNull(repository.load("missing"))
        }

    @Test
    fun globalBusyCannotReplaceAnotherOwnerAndReleasedLeaseCannotCancelSuccessor() = runBlocking {
        val repository = repo()
        withContext(Dispatchers.IO) { database.rssSourceDao.insert(RssSource("key", "Name")) }
        val source = repository.load("key")!!
        val other =
            object : Debug.Callback {
                override fun printLog(state: Int, msg: String) = Unit
            }
        assertTrue(Debug.startSimpleDebug(other, "other"))
        try {
            assertNull(repository.acquire(source) {})
            assertSame(other, Debug.callback)
        } finally {
            Debug.cancelDebug(other)
        }
        val lease = repository.acquire(source) {}!!
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
    fun realDebugEngineEmitsSearchFailureAndNoContentSuccessTerminalWithoutChangingRoomSource() =
        runBlocking {
            val repository = repo()
            val row =
                RssSource(
                    "key",
                    "Name",
                    ruleArticles = ".entry",
                    customOrder = 7,
                    lastUpdateTime = 99,
                )
            withContext(Dispatchers.IO) { database.rssSourceDao.insert(row) }
            val source = repository.load("key")!!
            val events = CopyOnWriteArrayList<RssSourceDebugEvent>()
            val missing = repository.acquire(source) { events += it }!!
            try {
                withTimeout(5000) { missing.run("我的") }
            } finally {
                missing.close()
                missing.awaitStopped()
            }
            assertTrue(events.any { it.state == -1 && it.text.contains("搜索URL为空") })
            assertNull(Debug.callback)
            events.clear()
            val content = repository.acquire(source) { events += it }!!
            try {
                withTimeout(5000) { content.run("https://example.invalid/content") }
            } finally {
                content.close()
                content.awaitStopped()
            }
            assertTrue(events.any { it.state == 1000 && it.text.contains("内容规则为空") })
            assertNull(Debug.callback)
            withContext(Dispatchers.IO) {
                val persisted = database.rssSourceDao.getByKey("key")!!
                assertEquals(7, persisted.customOrder)
                assertEquals(99L, persisted.lastUpdateTime)
                assertNull(persisted.searchUrl)
            }
        }

    @Test
    fun realHttpParserFixtureProducesSeparateListAndContentHtmlAndCompletesScopedLease() =
        runBlocking {
            val list =
                "<html><div class='entry'><a href='/content'>Fixture article</a></div></html>"
            val content = "<html><div class='body'>Fixture body</div></html>"
            FixtureServer(mapOf("/list" to list, "/content" to content)).use { server ->
                val repository = repo()
                val row =
                    RssSource(
                        server.url + "/source",
                        "Fixture",
                        searchUrl = server.url + "/list",
                        ruleArticles = ".entry",
                        ruleTitle = "a@text",
                        ruleLink = "a@href",
                        ruleContent = ".body@html",
                    )
                withContext(Dispatchers.IO) { database.rssSourceDao.insert(row) }
                val events = CopyOnWriteArrayList<RssSourceDebugEvent>()
                val lease = repository.acquire(repository.load(row.sourceUrl)!!) { events += it }!!
                try {
                    withTimeout(10000) { lease.run("我的") }
                } finally {
                    lease.close()
                    lease.awaitStopped()
                }
                assertTrue(events.any { it.state == 10 && it.text.contains(list) })
                assertTrue(events.any { it.state == 20 && it.text.contains(content) })
                assertTrue(events.any { it.state == 1000 })
                assertTrue(
                    events.any {
                        it.state != 10 && it.state != 20 && it.text.contains("Fixture body")
                    }
                )
                assertNull(Debug.callback)
            }
        }

    @Test
    fun diskRestoresMillionCharacterHtmlAndQueryWhileBoundingOnlyDisplayedLogAndRejectingOldWrites() =
        runBlocking {
            val repository = repo()
            val session = UUID.randomUUID().toString()
            val html = "body".repeat(250000)
            val record =
                RssSourceDebugRecord(
                    "key",
                    query = "query",
                    help = false,
                    output = "line".repeat(10000),
                    listHtml = html,
                    contentHtml = "content",
                    running = true,
                    revision = 4,
                )
            repository.write(session, record)
            repository.write(session, record.copy(revision = 3, listHtml = "old"))
            val restored = repo().read(session)!!
            assertEquals(html, restored.listHtml)
            assertEquals("content", restored.contentHtml)
            assertEquals("query", restored.query)
            assertEquals(20000, restored.output.length)
            assertTrue(restored.running)
            assertEquals(4L, restored.revision)
            try {
                repository.read("../outside")
                fail("invalid session")
            } catch (_: IllegalArgumentException) {}
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
