package io.legado.app.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class TxtTocRuleImportRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun existingIdRetainsOriginalOptInContractAndDefaultImportPreservesEveryNewField() = runBlocking {
        val id = System.nanoTime(); val session = UUID.randomUUID().toString()
        val fresh = TxtTocRule(id, "Fresh", "rule", "replacement", "example", 23, false)
        val old = TxtTocRule(id + 1, "Stored", "old", "old replacement", null, 3, true)
        val incoming = old.copy(name = "Changed", rule = "different", enable = false)
        val repo = AppTxtTocRuleImportRepository(context)
        try {
            withContext(Dispatchers.IO) { appDb.txtTocRuleDao.insert(old) }
            val items = repo.read(GSON.toJson(listOf(fresh, incoming)))
            assertFalse(items[0].existsLocally); assertTrue(items[0].selectedByDefault)
            assertTrue(items[1].existsLocally); assertFalse(items[1].selectedByDefault)
            assertEquals("example", items[0].example)
            repo.stage(session, items); assertEquals(TxtTocRuleImportSession(items), repo.restore(session))
            repo.insert(session, items, setOf(items.first().key))
            withContext(Dispatchers.IO) {
                assertEquals(GSON.toJson(fresh), GSON.toJson(appDb.txtTocRuleDao.get(fresh.id)))
                assertEquals(GSON.toJson(old), GSON.toJson(appDb.txtTocRuleDao.get(old.id)))
            }
            assertTrue(repo.restore(session)!!.committed)
        } finally {
            withContext(Dispatchers.IO) { appDb.txtTocRuleDao.delete(fresh, old) }
            File(context.cacheDir, "txt-toc-rule-import/$session.json").delete()
        }
    }
    @Test fun uriAndCodeEditRetainExampleAndStableKeyAndCannotWriteBeforeConfirm() = runBlocking {
        val source = File(context.cacheDir, "toc-source-${UUID.randomUUID()}.json")
        val rule = TxtTocRule(System.nanoTime(), "Rule", "pattern", "replacement", "three\\nlines", 5, false)
        val repo = AppTxtTocRuleImportRepository(context)
        try {
            withContext(Dispatchers.IO) { source.writeText(GSON.toJson(rule)) }
            val items = repo.read(android.net.Uri.fromFile(source).toString())
            assertEquals(repo.read(GSON.toJson(rule)), items)
            val edit = repo.edit(items.single().key, GSON.toJson(rule.copy(id = rule.id + 1, name = "Edited", example = "changed")))
            assertEquals(items.single().key, edit.key); assertEquals("changed", edit.example)
            assertTrue(runCatching { repo.edit("stable", "invalid") }.isFailure)
            withContext(Dispatchers.IO) { assertNull(appDb.txtTocRuleDao.get(rule.id)); assertNull(appDb.txtTocRuleDao.get(rule.id + 1)) }
        } finally { source.delete() }
    }
}
