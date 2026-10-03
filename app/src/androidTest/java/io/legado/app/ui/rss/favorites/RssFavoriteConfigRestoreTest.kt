package io.legado.app.ui.rss.favorites

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssStar
import io.legado.app.data.repository.FileRssFavoriteConfigRepository
import io.legado.app.ui.about.AboutActivity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class RssFavoriteConfigRestoreTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ids = mutableListOf<String>()

    @After
    fun cleanup() {
        ids.forEach { File(context.filesDir, "rss-favorite-config/$it.json").delete() }
    }

    private fun await(scenario: ActivityScenario<AboutActivity>) {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            var ready = false
            scenario.onActivity { activity ->
                ready =
                    (activity.supportFragmentManager.findFragmentByTag("favorite-test")
                            as? RssFavoritesDialog)
                        ?.model
                        ?.state
                        ?.value
                        ?.loaded == true
            }
            if (ready) return
            SystemClock.sleep(25)
        }
        throw AssertionError("RSS favorite config did not restore")
    }

    @Test
    fun articleConstructorStoresSmallIdAndRealRecreationKeepsDraftSelectionAndGroupFocus() {
        val article = RssArticle(title = "Large ".repeat(20000), group = "Original group")
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = RssFavoritesDialog(article)
                assertEquals(setOf("requestId"), dialog.requireArguments().keySet())
                ids += dialog.requireArguments().getString("requestId")!!
                assertTrue(dialog.requireArguments().toString().length < 500)
                dialog.showNow(activity.supportFragmentManager, "favorite-test")
            }
            await(scenario)
            compose
                .onNodeWithTag("favorite-config-title")
                .performScrollTo()
                .performTextReplacement("Draft title")
            compose
                .onNodeWithTag("favorite-config-group")
                .performScrollTo()
                .performTextReplacement("Draft group")
            compose
                .onNodeWithTag("favorite-config-group")
                .performTextInputSelection(TextRange(2, 5))
            scenario.recreate()
            await(scenario)
            compose.onNodeWithTag("favorite-config-title").assertTextContains("Draft title")
            compose
                .onNodeWithTag("favorite-config-group")
                .assertTextContains("Draft group")
                .assertIsFocused()
            scenario.onActivity { activity ->
                val dialog =
                    activity.supportFragmentManager.findFragmentByTag("favorite-test")
                        as RssFavoritesDialog
                assertEquals(ids.single(), dialog.requireArguments().getString("requestId"))
                assertEquals(2, dialog.model.state.value.groupStart)
                assertEquals(5, dialog.model.state.value.groupEnd)
                assertFalse(activity.isFinishing)
            }
            val disk = runBlocking { FileRssFavoriteConfigRepository(context).load(ids.single()) }
            assertEquals("Draft title", disk.title)
            assertEquals("Draft group", disk.group)
            compose.onNodeWithTag("favorite-config-cancel").performClick()
        }
    }

    @Test
    fun starConstructorAndLegacyArgumentsPreserveOriginalNullableValuesAndPublicCallbackContract() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = RssFavoritesDialog(RssStar(title = "Star title", group = "Star group"))
                ids += dialog.requireArguments().getString("requestId")!!
                assertNull(dialog.callback)
                dialog.showNow(activity.supportFragmentManager, "favorite-test")
            }
            await(scenario)
            compose.onNodeWithTag("favorite-config-title").assertTextContains("Star title")
            compose.onNodeWithTag("favorite-config-group").assertTextContains("Star group")
            scenario.onActivity { activity ->
                (activity.supportFragmentManager.findFragmentByTag("favorite-test")
                        as RssFavoritesDialog)
                    .dismissNow()
                val legacy =
                    RssFavoritesDialog().apply {
                        arguments =
                            android.os.Bundle().apply {
                                putString("title", null)
                                putString("group", "Legacy group")
                            }
                    }
                legacy.showNow(activity.supportFragmentManager, "favorite-test")
                ids += legacy.requireArguments().getString("requestId")!!
            }
            await(scenario)
            compose.onNodeWithTag("favorite-config-title").assertTextContains("")
            compose.onNodeWithTag("favorite-config-group").assertTextContains("Legacy group")
            val loaded = runBlocking { FileRssFavoriteConfigRepository(context).load(ids.last()) }
            assertNull(loaded.originalTitle)
            assertEquals("Legacy group", loaded.originalGroup)
        }
    }
}
