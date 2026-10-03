package io.legado.app.data.repository

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class RssSourceEditorRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var directory: File
    private val invalidated = mutableListOf<Pair<String?, String>>()

    @Before
    fun before() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        directory = File(context.cacheDir, "rss-editor-${UUID.randomUUID()}")
    }

    @After
    fun after() {
        database.close()
        directory.deleteRecursively()
    }

    private fun repo() =
        RoomRssSourceEditorRepository(
            context,
            database,
            directory,
            now = { 100L },
            invalidate = { old, source ->
                assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                invalidated += old?.sourceUrl to source.sourceUrl
            },
        )

    @Test
    fun renameUpdatesRelatedOriginsInTransactionAndPreservesFreshOrderAndEveryEditedField() =
        runBlocking {
            val repository = repo()
            val session = UUID.randomUUID().toString()
            withContext(Dispatchers.IO) {
                database.rssSourceDao.insert(
                    RssSource("old", "name", customOrder = 3, lastUpdateTime = 9)
                )
                database.rssStarDao.insert(
                    RssStar(origin = "old", link = "star", content = "saved content")
                )
                database.rssArticleDao.insert(
                    RssArticle(
                        origin = "old",
                        link = "article",
                        sort = "sort",
                        read = true,
                        content = "article content",
                    )
                )
            }
            val initial = repository.load("old")!!
            withContext(Dispatchers.IO) {
                database.rssSourceDao.insert(
                    RssSource("old", "name", customOrder = 27, lastUpdateTime = 99)
                )
            }
            val draft =
                initial.draft.copy(
                    fields =
                        RssSourceEditorField.entries.associateWith {
                            RssSourceEditorText(
                                if (it == RssSourceEditorField.SourceUrl) "new" else " ${it.key} ",
                                2,
                                5,
                            )
                        },
                    enabled = false,
                    singleUrl = true,
                    cookieJar = false,
                    preload = true,
                    enableJs = false,
                    loadWithBaseUrl = false,
                    showWebLog = true,
                    cacheFirst = true,
                    type = 2,
                    articleStyle = 4,
                )
            val result =
                repository.save(
                    session,
                    initial.copy(draft = draft),
                    RssSourceEditorSaveAction.Debug,
                    false,
                )
            withContext(Dispatchers.IO) {
                assertNull(database.rssSourceDao.getByKey("old"))
                val row = database.rssSourceDao.getByKey("new")!!
                assertEquals(27, row.customOrder)
                assertEquals(100L, row.lastUpdateTime)
                assertTrue(draft.sameContent(RssSourceEditorDraft.from(row)))
                assertNull(database.rssStarDao.get("old", "star"))
                assertEquals("saved content", database.rssStarDao.get("new", "star")!!.content)
                assertNull(database.rssArticleDao.get("old", "article", "sort"))
                val article = database.rssArticleDao.get("new", "article", "sort")!!
                assertTrue(article.read)
                assertEquals("article content", article.content)
            }
            assertEquals(listOf("old" to "new"), invalidated)
            assertEquals("new", result.originalKey)
            assertEquals("new", result.delivery!!.sourceUrl)
            assertEquals(result, repo().readDraft(session))
            assertEquals(2, result.draft[RssSourceEditorField.Header].start)
        }

    @Test
    fun deletedExistingIsNeverRecreatedAndFailedSaveDoesNotCreateDelivery() = runBlocking {
        val repository = repo()
        val session = UUID.randomUUID().toString()
        withContext(Dispatchers.IO) { database.rssSourceDao.insert(RssSource("old", "name")) }
        val document = repository.load("old")!!
        withContext(Dispatchers.IO) { database.rssSourceDao.delete("old") }
        try {
            repository.save(session, document, RssSourceEditorSaveAction.Close, false)
            fail("deleted source must reject")
        } catch (_: IllegalStateException) {}
        withContext(Dispatchers.IO) { assertNull(database.rssSourceDao.getByKey("old")) }
        assertNull(repository.readDraft(session))
        assertTrue(invalidated.isEmpty())
    }

    @Test
    fun originConstraintFailureRollsBackDeleteAndAllRelatedUpdates() = runBlocking {
        val repository = repo()
        val session = UUID.randomUUID().toString()
        withContext(Dispatchers.IO) {
            database.rssSourceDao.insert(RssSource("old", "name"), RssSource("new", "destination"))
            database.rssStarDao.insert(
                RssStar(origin = "old", link = "same"),
                RssStar(origin = "new", link = "same"),
            )
        }
        val initial = repository.load("old")!!
        try {
            repository.save(
                session,
                initial.copy(
                    draft =
                        initial.draft.with(
                            RssSourceEditorField.SourceUrl,
                            RssSourceEditorText("new"),
                        )
                ),
                RssSourceEditorSaveAction.Close,
                false,
            )
            fail("collision must roll back")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {}
        withContext(Dispatchers.IO) {
            assertEquals("name", database.rssSourceDao.getByKey("old")!!.sourceName)
            assertEquals("destination", database.rssSourceDao.getByKey("new")!!.sourceName)
            assertNotNull(database.rssStarDao.get("old", "same"))
        }
        assertTrue(invalidated.isEmpty())
        assertNull(repository.readDraft(session))
    }

    @Test
    fun sameContentKeepsLatestTimestampWhileAnyRuleChangeUpdatesIt() = runBlocking {
        val repository = repo()
        val session = UUID.randomUUID().toString()
        withContext(Dispatchers.IO) {
            database.rssSourceDao.insert(
                RssSource("url", "name", lastUpdateTime = 35, customOrder = 9)
            )
        }
        val initial = repository.load("url")!!
        val unchanged = repository.save(session, initial, RssSourceEditorSaveAction.Variable, false)
        assertEquals(35L, unchanged.lastUpdateTime)
        assertEquals(9, unchanged.customOrder)
        val changed =
            repository.save(
                session,
                unchanged.copy(
                    draft =
                        unchanged.draft.with(
                            RssSourceEditorField.RuleImage,
                            RssSourceEditorText("img"),
                        )
                ),
                RssSourceEditorSaveAction.Login,
                false,
            )
        assertEquals(100L, changed.lastUpdateTime)
        assertFalse(changed.delivery!!.loginAvailable)
    }

    @Test
    fun objectAndSingleArrayPasteExportAllFieldsWithoutUsingForeignMetadata() = runBlocking {
        val repository = repo()
        val source =
            RssSource(
                "foreign",
                "Name",
                jsLib = "library",
                ruleImage = "image",
                contentWhitelist = "allow",
                contentBlacklist = "deny",
                shouldOverrideUrlLoading = "@js:url",
                nextContentUrl = ".next",
                startHtml = "html",
                startStyle = "css",
                preloadJs = "preload",
                customOrder = 999,
                lastUpdateTime = 999,
            )
        val pasted = repository.parse(GSON.toJson(source))!!
        assertEquals(pasted, repository.parse(GSON.toJson(listOf(source))))
        assertNull(repository.parse(GSON.toJson(listOf(source, source))))
        assertNull(repository.parse("bad"))
        val json =
            repository.export(
                RssSourceEditorDocument("original", pasted, lastUpdateTime = 8, customOrder = 3),
                true,
            )
        val exported = GSON.fromJsonObject<RssSource>(json).getOrThrow()
        assertEquals("foreign", exported.sourceUrl)
        assertEquals("library", exported.jsLib)
        assertEquals("allow", exported.contentWhitelist)
        assertEquals("deny", exported.contentBlacklist)
        assertEquals("@js:url", exported.shouldOverrideUrlLoading)
        assertEquals("html", exported.startHtml)
        assertEquals("css", exported.startStyle)
        assertEquals("preload", exported.preloadJs)
        assertEquals(".next@href", exported.nextContentUrl)
        assertEquals(3, exported.customOrder)
        assertEquals(8L, exported.lastUpdateTime)
    }

    @Test
    fun renameCommittedThenInvalidationFailureRestoresOriginalSaveReceiptWithoutRepeatingRoomMutation() =
        runBlocking {
            val session = UUID.randomUUID().toString()
            val repository = repo()
            withContext(Dispatchers.IO) {
                database.rssSourceDao.insert(RssSource("old", "Name", customOrder = 7))
                database.rssStarDao.insert(RssStar(origin = "old", link = "star"))
            }
            val document = repository.load("old")!!
            var failures = 1
            val failing =
                RoomRssSourceEditorRepository(
                    context,
                    database,
                    directory,
                    now = { 100L },
                    invalidate = { _, _ -> if (failures-- > 0) error("invalidate failed") },
                )
            val edited =
                document.copy(
                    draft =
                        document.draft.with(
                            RssSourceEditorField.SourceUrl,
                            RssSourceEditorText("renamed"),
                        )
                )
            try {
                failing.save(session, edited, RssSourceEditorSaveAction.Debug, false)
                fail("must fail invalidation")
            } catch (_: IllegalStateException) {}
            withContext(Dispatchers.IO) {
                assertNull(database.rssSourceDao.getByKey("old"))
                assertNotNull(database.rssSourceDao.getByKey("renamed"))
                assertNotNull(database.rssStarDao.get("renamed", "star"))
            }
            val restored = repo().readDraft(session)!!
            assertEquals("renamed", restored.originalKey)
            assertEquals(RssSourceEditorSaveAction.Debug, restored.delivery!!.action)
            assertEquals(7, restored.customOrder)
            assertEquals(restored.delivery!!.token, repo().readDraft(session)!!.delivery!!.token)
            repository.writeDraft(
                session,
                restored.copy(delivery = null, revision = restored.revision + 1),
            )
            assertNull(repo().readDraft(session)!!.delivery)
        }

    @Test
    fun renameCommittedThenFinalDraftFailureCanRecoverSameDeliveryAndOldKeyIsNotResurrected() =
        runBlocking {
            val session = UUID.randomUUID().toString()
            var rejectDraft = true
            withContext(Dispatchers.IO) { database.rssSourceDao.insert(RssSource("old", "Name")) }
            val document = repo().load("old")!!
            val failing =
                RoomRssSourceEditorRepository(
                    context,
                    database,
                    directory,
                    now = { 100L },
                    beforeDraftWrite = { if (rejectDraft) error("draft disk failed") },
                    invalidate = { _, _ -> },
                )
            val edited =
                document.copy(
                    draft =
                        document.draft.with(
                            RssSourceEditorField.SourceUrl,
                            RssSourceEditorText("new"),
                        )
                )
            try {
                failing.save(session, edited, RssSourceEditorSaveAction.Variable, false)
                fail("must fail final draft")
            } catch (_: IllegalStateException) {}
            rejectDraft = false
            val recovered = failing.save(session, edited, RssSourceEditorSaveAction.Close, false)
            assertEquals("new", recovered.originalKey)
            assertEquals(RssSourceEditorSaveAction.Variable, recovered.delivery!!.action)
            assertEquals(recovered, repo().readDraft(session))
            withContext(Dispatchers.IO) {
                assertNull(database.rssSourceDao.getByKey("old"))
                assertEquals("Name", database.rssSourceDao.getByKey("new")!!.sourceName)
            }
        }

    @Test
    fun journalWriteFailurePreventsAnyDatabaseMutationOrCacheInvalidation() = runBlocking {
        val session = UUID.randomUUID().toString()
        withContext(Dispatchers.IO) { database.rssSourceDao.insert(RssSource("old", "Name")) }
        val document = repo().load("old")!!
        var invalidations = 0
        val failing =
            RoomRssSourceEditorRepository(
                context,
                database,
                directory,
                beforeJournalWrite = { error("journal disk failed") },
                invalidate = { _, _ -> invalidations++ },
            )
        try {
            failing.save(
                session,
                document.copy(
                    draft =
                        document.draft.with(
                            RssSourceEditorField.SourceUrl,
                            RssSourceEditorText("new"),
                        )
                ),
                RssSourceEditorSaveAction.Close,
                false,
            )
            fail("must fail journal")
        } catch (_: IllegalStateException) {}
        withContext(Dispatchers.IO) {
            assertNotNull(database.rssSourceDao.getByKey("old"))
            assertNull(database.rssSourceDao.getByKey("new"))
        }
        assertEquals(0, invalidations)
        assertNull(repo().readDraft(session))
    }

    @Test
    fun committedTargetChangedByAnotherHostIsNeverOverwrittenDuringRecovery() = runBlocking {
        val session = UUID.randomUUID().toString()
        withContext(Dispatchers.IO) { database.rssSourceDao.insert(RssSource("old", "Name")) }
        val document = repo().load("old")!!
        val failing =
            RoomRssSourceEditorRepository(
                context,
                database,
                directory,
                invalidate = { _, _ -> error("invalidate failed") },
            )
        try {
            failing.save(
                session,
                document.copy(
                    draft =
                        document.draft.with(
                            RssSourceEditorField.SourceUrl,
                            RssSourceEditorText("new"),
                        )
                ),
                RssSourceEditorSaveAction.Close,
                false,
            )
            fail("must fail invalidation")
        } catch (_: IllegalStateException) {}
        withContext(Dispatchers.IO) {
            database.rssSourceDao.insert(
                RssSource("new", "external", ruleContent = "external rule")
            )
        }
        try {
            repo().readDraft(session)
            fail("must reject changed target")
        } catch (_: IllegalStateException) {}
        withContext(Dispatchers.IO) {
            assertEquals("external", database.rssSourceDao.getByKey("new")!!.sourceName)
            assertEquals("external rule", database.rssSourceDao.getByKey("new")!!.ruleContent)
            assertNull(database.rssSourceDao.getByKey("old"))
        }
    }

    @Test
    fun largeDiskDraftRecoversAllCursorsAndOlderWritesCannotOverwriteIt() = runBlocking {
        val repository = repo()
        val session = UUID.randomUUID().toString()
        val draft =
            RssSourceEditorDraft.from(RssSource("url", "name"))
                .with(
                    RssSourceEditorField.StartHtml,
                    RssSourceEditorText("large".repeat(200000), 80000, 90000),
                )
        val document = RssSourceEditorDocument("url", draft, revision = 4)
        repository.writeDraft(session, document)
        repository.writeDraft(session, document.copy(revision = 3, draft = RssSourceEditorDraft()))
        assertEquals(document, repo().readDraft(session))
        try {
            repository.readDraft("../outside")
            fail("invalid session")
        } catch (_: IllegalArgumentException) {}
        val path = repository.editorInput(draft[RssSourceEditorField.StartHtml].text)
        assertEquals(draft[RssSourceEditorField.StartHtml].text, repository.editorText(path))
        repository.clearEditor(path)
        assertFalse(File(path).exists())
    }
}
