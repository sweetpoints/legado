package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.model.RuleUpdate
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class AppBookImportStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var store: AppBookImportStore
    private lateinit var repository: DefaultBookImportRepository
    private lateinit var directory: File
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        directory = File(context.cacheDir, "book-import-store-${UUID.randomUUID()}")
        store = AppBookImportStore(context, database, { database.bookSourceDao.insert(*it.toTypedArray()) }, directory)
        repository = DefaultBookImportRepository(store)
    }
    @After fun close() { database.close(); directory.deleteRecursively() }
    @Test fun cachedRequestIsConsumedOnceAndRoomComparisonBatchesMoreThan900Urls() = runBlocking {
        val url = "https://book-import-${UUID.randomUUID()}.invalid/list"
        val existing = List(901) { BookSource(bookSourceUrl = "https://source.invalid/$it", bookSourceName = "Stored $it", lastUpdateTime = 1) }
        withContext(Dispatchers.IO) { database.bookSourceDao.insert(*existing.toTypedArray()) }
        val incoming = existing.map { it.copy(lastUpdateTime = 2) } + BookSource(bookSourceUrl = "https://new.invalid", bookSourceName = "New")
        try {
            RuleUpdate.cacheBookSourceMap[url] = incoming
            val original = repository.load(url); assertFalse(RuleUpdate.cacheBookSourceMap.containsKey(url)); assertEquals(902, original.size)
            val entries = repository.refresh(original, false, emptyMap())
            assertTrue(entries.take(901).all { it.status == BookImportStatus.Update }); assertEquals(BookImportStatus.New, entries.last().status)
        } finally { RuleUpdate.cacheBookSourceMap.remove(url) }
    }
    @Test fun realRoomPublicationKeepsFieldsAndEnableExploreThenAppliesGroupAndCommitsSession() = runBlocking {
        val previous = BookSource(bookSourceUrl = "https://fixed.invalid", bookSourceName = "Stored", bookSourceGroup = "A;B", enabled = false, enabledExplore = false, customOrder = 19, lastUpdateTime = 1)
        withContext(Dispatchers.IO) { database.bookSourceDao.insert(previous) }
        val json = """{"bookSourceUrl":"https://fixed.invalid","bookSourceName":"Incoming","bookSourceGroup":"Incoming","bookSourceType":2,"lastUpdateTime":2,"enabled":true,"enabledExplore":true,"header":"header","jsLib":"library","loginCheckJs":"check","coverDecodeJs":"decode","variableComment":"vars","exploreScreen":"screen","eventListener":true,"customButton":true,"ruleSearch":{"bookList":"list","name":"name"},"ruleBookInfo":{"name":"title","tocUrl":"toc"},"ruleToc":{"chapterList":"chapters","chapterUrl":"url"},"ruleContent":{"content":"content","nextContentUrl":"next"}}"""
        val entries = repository.refresh(repository.load(json), false, emptyMap()); val snapshot = BookImportSnapshot(entries, false, emptyMap())
        repository.stage("publish", snapshot)
        repository.insert("publish", snapshot, setOf(entries.single().key), BookImportPreferences(keepName = true, keepGroup = true, keepEnable = true), " B ", true)
        val source = withContext(Dispatchers.IO) { database.bookSourceDao.getBookSource(previous.bookSourceUrl)!! }
        val expected = GSON.fromJsonObject<BookSource>(json).getOrThrow().copy(bookSourceName = "Stored", bookSourceGroup = "A,B", enabled = false, enabledExplore = false, customOrder = 19)
        assertEquals(GSON.toJson(expected), GSON.toJson(source)); assertTrue(repository.restore("publish")!!.committed)
        assertEquals(GSON.toJson(source), repository.source(previous.bookSourceUrl))
    }
    @Test fun realSourceRulesProduceDerivedPreviewAndInvalidPreviewCannotBeImported() = runBlocking {
        withContext(Dispatchers.IO) { database.replaceRuleDao.insert(ReplaceRule(id = 9, name = "source", pattern = "Original", replacement = "\"", isRegex = false, scopeSource = true)) }
        val original = BookSource(bookSourceUrl = "https://original.invalid", bookSourceName = "Original")
        val entries = repository.refresh(repository.load(GSON.toJson(original)), true, emptyMap())
        assertFalse(entries.single().canImport); assertNotNull(entries.single().replacementError); assertEquals(GSON.toJson(original), entries.single().originalJson)
        repository.insert("invalid-preview", BookImportSnapshot(entries, true, emptyMap()), setOf(entries.single().key), BookImportPreferences(), null, false)
        assertNull(withContext(Dispatchers.IO) { database.bookSourceDao.getBookSource(original.bookSourceUrl) })
    }
    @Test fun fileUriAndDurableSessionRestoreWithoutReloadingRemoteRequest() = runBlocking {
        val file = File(context.cacheDir, "book-import-${UUID.randomUUID()}.json")
        try {
            val source = BookSource(bookSourceUrl = "https://uri.invalid", bookSourceName = "URI", mainJs = "large JS\n".repeat(100000), loginUi = "[]")
            withContext(Dispatchers.IO) { file.writeText(GSON.toJson(source)) }
            val entries = repository.refresh(repository.load(Uri.fromFile(file).toString()), false, emptyMap())
            val snapshot = BookImportSnapshot(entries, false, mapOf(entries.single().key to listOf(1, 4)))
            repository.stage("restore", snapshot)
            val restored = DefaultBookImportRepository(AppBookImportStore(context, database, { database.bookSourceDao.insert(*it.toTypedArray()) }, directory))
            assertEquals(snapshot, restored.restore("restore")); assertEquals(GSON.toJson(source), restored.restore("restore")!!.items.single().originalJson)
            assertFalse(entries.single().needsLogin)
        } finally { file.delete() }
    }
    @Test fun actualJavaScriptExtractorRetainsScriptAndRecognizesLoginForm() = runBlocking {
        val js = """var config={bookSourceUrl:'https://js.invalid',bookSourceName:'JS',loginUi:[{name:'Username',type:'text'}]}; function search(){return [];} function getChapters(){return [];} function getContent(){return '';}"""
        val entries = repository.refresh(repository.load(js), false, emptyMap())
        val source = GSON.fromJsonObject<BookSource>(entries.single().json).getOrThrow()
        assertEquals(js, source.mainJs); assertEquals("JS", source.bookSourceName); assertTrue(entries.single().needsLogin)
    }
    @Test fun publicationFailureKeepsStagedSessionUncommittedAndDoesNotWriteRoom() = runBlocking {
        val failing = DefaultBookImportRepository(AppBookImportStore(context, database, { error("publish failed") }, directory))
        val entries = failing.refresh(failing.load(GSON.toJson(BookSource(bookSourceUrl = "https://fail.invalid", bookSourceName = "Fail"))), false, emptyMap())
        val snapshot = BookImportSnapshot(entries, false, emptyMap()); failing.stage("failure", snapshot)
        assertTrue(runCatching { failing.insert("failure", snapshot, setOf(entries.single().key), BookImportPreferences(), null, false) }.isFailure)
        assertFalse(failing.restore("failure")!!.committed); assertNull(withContext(Dispatchers.IO) { database.bookSourceDao.getBookSource("https://fail.invalid") })
    }
}
