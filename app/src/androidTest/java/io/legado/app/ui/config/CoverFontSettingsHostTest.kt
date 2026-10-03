package io.legado.app.ui.config

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import org.junit.*
import org.junit.Assert.*

class CoverFontSettingsHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun intent() = Intent(ApplicationProvider.getApplicationContext(), ConfigActivity::class.java).putExtra("configTag", ConfigTag.COVER_FONT_CONFIG)
    @Test fun actualConfigDestinationHostsBothComposePreviewsAfterRecreation() {
        ActivityScenario.launch<ConfigActivity>(intent()).use { scenario ->
            compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithTag("cover-font-settings-list").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("cover-font-settings-list").performScrollToNode(hasTestTag("cover-font-row-coverPreview"))
            compose.onNodeWithTag("cover-font-preview-short").assertExists(); compose.onNodeWithTag("cover-font-preview-long").assertExists()
            scenario.recreate()
            compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithTag("cover-font-settings-list").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { val fragment = it.supportFragmentManager.findFragmentByTag(ConfigTag.COVER_FONT_CONFIG)
                assertTrue(fragment is CoverFontConfigFragment); assertFalse((fragment as CoverFontConfigFragment).selectSystemTypefaceOnDefault) }
        }
    }
    @Test fun sharedConfigSearchInterfaceShowsComposeCategoryResultsAndPositionsChosenNightSetting() {
        ActivityScenario.launch<ConfigActivity>(intent()).use { scenario ->
            compose.waitUntil(timeoutMillis=10000) { compose.onAllNodesWithTag("cover-font-settings-list").fetchSemanticsNodes().isNotEmpty() }
            val night = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cover_author_small_size); var completed = 0
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentByTag(ConfigTag.COVER_FONT_CONFIG)
                assertTrue(fragment is ConfigSearchPage); (fragment as ConfigSearchPage).searchSettings(night) { completed++ }
            }
            compose.onNodeWithTag("cover-font-search-coverAuthorSmallSize").performScrollTo().performClick()
            compose.onNodeWithTag("cover-font-row-coverAuthorSmallSize").assertIsDisplayed(); assertEquals(1, completed)
        }
    }
}
