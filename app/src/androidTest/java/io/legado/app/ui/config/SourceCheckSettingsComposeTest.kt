package io.legado.app.ui.config

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import org.junit.*
import org.junit.Assert.*

class SourceCheckSettingsComposeTest {
    @get:Rule val compose = createComposeRule()

    private class Repository : SourceCheckSettingsRepository {
        val saves = mutableListOf<SourceCheckSettings>()

        override suspend fun load() =
            SourceCheckSettings(180000, true, false, true, true, true, true, true)

        override suspend fun save(settings: SourceCheckSettings) {
            saves += settings
        }
    }

    @Test
    fun realRouteChangesDependenciesAndSaveDeliversOnceAfterValidSeconds() {
        val repo = Repository()
        val model = SourceCheckSettingsViewModel(repo, SavedStateHandle())
        var closed = 0
        try {
            compose.setContent {
                MaterialTheme { SourceCheckSettingsRoute(model, { true }, { closed++ }, {}) }
            }
            compose.waitUntil { !model.state.value.loading }
            compose.onNodeWithTag("source-check-Info").performClick()
            compose.onNodeWithTag("source-check-Category").assertIsNotEnabled().assertIsOff()
            compose.onNodeWithTag("source-check-Content").assertIsNotEnabled().assertIsOff()
            compose.onNodeWithTag("source-check-seconds").performTextReplacement("12")
            compose.onNodeWithTag("source-check-save").performClick()
            compose.waitUntil { closed == 1 }
            compose.waitForIdle()
            assertEquals(1, repo.saves.size)
            assertEquals(12000L, repo.saves.single().timeout)
            assertFalse(repo.saves.single().info)
            assertFalse(repo.saves.single().content)
            assertEquals(1, closed)
        } finally {
            compose.runOnIdle { model.stop() }
        }
    }

    @Test
    fun invalidInputRemainsEditableAndCancelNeverWritesInDarkTheme() {
        val repo = Repository()
        val model = SourceCheckSettingsViewModel(repo, SavedStateHandle())
        var closed = 0
        try {
            compose.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    SourceCheckSettingsRoute(model, { true }, { closed++ }, {})
                }
            }
            compose.waitUntil { !model.state.value.loading }
            compose
                .onNodeWithTag("source-check-seconds")
                .performTextReplacement("9223372036854775807")
            compose.onNodeWithTag("source-check-save").performClick()
            compose.waitForIdle()
            assertEquals(SourceCheckTimeoutIssue.Invalid, model.state.value.timeoutIssue)
            assertTrue(repo.saves.isEmpty())
            compose
                .onNodeWithTag("source-check-seconds")
                .assertIsEnabled()
                .performTextReplacement("30")
            compose.onNodeWithTag("source-check-cancel").performClick()
            assertEquals(1, closed)
            assertTrue(repo.saves.isEmpty())
        } finally {
            compose.runOnIdle { model.stop() }
        }
    }
}
