package io.legado.app.data.repository

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class HighlightImportRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun actualUriComparisonImportPreservesMetadataAndExistingIdWhileIgnoringImportedIdAndOrder() = runBlocking {
        val session = UUID.randomUUID().toString(); val file = File(context.cacheDir, "highlight-$session.json")
        val old = HighlightRule(name = "Stored", pattern = "old", style = "{\"bold\":true}", order = 100)
        val fresh = HighlightRule(name = "Fresh", pattern = "fresh", style = "{\"bold\":true}", group = "Import group", scope = "Book",
            applyToTitle = true, applyToBody = false, isEnabled = false, timeoutMillisecond = 2300)
        val before = withContext(Dispatchers.IO) { appDb.highlightRuleDao.all.map { it.copy() } }
        try {
            val id = withContext(Dispatchers.IO) { appDb.highlightRuleDao.insert(old).single() }
            val changed = old.copy(id = 999999, pattern = "updated", order = 500)
            withContext(Dispatchers.IO) { file.writeText(GSON.toJson(HighlightRuleFile(HighlightRuleFile.TYPE, listOf(changed, fresh)))) }
            val repo = AppHighlightImportRepository(context)
            // Main callers suspend while URI, parser, Room and AtomicFile work is moved to IO.
            val items = withContext(Dispatchers.Main) { repo.read(Uri.fromFile(file).toString()) }
            assertEquals(listOf(HighlightImportStatus.UPDATE, HighlightImportStatus.NEW), items.map { it.status })
            repo.stage(session, items); assertEquals(items, repo.restore(session)!!.items)
            withContext(Dispatchers.Main) { repo.insert(session, items, setOf(old.uuid, fresh.uuid)) }
            assertTrue(repo.restore(session)!!.committed)
            val imported = withContext(Dispatchers.IO) { appDb.highlightRuleDao.all }
            assertEquals(id, imported.single { it.uuid == old.uuid }.id)
            val saved = imported.single { it.uuid == fresh.uuid }
            assertEquals("Import group", saved.group); assertEquals("Book", saved.scope); assertFalse(saved.isEnabled)
            assertTrue(saved.applyToTitle); assertFalse(saved.applyToBody); assertEquals(2300L, saved.timeoutMillisecond)
            assertEquals(fresh.styleObj(), saved.styleObj())
        } finally {
            withContext(Dispatchers.IO) {
                appDb.highlightRuleDao.all.filter { it.uuid == old.uuid || it.uuid == fresh.uuid }.forEach { appDb.highlightRuleDao.delete(it) }
                if (before.isNotEmpty()) appDb.highlightRuleDao.update(*before.toTypedArray())
                file.delete(); File(context.cacheDir, "highlight-rule-import/$session.json").delete()
            }
        }
    }
    @Test fun strictInvalidFileDoesNotCreatePartialSelectionOrWriteAnyRule() = runBlocking {
        val file = File(context.cacheDir, "invalid-highlight-${UUID.randomUUID()}.json")
        val rule = HighlightRule(pattern = "valid", style = "{}")
        val originalCount = withContext(Dispatchers.IO) { appDb.highlightRuleDao.all.size }
        try {
            withContext(Dispatchers.IO) { file.writeText("[{\"uuid\":\"${rule.uuid}\",\"pattern\":\"valid\",\"style\":\"{}\"},{\"uuid\":\"invalid\",\"pattern\":\"bad\",\"style\":\"{}\"}]") }
            val failed = runCatching { AppHighlightImportRepository(context).read(Uri.fromFile(file).toString()) }
            assertTrue(failed.isFailure); assertEquals(originalCount, withContext(Dispatchers.IO) { appDb.highlightRuleDao.all.size })
        } finally { withContext(Dispatchers.IO) { file.delete() } }
    }
}
