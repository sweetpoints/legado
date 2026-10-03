package io.legado.app.ui.config

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.model.cover.CoverSettingSwitch
import org.junit.*
import org.junit.Assert.*

class CoverSettingsHostTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun intent() =
        Intent(ApplicationProvider.getApplicationContext(), ConfigActivity::class.java)
            .putExtra("configTag", ConfigTag.COVER_CONFIG)

    @Test
    fun existingConfigDestinationUsesComposeAndSurvivesRecreationWithoutWritingSettings() {
        ActivityScenario.launch<ConfigActivity>(intent()).use { scenario ->
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("cover-row-rules").fetchSemanticsNodes().isNotEmpty()
            }
            val before =
                compose
                    .onNodeWithTag("cover-row-rules")
                    .fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            scenario.recreate()
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("cover-row-rules").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(
                before,
                compose
                    .onNodeWithTag("cover-row-rules")
                    .fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.Text],
            )
            scenario.onActivity {
                assertTrue(
                    it.supportFragmentManager.findFragmentByTag(ConfigTag.COVER_CONFIG)
                        is CoverConfigFragment
                )
            }
        }
    }

    @Test
    fun sharedConfigSearchFindsAndPositionsNightSettingWithoutChangingPreferences() {
        ActivityScenario.launch<ConfigActivity>(intent()).use { scenario ->
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("cover-row-rules").fetchSemanticsNodes().isNotEmpty()
            }
            val night =
                InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.night)
            var completed = 0
            scenario.onActivity {
                val fragment = it.supportFragmentManager.findFragmentByTag(ConfigTag.COVER_CONFIG)
                assertTrue(fragment is ConfigSearchPage)
                (fragment as ConfigSearchPage).searchSettings(night) { completed++ }
            }
            compose
                .onNodeWithTag("cover-search-${CoverSettingSwitch.NightAuthor.key}")
                .performScrollTo()
                .performClick()
            compose
                .onNodeWithTag("cover-row-${CoverSettingSwitch.NightAuthor.key}")
                .assertIsDisplayed()
            assertEquals(1, completed)
        }
    }
}
