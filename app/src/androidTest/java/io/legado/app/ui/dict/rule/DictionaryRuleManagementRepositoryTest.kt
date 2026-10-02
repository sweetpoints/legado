package io.legado.app.ui.dict.rule

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.DictRule
import io.legado.app.data.repository.DictionaryRuleSnapshot
import io.legado.app.data.repository.RoomDictionaryRuleManagementRepository
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class DictionaryRuleManagementRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomDictionaryRuleManagementRepository
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repository = RoomDictionaryRuleManagementRepository(ApplicationProvider.getApplicationContext(), database)
        database.dictRuleDao.insert(DictRule("a", "url-a", "show-a", false, 42), DictRule("b", "url-b", "show-b", true, 42), DictRule("c", "url-c", "show-c", false, 42))
    }
    @After fun close() { database.close() }
    @Test fun reorderedEqualSortNumbersFollowDesiredOrderAndPreserveMetadata() = runBlocking {
        repository.reorder(listOf("c", "a", "b", "c", "missing"))
        val rows = database.dictRuleDao.all
        assertEquals(listOf("c", "a", "b"), rows.map { it.name }); assertEquals(listOf(1, 2, 3), rows.map { it.sortNumber })
        assertEquals("show-a", rows[1].showRule); assertFalse(rows[1].enabled); assertTrue(rows[2].enabled)
    }
    @Test fun reorderRetainsNewRulesAndDoesNotRecreateDeletedRules() = runBlocking {
        database.dictRuleDao.delete(checkNotNull(database.dictRuleDao.getByName("b")))
        database.dictRuleDao.insert(DictRule("new", "new-url", sortNumber = 100))
        repository.reorder(listOf("c", "b", "a"))
        assertEquals(listOf("c", "a", "new"), database.dictRuleDao.all.map { it.name })
    }
    @Test fun batchUpdateKeepsLatestEditedFieldsAndNeverInsertsDeletedIdentity() = runBlocking {
        database.dictRuleDao.insert(DictRule("b", "updated-url", "updated-show", true, 7))
        repository.setEnabled(listOf("b", "missing"), false)
        val b = checkNotNull(database.dictRuleDao.getByName("b"))
        assertEquals("updated-url", b.urlRule); assertEquals("updated-show", b.showRule); assertEquals(7, b.sortNumber); assertFalse(b.enabled)
        assertNull(database.dictRuleDao.getByName("missing"))
        repository.delete(listOf("b")); assertNull(database.dictRuleDao.getByName("b")); assertEquals(2, database.dictRuleDao.all.size)
    }
    @Test fun failedReorderRollsBackEveryRow() = runBlocking {
        val before = database.dictRuleDao.all
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_dictionary_update BEFORE UPDATE ON dictRules WHEN NEW.name = 'a' BEGIN SELECT RAISE(ABORT, 'rejected'); END")
        assertTrue(runCatching { repository.reorder(listOf("c", "a", "b")) }.isFailure)
        assertEquals(before, database.dictRuleDao.all)
    }
    @Test fun shareFileAndExportRoundTripEveryFieldAndOnlyChosenRules() = runBlocking {
        val rule = DictionaryRuleSnapshot("chosen", "https://example.org?q={{key}}&a=1#result", "@js:result + ' & ? # '", false, 42)
        val file = File(repository.shareFile(listOf(rule)))
        try {
            val shared = GSON.fromJsonArray<DictRule>(file.readText()).getOrThrow().single()
            val exported = GSON.fromJsonArray<DictRule>(repository.json(listOf(rule))).getOrThrow().single()
            assertEquals(rule.entity(), shared); assertEquals(shared, exported)
        } finally { file.delete() }
    }
}
