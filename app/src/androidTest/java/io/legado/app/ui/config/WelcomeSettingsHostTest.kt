package io.legado.app.ui.config

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.model.welcome.WelcomeSwitch
import org.junit.*
import org.junit.Assert.*

class WelcomeSettingsHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun intent() = Intent(ApplicationProvider.getApplicationContext(), ConfigActivity::class.java).putExtra("configTag", ConfigTag.WELCOME_CONFIG)
    @Test fun existingConfigDestinationUsesComposeAndSurvivesRecreationWithoutWritingSettings() {
        ActivityScenario.launch<ConfigActivity>(intent()).use { scenario ->
            compose.waitUntil(10000) { compose.onAllNodesWithTag("welcome-time-value").fetchSemanticsNodes().isNotEmpty() }
            val before = compose.onNodeWithTag("welcome-time-value").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            scenario.recreate()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("welcome-time-value").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(before, compose.onNodeWithTag("welcome-time-value").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text])
            scenario.onActivity { assertTrue(it.supportFragmentManager.findFragmentByTag(ConfigTag.WELCOME_CONFIG) is WelcomeConfigFragment) }
        }
    }
    @Test fun sharedConfigSearchFindsAndPositionsNightSettingWithoutChangingPreferences() {
        ActivityScenario.launch<ConfigActivity>(intent()).use { scenario ->
            compose.waitUntil(10000) { compose.onAllNodesWithTag("welcome-time-value").fetchSemanticsNodes().isNotEmpty() }
            val night = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.night); var completed = 0
            scenario.onActivity { val fragment = it.supportFragmentManager.findFragmentByTag(ConfigTag.WELCOME_CONFIG)
                assertTrue(fragment is ConfigSearchPage); (fragment as ConfigSearchPage).searchSettings(night) { completed++ } }
            compose.onNodeWithTag("welcome-search-${WelcomeSwitch.NightIcon.key}").performScrollTo().performClick()
            compose.onNodeWithTag("welcome-row-${WelcomeSwitch.NightIcon.key}").assertIsDisplayed(); assertEquals(1, completed)
        }
    }
}
