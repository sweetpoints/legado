package io.legado.app.ui.book.read

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EffectiveReplacementScreenTest {
    @get:Rule val compose = createComposeRule()

    private class Fake : EffectiveReplacementRepository {
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        val disabled = mutableListOf<Long>()
        val modes = mutableListOf<Int>()

        override suspend fun load(
            sourceIds: List<Long>?,
            readerRows: List<EffectiveReplacementRow>,
        ) =
            EffectiveReplacementSnapshot(
                listOf(EffectiveReplacementRow(1, "Same"), EffectiveReplacementRow(2, "Same")),
                1,
            )

        override suspend fun disable(id: Long) {
            gate?.await()
            if (fail) error("database failed")
            disabled += id
        }

        override suspend fun conversion(mode: Int) {
            modes += mode
        }
    }

    private fun show(
        repo: Fake,
        saved: SavedStateHandle = SavedStateHandle(),
        source: List<Long>? = null,
        edit: (Long) -> Unit = {},
        refresh: () -> Unit = {},
        close: () -> Unit = {},
    ): EffectiveReplacementViewModel {
        val model = EffectiveReplacementViewModel(repo, saved, source, emptyList(), "Conversion")
        compose.setContent {
            LegadoComposeTheme { EffectiveReplacementRoute(model, { true }, edit, refresh, close) }
        }
        compose.waitUntil { !model.state.value.loading }
        return model
    }

    @Test
    fun duplicateNamesEditExactIdAndUnchangedCloseNeverRefreshes() {
        var opened: Long? = null
        var closes = 0
        var refreshes = 0
        val repo = Fake()
        show(repo, edit = { opened = it }, refresh = { refreshes++ }, close = { closes++ })
        compose.onNodeWithTag("effective-open-rule:2").performClick()
        compose.waitUntil { opened != null }
        compose.runOnIdle { assertEquals(2L, opened) }
        compose.onNodeWithTag("effective-close").performClick()
        compose.waitUntil { closes > 0 }
        compose.runOnIdle {
            assertEquals(1, closes)
            assertEquals(0, refreshes)
            assertTrue(repo.disabled.isEmpty())
        }
    }

    @Test
    fun failedDisableKeepsRowAndRetryThenCloseRefreshesOnce() {
        val repo = Fake().apply { fail = true }
        var closes = 0
        var refreshes = 0
        show(repo, refresh = { refreshes++ }, close = { closes++ })
        compose.onNodeWithTag("effective-remove-rule:1").performClick()
        compose.onNodeWithText("database failed").assertIsDisplayed()
        compose.onNodeWithTag("effective-open-rule:1").assertExists()
        compose.runOnIdle { repo.fail = false }
        compose.onNodeWithTag("effective-remove-rule:1").performClick()
        compose.onNodeWithTag("effective-open-rule:1").assertDoesNotExist()
        compose.onNodeWithTag("effective-close").performClick()
        compose.waitUntil { closes > 0 }
        compose.runOnIdle {
            assertEquals(listOf(1L), repo.disabled)
            assertEquals(1, refreshes)
            assertEquals(1, closes)
        }
    }

    @Test
    fun pendingDisableBlocksCloseAndOtherRowsUntilCommit() {
        val repo = Fake().apply { gate = CompletableDeferred() }
        var closes = 0
        val model = show(repo, close = { closes++ })
        compose.onNodeWithTag("effective-remove-rule:2").performClick()
        compose.onNodeWithTag("effective-close").assertIsNotEnabled()
        compose.runOnIdle {
            model.close()
            assertEquals(0, closes)
            repo.gate!!.complete(Unit)
        }
        compose.waitUntil { !model.state.value.busy }
        compose.onNodeWithTag("effective-close").performClick()
        compose.waitUntil { closes > 0 }
        compose.runOnIdle { assertEquals(listOf(2L), repo.disabled) }
    }

    @Test
    fun conversionPickerAndSyntheticRemovalNeverDisableDatabaseRows() {
        val repo = Fake()
        show(repo)
        compose.onNodeWithTag("effective-open-conversion").performClick()
        compose.onNodeWithTag("effective-conversion-2").performClick()
        compose.onNodeWithTag("effective-remove-conversion").performClick()
        compose.onNodeWithTag("effective-open-conversion").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(2, 0), repo.modes)
            assertTrue(repo.disabled.isEmpty())
        }
    }

    @Test
    fun sourceModeHasNoConversionAndFinishedRestoreConsumesPendingRefreshOnce() {
        val repo = Fake()
        var refreshes = 0
        var closes = 0
        show(
            repo,
            SavedStateHandle(mapOf("effective.finished" to true, "effective.refresh" to true)),
            source = listOf(1),
            refresh = { refreshes++ },
            close = { closes++ },
        )
        compose.waitUntil { closes > 0 }
        compose.onNodeWithTag("effective-open-conversion").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, refreshes)
            assertEquals(1, closes)
            assertTrue(repo.disabled.isEmpty())
            assertTrue(repo.modes.isEmpty())
        }
    }
}
