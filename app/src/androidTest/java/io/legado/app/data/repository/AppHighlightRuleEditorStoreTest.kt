package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.HighlightRule
import io.legado.app.help.HighlightStyle
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AppHighlightRuleEditorStoreTest {
    @Test fun actualRoomPreservesMetadataAppendsNewOrderAndUuidRetryCannotInsertTwiceOrMoveExistingOrder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val original = HighlightRule(name = "Existing", pattern = "old", isEnabled = false, order = 77, timeoutMillisecond = 789,
                scope = "book", group = "Group", applyToTitle = true, applyToBody = false).apply { applyStyle(HighlightStyle(fontPath = "font", italic = true)) }
            val id = withContext(Dispatchers.IO) { database.highlightRuleDao.insert(original).single() }
            val store = AppHighlightRuleEditorStore(context, database)
            val loaded = withContext(Dispatchers.IO) { store.load(id) }!!
            assertEquals(77, loaded.order); assertEquals(789L, loaded.timeoutMillisecond); assertFalse(loaded.isEnabled)
            assertTrue(loaded.applyToTitle); assertFalse(loaded.applyToBody); assertEquals("font", loaded.style.fontPath)
            val updated = loaded.copy(pattern = "new", group = "Changed", style = HighlightStyle(fill = 123, bold = true))
            assertEquals(id, withContext(Dispatchers.IO) { store.insert(updated) })
            val actual = withContext(Dispatchers.IO) { database.highlightRuleDao.findById(id) }!!
            assertEquals(original.uuid, actual.uuid); assertEquals("Changed", actual.group); assertEquals(77, actual.order)
            assertEquals(123, actual.styleObj().fill); assertFalse(actual.isEnabled); assertEquals(789L, actual.timeoutMillisecond)
            val fresh = HighlightRuleDraft(name = "Same name allowed", pattern = "fresh")
            val added = withContext(Dispatchers.IO) { store.insert(fresh) }
            assertEquals(78, withContext(Dispatchers.IO) { database.highlightRuleDao.findById(added) }!!.order)
            val repeated = withContext(Dispatchers.IO) { store.insert(fresh.copy(pattern = "retry")) }
            assertEquals(added, repeated); assertEquals(2, withContext(Dispatchers.IO) { database.highlightRuleDao.all.size })
            assertEquals(78, withContext(Dispatchers.IO) { database.highlightRuleDao.findById(added) }!!.order)
        } finally { database.close() }
    }
    @Test fun actualPrivateDiskRestoresLargePatternAndRejectsOlderRevisionAcrossInstances() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val session = UUID.randomUUID().toString()
        val file = java.io.File(context.filesDir, "highlight-rule-editor-drafts/$session.json")
        try {
            val draft = HighlightRuleEditorDraft(HighlightRuleDraft(pattern = "pattern".repeat(180000), style = HighlightStyle(fill = 123, fontPath = "font")), 20)
            val store = AppHighlightRuleEditorStore(context); val other = AppHighlightRuleEditorStore(context)
            store.write(session, draft); other.write(session, draft.copy(revision = 19, rule = draft.rule.copy(pattern = "stale")))
            assertEquals(draft, other.read(session))
            coroutineScope { (21L..30L).map { revision -> async(Dispatchers.IO) { other.write(session, draft.copy(revision = revision)) } }.awaitAll() }
            assertEquals(30L, store.read(session)!!.revision)
        } finally { file.delete(); java.io.File(file.path + ".bak").delete(); java.io.File(file.path + ".new").delete() }
    }
}
