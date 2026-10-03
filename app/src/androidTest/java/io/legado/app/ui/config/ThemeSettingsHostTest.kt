package io.legado.app.ui.config

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import org.junit.*
import org.junit.Assert.*

class ThemeSettingsHostTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun intent() =
        Intent(ApplicationProvider.getApplicationContext(), ConfigActivity::class.java)
            .putExtra("configTag", ConfigTag.THEME_CONFIG)

    @Test
    fun actualConfigDestinationContainsComposeSettingsAndRestoresOpenFontDraftOnRecreation() {
        ActivityScenario.launch<ConfigActivity>(intent()).use { scenario ->
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("theme-row-font").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("theme-row-font").performClick()
            compose.onNodeWithTag("theme-number-input").performTextReplacement("15")
            scenario.recreate()
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("theme-number-input").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("theme-number-input").assertTextEquals("15")
            // Cancel the draft through the dialog's outside/back path; no shared preference is
            // changed by this test.
            pressBack()
            compose.onNodeWithTag("theme-number-input").assertDoesNotExist()
        }
    }

    @Test
    fun sharedConfigSearchInterfaceShowsComposeCategoryResultsAndPositionsChosenNightSetting() {
        ActivityScenario.launch<ConfigActivity>(intent()).use { scenario ->
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("theme-settings-list").fetchSemanticsNodes().isNotEmpty()
            }
            val night =
                InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.night)
            var completed = 0
            scenario.onActivity { activity ->
                val fragment =
                    activity.supportFragmentManager.findFragmentByTag(ConfigTag.THEME_CONFIG)
                assertTrue(fragment is ConfigSearchPage)
                (fragment as ConfigSearchPage).searchSettings(night) { completed++ }
            }
            compose.onNodeWithTag("theme-search-save-night").performScrollTo().performClick()
            compose.onNodeWithTag("theme-row-save-night").assertIsDisplayed()
            assertEquals(1, completed)
        }
    }
}
