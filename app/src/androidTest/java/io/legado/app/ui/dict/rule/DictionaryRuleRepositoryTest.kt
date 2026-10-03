package io.legado.app.ui.dict.rule

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.repository.DictionaryRuleSnapshot
import io.legado.app.data.repository.RoomDictionaryRuleRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DictionaryRuleRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomDictionaryRuleRepository

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    AppDatabase::class.java,
                )
                .build()
        repository = RoomDictionaryRuleRepository(database)
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun renameRemovesOldRowAndRetainsMetadata() = runBlocking {
        val old = DictionaryRuleSnapshot("old", "url", "show", false, 42)
        repository.save(null, old)
        val renamed = old.copy(name = "new", showRule = "new-show")
        repository.save("old", renamed)
        assertNull(repository.load("old"))
        assertEquals(renamed, repository.load("new"))
    }

    @Test
    fun failedInsertRollsBackDeleteOfOriginalRule() = runBlocking {
        val old = DictionaryRuleSnapshot("old", "url", "show")
        repository.save(null, old)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_dictionary_insert BEFORE INSERT ON dictRules WHEN NEW.name = 'rejected' BEGIN SELECT RAISE(ABORT, 'rejected'); END"
        )
        val result = runCatching { repository.save("old", old.copy(name = "rejected")) }
        assertTrue(result.isFailure)
        assertEquals(old, repository.load("old"))
        assertNull(repository.load("rejected"))
    }
}
