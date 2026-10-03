package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.*
import org.junit.Assert.*

class ReplaceRuleGroupRepositoryTest {
    private lateinit var database: AppDatabase

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                )
                .build()
    }

    @After
    fun cleanup() {
        database.close()
    }

    @Test
    fun renameAndDeleteOnlyExactMembersAndPreserveFullMetadata() = runBlocking {
        val exact =
            ReplaceRule(
                id = 801,
                name = "Rule",
                pattern = "regex",
                replacement = "new",
                group = "One,Other",
                isEnabled = false,
            )
        val similar = exact.copy(id = 802, group = "OneMore")
        withContext(Dispatchers.IO) { database.replaceRuleDao.insert(exact, similar) }
        val repository = ReplaceRuleGroupRepository(database)
        repository.rename("One", "New")
        withContext(Dispatchers.IO) {
            assertEquals(
                GSON.toJson(exact.copy(group = "Other,New")),
                GSON.toJson(database.replaceRuleDao.findById(exact.id)),
            )
            assertEquals(
                GSON.toJson(similar),
                GSON.toJson(database.replaceRuleDao.findById(similar.id)),
            )
        }
        repository.rename("New", null)
        withContext(Dispatchers.IO) {
            assertEquals("Other", database.replaceRuleDao.findById(exact.id)!!.group)
        }
    }

    @Test
    fun addingGroupOnlyAssignsActuallyUngroupedRecords() = runBlocking {
        val ungrouped = ReplaceRule(id = 811, group = null)
        val empty = ungrouped.copy(id = 812, group = "")
        val existing = ungrouped.copy(id = 813, group = "Existing")
        withContext(Dispatchers.IO) { database.replaceRuleDao.insert(ungrouped, empty, existing) }
        val repository = ReplaceRuleGroupRepository(database)
        repository.add(" ")
        repository.add(" Exact ")
        withContext(Dispatchers.IO) {
            assertEquals(" Exact ", database.replaceRuleDao.findById(811)!!.group)
            assertEquals(" Exact ", database.replaceRuleDao.findById(812)!!.group)
            assertEquals(GSON.toJson(existing), GSON.toJson(database.replaceRuleDao.findById(813)))
        }
    }

    @Test
    fun percentAndUnderscoreNamesNeverRenameOtherSqlCandidates() = runBlocking {
        withContext(Dispatchers.IO) {
            database.replaceRuleDao.insert(
                ReplaceRule(id = 821, group = "A%_"),
                ReplaceRule(id = 822, group = "ABC"),
            )
        }
        ReplaceRuleGroupRepository(database).rename("A%_", "Target")
        withContext(Dispatchers.IO) {
            assertEquals("Target", database.replaceRuleDao.findById(821)!!.group)
            assertEquals("ABC", database.replaceRuleDao.findById(822)!!.group)
        }
    }
}
