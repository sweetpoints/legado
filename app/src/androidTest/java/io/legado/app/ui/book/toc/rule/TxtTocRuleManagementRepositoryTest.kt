package io.legado.app.ui.book.toc.rule

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.data.repository.TxtTocRuleSnapshot
import io.legado.app.data.repository.RoomTxtTocRuleManagementRepository
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class TxtTocRuleManagementRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomTxtTocRuleManagementRepository
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repository = RoomTxtTocRuleManagementRepository(ApplicationProvider.getApplicationContext(), database)
        database.txtTocRuleDao.insert(TxtTocRule(1, "a", "regex-a", "replace-a", "sample-a", 42, false), TxtTocRule(2, "b", "regex-b", "replace-b", "sample-b", 42, true), TxtTocRule(3, "c", "regex-c", serialNumber = 42))
    }
    @After fun close() { database.close() }
    @Test fun reorderEqualNumbersPreservesAllFieldsAndUsesActualOrder() = runBlocking {
        repository.reorder(listOf(3, 1, 2, 3, 99))
        val rows = database.txtTocRuleDao.all
        assertEquals(listOf(3L, 1L, 2L), rows.map { it.id }); assertEquals(listOf(1, 2, 3), rows.map { it.serialNumber })
        assertEquals("replace-a", rows[1].replacement); assertEquals("sample-a", rows[1].example); assertFalse(rows[1].enable)
    }
    @Test fun reorderAndEdgesRetainNewRowsAndNeverRecreateDeletedRows() = runBlocking {
        database.txtTocRuleDao.delete(checkNotNull(database.txtTocRuleDao.get(2)))
        database.txtTocRuleDao.insert(TxtTocRule(4, "new", "new-regex", serialNumber = 100))
        repository.reorder(listOf(3, 2, 1)); assertEquals(listOf(3L, 1L, 4L), database.txtTocRuleDao.all.map { it.id })
        repository.moveToEdge(listOf(4, 99), true); assertEquals(listOf(4L, 3L, 1L), database.txtTocRuleDao.all.map { it.id })
        repository.moveToEdge(listOf(4), false); assertEquals(listOf(3L, 1L, 4L), database.txtTocRuleDao.all.map { it.id })
    }
    @Test fun enableReadsLatestEditableFieldsInsteadOfStaleListAndDoesNotInsertMissingRows() = runBlocking {
        database.txtTocRuleDao.update(TxtTocRule(2, "renamed", "updated-regex", "updated-replacement", "updated-example", 7, true))
        repository.setEnabled(listOf(2, 99), false)
        val row = checkNotNull(database.txtTocRuleDao.get(2))
        assertEquals("renamed", row.name); assertEquals("updated-regex", row.rule); assertEquals("updated-replacement", row.replacement); assertEquals("updated-example", row.example)
        assertEquals(7, row.serialNumber); assertFalse(row.enable); assertNull(database.txtTocRuleDao.get(99))
        repository.delete(listOf(2)); assertNull(database.txtTocRuleDao.get(2)); assertEquals(2, database.txtTocRuleDao.count)
    }
    @Test fun failureRollsBackAllSortingChanges() = runBlocking {
        val before = database.txtTocRuleDao.all.map(TxtTocRuleSnapshot::from)
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_toc_management_update BEFORE UPDATE ON txtTocRules WHEN NEW.id = 1 BEGIN SELECT RAISE(ABORT, 'rejected'); END")
        assertTrue(runCatching { repository.reorder(listOf(3, 1, 2)) }.isFailure)
        assertEquals(before, database.txtTocRuleDao.all.map(TxtTocRuleSnapshot::from))
    }
    @Test fun shareAndExportRoundTripAllSevenFields() = runBlocking {
        val selected = TxtTocRuleSnapshot(-12, "name", "^Chapter (.+)$", "@js:result", "Chapter One", 42, false)
        val file = File(repository.shareFile(listOf(selected)))
        try {
            assertEquals(selected, TxtTocRuleSnapshot.from(GSON.fromJsonArray<TxtTocRule>(file.readText()).getOrThrow().single()))
            assertEquals(selected, TxtTocRuleSnapshot.from(GSON.fromJsonArray<TxtTocRule>(repository.json(listOf(selected))).getOrThrow().single()))
        } finally { file.delete() }
    }
}
