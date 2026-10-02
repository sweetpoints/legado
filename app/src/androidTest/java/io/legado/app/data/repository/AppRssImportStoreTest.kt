package io.legado.app.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.model.RuleUpdate
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AppRssImportStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun realCacheConsumptionAndTransactionalRoomComparisonHandleMoreThan900SourceUrls() = runBlocking {
        val suffix = UUID.randomUUID().toString(); val url = "https://rss-import-$suffix.invalid/list"
        val existing = List(901) { RssSource(sourceUrl = "https://rss-import-$suffix.invalid/$it", sourceName = "Stored $it", lastUpdateTime = 1) }
        val fresh = RssSource(sourceUrl = "https://rss-import-$suffix.invalid/new", sourceName = "Fresh", header = "header", jsLib = "js", lastUpdateTime = 2)
        val incoming = existing.map { it.copy(lastUpdateTime = 2) } + fresh
        val repo = DefaultRssImportRepository(AppRssImportStore(context))
        try {
            withContext(Dispatchers.IO) { appDb.rssSourceDao.insert(*existing.toTypedArray()) }
            RuleUpdate.cacheRssSourceMap[url] = incoming
            val originals = repo.load(url)
            assertFalse(RuleUpdate.cacheRssSourceMap.containsKey(url)); assertEquals(902, originals.size)
            val entries = repo.refresh(originals, false, emptyMap())
            assertTrue(entries.take(901).all { it.status == RssImportStatus.Update && it.selectedByDefault })
            assertEquals(RssImportStatus.New, entries.last().status)
            assertEquals(GSON.toJson(fresh), entries.last().originalJson)
        } finally {
            RuleUpdate.cacheRssSourceMap.remove(url)
            withContext(Dispatchers.IO) { appDb.rssSourceDao.delete(*existing.toTypedArray()) }
        }
    }
    @Test fun actualUriAndAtomicSessionRoundTripAndRememberGroupDisableClearsStoredGroup() = runBlocking {
        val session = UUID.randomUUID().toString(); val source = File(context.cacheDir, "rss-source-$session.json")
        val store = AppRssImportStore(context); val repo = DefaultRssImportRepository(store)
        val keys = listOf(PreferKey.importKeepName, PreferKey.importKeepGroup, PreferKey.importKeepEnable,
            PreferKey.importShowComment, PreferKey.importRememberGroup, PreferKey.importLastGroup,
            PreferKey.importLastGroupAdd, PreferKey.importReplaceSource)
        val prefs = context.defaultSharedPreferences
        val original = prefs.all.filterKeys { it in keys }
        try {
            val value = RssSource(sourceUrl = "https://rss-uri-$session.invalid", sourceName = "URI", header = "header", ruleContent = "content")
            withContext(Dispatchers.IO) { source.writeText(GSON.toJson(value)) }
            val originals = repo.load(android.net.Uri.fromFile(source).toString())
            assertEquals(GSON.toJson(value), originals.single().json)
            val entries = repo.refresh(originals, false, emptyMap())
            val snapshot = RssImportSnapshot(entries, false, mapOf(entries.single().key to listOf(4L)))
            repo.stage(session, snapshot); assertEquals(snapshot, repo.restore(session))
            repo.preferences(RssImportPreferences(keepName = true, rememberGroup = true, lastGroup = "Remember", lastGroupAdd = true))
            assertEquals("Remember", repo.preferences().lastGroup)
            repo.preferences(repo.preferences().copy(rememberGroup = false))
            assertNull(repo.preferences().lastGroup); assertFalse(repo.preferences().lastGroupAdd)
        } finally {
            withContext(Dispatchers.IO) {
                prefs.edit().apply {
                    keys.forEach { remove(it) }
                    original.forEach { (key, value) -> when (value) {
                        is Boolean -> putBoolean(key, value)
                        is String -> putString(key, value)
                    } }
                }.commit()
                source.delete(); File(context.cacheDir, "rss-source-import/$session.json").delete()
            }
        }
    }
}
