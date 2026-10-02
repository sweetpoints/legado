package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.HighlightRule
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class RoomHighlightGroupRepositoryTest {
    private lateinit var db: AppDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build() }
    @After fun cleanup() { db.close() }
    @Test fun renameMatchesWholeTrimmedLabelAndPreservesAllOtherMetadata() = runBlocking {
        val exact = HighlightRule(id = 901, name = "Rule", pattern = "regex", group = " A ", isEnabled = false, order = 73, scope = "book", applyToTitle = true, applyToBody = false)
        val comma = exact.copy(id = 902, uuid = java.util.UUID.randomUUID().toString(), group = "A,B")
        val similar = exact.copy(id = 903, uuid = java.util.UUID.randomUUID().toString(), group = "AA")
        withContext(Dispatchers.IO) { db.highlightRuleDao.insert(exact, comma, similar) }
        val repo = RoomHighlightGroupRepository(db); repo.rename("A", "  New  ")
        withContext(Dispatchers.IO) { assertEquals(GSON.toJson(exact.copy(group = "New")), GSON.toJson(db.highlightRuleDao.findById(901)))
            assertEquals(GSON.toJson(comma), GSON.toJson(db.highlightRuleDao.findById(902))); assertEquals(GSON.toJson(similar), GSON.toJson(db.highlightRuleDao.findById(903))) }
        repo.rename("New", "  "); assertEquals("New", withContext(Dispatchers.IO) { db.highlightRuleDao.findById(901)!!.group })
    }
    @Test fun deleteRemovesMatchingRulesWhileMoveMergesAndUngroupsWithoutDeletingRecords() = runBlocking {
        withContext(Dispatchers.IO) { db.highlightRuleDao.insert(HighlightRule(id = 911, group = "A"), HighlightRule(id = 912, group = " A "), HighlightRule(id = 913, group = "No group"), HighlightRule(id = 914, group = "AA"), HighlightRule(id = 915, group = "A,B")) }
        val repo = RoomHighlightGroupRepository(db); repo.move("A", "No group")
        assertEquals(3, withContext(Dispatchers.IO) { db.highlightRuleDao.all.count { it.group == "No group" } })
        repo.move("No group", null)
        assertEquals(3, withContext(Dispatchers.IO) { db.highlightRuleDao.all.count { it.group == null } })
        repo.delete(" AA ")
        assertEquals(setOf(911L, 912L, 913L, 915L), withContext(Dispatchers.IO) { db.highlightRuleDao.all.map { it.id }.toSet() })
        assertEquals("A,B", withContext(Dispatchers.IO) { db.highlightRuleDao.findById(915)!!.group })
    }
    @Test fun observedGroupsKeepDaoNoCaseOrderingAndExcludeBlankAndNull() = runBlocking {
        withContext(Dispatchers.IO) { db.highlightRuleDao.insert(HighlightRule(group = "zeta"), HighlightRule(group = "Alpha"), HighlightRule(group = "beta"), HighlightRule(group = "Alpha"), HighlightRule(group = " "), HighlightRule(group = null)) }
        assertEquals(listOf("Alpha", "beta", "zeta"), RoomHighlightGroupRepository(db).groups().first())
    }
}
