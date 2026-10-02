package io.legado.app.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.DictRule
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class DictRuleImportRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun actualRoomComparisonUsesNameAndDefaultSelectionNeverOverwritesExistingRules() = runBlocking {
        val suffix = UUID.randomUUID().toString(); val session = UUID.randomUUID().toString()
        val new = DictRule("new-$suffix", "url", "show", enabled = false, sortNumber = 23)
        val old = DictRule("stored-$suffix", "old", "old-show", enabled = true, sortNumber = 3)
        val incoming = old.copy(urlRule = "new", showRule = "new-show", enabled = false, sortNumber = 999)
        val repo = AppDictRuleImportRepository(context)
        try {
            withContext(Dispatchers.IO) { appDb.dictRuleDao.insert(old) }
            val items = repo.read(GSON.toJson(listOf(new, incoming)))
            assertEquals(2, items.size); assertFalse(items[0].existsLocally); assertTrue(items[1].existsLocally)
            assertTrue(items[0].selectedByDefault); assertFalse(items[1].selectedByDefault)
            repo.stage(session, items); assertEquals(DictRuleImportSession(items), repo.restore(session))
            repo.insert(session, items, setOf(items[0].key))
            withContext(Dispatchers.IO) {
                assertEquals(GSON.toJson(new), GSON.toJson(appDb.dictRuleDao.getByName(new.name)))
                assertEquals(GSON.toJson(old), GSON.toJson(appDb.dictRuleDao.getByName(old.name)))
            }
            assertTrue(repo.restore(session)!!.committed)
        } finally {
            withContext(Dispatchers.IO) { appDb.dictRuleDao.delete(new, old) }
            File(context.cacheDir, "dict-rule-import/$session.json").delete()
        }
    }
    @Test fun codeRenameRechecksLocalNamePreservesStableKeyAndUriRetainsAllFields() = runBlocking {
        val suffix = UUID.randomUUID().toString(); val source = File(context.cacheDir, "dict-source-$suffix.json")
        val item = DictRule("new-$suffix", "url", "show", false, 123)
        val existing = DictRule("existing-$suffix", "different", "different", true, 4)
        val repo = AppDictRuleImportRepository(context)
        try {
            withContext(Dispatchers.IO) { appDb.dictRuleDao.insert(existing); source.writeText(GSON.toJson(item)) }
            val items = repo.read(android.net.Uri.fromFile(source).toString())
            assertEquals(repo.read(GSON.toJson(item)), items)
            val edited = repo.edit(items.single().key, GSON.toJson(item.copy(name = existing.name)))
            assertEquals(items.single().key, edited.key); assertTrue(edited.existsLocally)
            assertTrue(runCatching { repo.edit("stable", "invalid") }.isFailure)
            withContext(Dispatchers.IO) {
                assertNull(appDb.dictRuleDao.getByName(item.name))
                assertEquals(GSON.toJson(existing), GSON.toJson(appDb.dictRuleDao.getByName(existing.name)))
            }
        } finally {
            withContext(Dispatchers.IO) { appDb.dictRuleDao.delete(existing) }; source.delete()
        }
    }
}
