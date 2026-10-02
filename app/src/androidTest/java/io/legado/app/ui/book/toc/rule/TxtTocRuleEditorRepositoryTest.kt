package io.legado.app.ui.book.toc.rule

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.repository.RoomTxtTocRuleEditorRepository
import io.legado.app.data.repository.TxtTocRuleSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class TxtTocRuleEditorRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomTxtTocRuleEditorRepository
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repository = RoomTxtTocRuleEditorRepository(database)
    }
    @After fun close() { database.close() }
    @Test fun savesAndLoadsAllFieldsIncludingNegativeDefaultIdentity() = runBlocking {
        val rule = TxtTocRuleSnapshot(-12, "name", "^Chapter (.+)$", "@js:result", "Chapter One", 42, false)
        assertEquals(rule, repository.save(rule)); assertEquals(rule, repository.load(-12))
    }
    @Test fun editsKeepLatestSortingAndEnabledMetadataChangedWhileDialogOpen() = runBlocking {
        val old = TxtTocRuleSnapshot(123, "old", "regex", serialNumber = 42, enable = false)
        repository.save(old)
        database.txtTocRuleDao.update(old.copy(serialNumber = 99, enable = true).entity())
        val updated = repository.save(old.copy(name = "new", replacement = "replacement", example = "sample"))
        assertEquals(old.copy(name = "new", replacement = "replacement", example = "sample", serialNumber = 99, enable = true), updated)
        assertEquals(updated, repository.load(123))
    }
    @Test fun failedInsertLeavesOriginalFieldsAndMetadataIntact() = runBlocking {
        val old = TxtTocRuleSnapshot(123, "old", "regex", serialNumber = 42, enable = false)
        repository.save(old)
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_toc_editor_insert BEFORE INSERT ON txtTocRules WHEN NEW.name = 'rejected' BEGIN SELECT RAISE(ABORT, 'rejected'); END")
        assertTrue(runCatching { repository.save(old.copy(name = "rejected")) }.isFailure)
        assertEquals(old, repository.load(123)); assertEquals(1, database.txtTocRuleDao.count)
    }
    @Test fun deletedExistingRuleIsNotRecreatedBySave() = runBlocking {
        val old = TxtTocRuleSnapshot(123, "old", "regex", serialNumber = 42, enable = false)
        repository.save(old)
        val loaded = repository.load(old.id)!!
        database.txtTocRuleDao.delete(old.entity())
        assertTrue(runCatching { repository.save(loaded.copy(name = "draft"), requireExisting = true) }.isFailure)
        assertNull(repository.load(old.id)); assertEquals(0, database.txtTocRuleDao.count)
        assertEquals(loaded, repository.save(loaded, requireExisting = false))
    }

}
