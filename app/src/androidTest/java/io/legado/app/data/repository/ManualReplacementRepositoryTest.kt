package io.legado.app.data.repository

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.ManualReplacementViewModel
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ManualReplacementRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: AppManualReplacementRepository

    @Before
    fun setup() = runBlocking {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    AppDatabase::class.java,
                )
                .build()
        repository = AppManualReplacementRepository(database)
        withContext(Dispatchers.IO) {
            database.replaceRuleDao.insert(
                ReplaceRule(
                    1,
                    "Mixed",
                    group = "Group",
                    order = 8,
                    scopeSource = true,
                    scopeTitle = true,
                    scopeContent = true,
                ),
                ReplaceRule(
                    2,
                    "Source only",
                    order = 1,
                    scopeSource = true,
                    scopeTitle = false,
                    scopeContent = false,
                ),
                ReplaceRule(
                    3,
                    "Disabled mixed",
                    order = 2,
                    scopeSource = true,
                    scopeTitle = true,
                    scopeContent = false,
                    isEnabled = false,
                ),
                ReplaceRule(
                    4,
                    "Body",
                    order = 3,
                    scopeSource = false,
                    scopeTitle = false,
                    scopeContent = true,
                ),
            )
        }
    }

    @After
    fun cleanup() {
        database.close()
    }

    @Test
    fun actualDaoKeepsReaderDisabledRulesAndSourceEnabledScopeInSortOrder() = runBlocking {
        val original = AppConfig.manualReplaceRule
        assertEquals(listOf(3L, 4L, 1L), repository.candidates(false).map { it.id })
        val source = repository.candidates(true)
        assertEquals(listOf(2L, 1L), source.map { it.id })
        assertEquals("Mixed (Group)", source.last().name)
        assertEquals(original, AppConfig.manualReplaceRule)
    }

    @Test
    fun realCandidateLoadFiltersUnknownSelectionsAndConfirmsInDaoOrderWithoutWriting() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            lateinit var model: ManualReplacementViewModel
            instrumentation.runOnMainSync {
                model =
                    ManualReplacementViewModel(
                        repository,
                        SavedStateHandle(),
                        false,
                        listOf(1, 999, 3),
                    )
            }
            try {
                withTimeout(5000) { while (model.state.value.loading) delay(10) }
                instrumentation.runOnMainSync {
                    assertEquals(setOf(1L, 3L), model.state.value.selected)
                    model.confirm()
                    assertEquals(listOf(3L, 1L), model.consumeConfirmation())
                    assertNull(model.consumeConfirmation())
                }
                assertEquals(listOf(3L, 4L, 1L), repository.candidates(false).map { it.id })
            } finally {
                instrumentation.runOnMainSync { model.stop() }
            }
        }
}
