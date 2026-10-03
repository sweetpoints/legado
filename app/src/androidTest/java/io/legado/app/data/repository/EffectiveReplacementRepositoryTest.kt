package io.legado.app.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EffectiveReplacementRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: AppEffectiveReplacementRepository
    private var originalMode = 0

    @Before
    fun setup() = runBlocking {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    AppDatabase::class.java,
                )
                .build()
        repository = AppEffectiveReplacementRepository(database)
        originalMode = AppConfig.chineseConverterType
        withContext(Dispatchers.IO) {
            database.replaceRuleDao.insert(
                ReplaceRule(1, "same", order = 8),
                ReplaceRule(2, "same", order = 3),
            )
        }
    }

    @After
    fun close() {
        AppConfig.chineseConverterType = originalMode
        database.close()
    }

    @Test
    fun sourceLookupRetainsDatabaseOrderingAndDisableOnlyUpdatesEnabledField() = runBlocking {
        val before =
            withContext(Dispatchers.IO) { checkNotNull(database.replaceRuleDao.findById(1)) }
        assertEquals(
            listOf(2L, 1L),
            repository.load(listOf(1, 2, 1, 99), emptyList()).rows.map { it.id },
        )
        repository.disable(1)
        withContext(Dispatchers.IO) {
            assertEquals(
                GSON.toJsonTree(before.copy(isEnabled = false)),
                GSON.toJsonTree(database.replaceRuleDao.findById(1)),
            )
            assertTrue(checkNotNull(database.replaceRuleDao.findById(2)).isEnabled)
            database.replaceRuleDao.delete(checkNotNull(database.replaceRuleDao.findById(1)))
        }
        repository.disable(1)
        withContext(Dispatchers.IO) { assertNull(database.replaceRuleDao.findById(1)) }
    }

    @Test
    fun readerSnapshotAndConversionSettingsRemainIndependentOfDatabaseRules() = runBlocking {
        val readerRows = listOf(EffectiveReplacementRow(17, "chapter"))
        repository.conversion(2)
        assertEquals(EffectiveReplacementSnapshot(readerRows, 2), repository.load(null, readerRows))
        repository.conversion(0)
        assertEquals(0, repository.load(emptyList(), readerRows).conversion)
        assertTrue(repository.load(emptyList(), readerRows).rows.isEmpty())
        assertTrue(runCatching { repository.conversion(7) }.isFailure)
        assertEquals(0, AppConfig.chineseConverterType)
    }
}
