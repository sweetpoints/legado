package io.legado.app.ui.rss.favorites

import android.os.SystemClock
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import io.legado.app.ui.flutter.FlutterUiTestDriver
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
    private val driver = FlutterUiTestDriver(InstrumentationRegistry.getInstrumentation())
    @Before fun setup() { driver.enableAccessibility() }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ids = mutableListOf<String>()

    @After
    fun cleanup() {
        driver.close()
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
            driver.setText("favorite-config-title", "Draft title")
            driver.setText("favorite-config-group", "Draft group")
            val node = requireNotNull(driver.node("favorite-config-group"))
            try {
                assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 2)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 5)
                }))
            } finally { node.recycle() }
            driver.await("Native private selection and group focus acknowledgement") {
                var accepted = false
                scenario.onActivity { activity ->
                    val model = (activity.supportFragmentManager.findFragmentByTag("favorite-test") as RssFavoritesDialog).model
                    accepted = model.state.value.let { it.group == "Draft group" && it.groupStart == 2 && it.groupEnd == 5 && it.focus == "group" }
                }
                accepted
            }
            scenario.recreate()
            await(scenario)
            driver.await("Actual Flutter draft and group focus restored") {
                val group = driver.node("favorite-config-group")
                val focused = group?.isFocused == true
                group?.recycle()
                driver.text("favorite-config-title") == "Draft title" &&
                    driver.text("favorite-config-group") == "Draft group" && focused
            }
            scenario.onActivity { activity ->
                val dialog =
                    activity.supportFragmentManager.findFragmentByTag("favorite-test")
                        as RssFavoritesDialog
                assertEquals(ids.single(), dialog.requireArguments().getString("requestId"))
                assertTrue(dialog.childFragmentManager.fragments.any { it is RssFavoriteConfigFlutterFragment })
                assertEquals(2, dialog.model.state.value.groupStart)
                assertEquals(5, dialog.model.state.value.groupEnd)
                assertFalse(activity.isFinishing)
            }
            val disk = runBlocking { FileRssFavoriteConfigRepository(context).load(ids.single()) }
            assertEquals("Draft title", disk.title)
            assertEquals("Draft group", disk.group)
            driver.click("favorite-config-cancel")
        }
    }

    @Test
    fun starConstructorAndLegacyArgumentsPreserveOriginalNullableValuesAndPublicCallbackContract() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            fun awaitRendered(title: String, group: String) {
                var rendered = "fieldsNotQueried"
                try {
                    // The initial Flutter page exposes empty disabled fields before its
                    // asynchronous native snapshot arrives. Identifier presence alone is
                    // not a loaded form. Keep the original identifier wait's 30s budget,
                    // requiring both actual values; the nullable title cannot pass alone.
                    driver.await("Actual Flutter favorite title and group from native snapshot") {
                        val titleNode = driver.node("favorite-config-title")
                        val groupNode = driver.node("favorite-config-group")
                        try {
                            val titleMatches = titleNode?.text?.toString().orEmpty() == title
                            val groupMatches = groupNode?.text?.toString().orEmpty() == group
                            val editable = titleNode?.isEnabled == true && titleNode.isEditable &&
                                groupNode?.isEnabled == true && groupNode.isEditable
                            rendered = "titlePresent=${titleNode != null},groupPresent=${groupNode != null}," +
                                "titleMatches=$titleMatches,groupMatches=$groupMatches,editable=$editable"
                            titleMatches && groupMatches && editable
                        } finally { titleNode?.recycle(); groupNode?.recycle() }
                    }
                } catch (failure: AssertionError) {
                    var native = "dialogMissing"
                    scenario.onActivity { activity ->
                        val model = (activity.supportFragmentManager.findFragmentByTag("favorite-test")
                            as? RssFavoritesDialog)?.model
                        model?.state?.value?.let {
                            native = "loaded=${it.loaded},loading=${it.loading},busy=${it.busy}," +
                                "canEdit=${it.canEdit},finished=${it.finished},error=${it.error != null}," +
                                "titleMatches=${it.title == title},groupMatches=${it.group == group}"
                        }
                    }
                    throw AssertionError("${failure.message}; native={$native}; rendered={$rendered}", failure)
                }
            }
            scenario.onActivity { activity ->
                val dialog = RssFavoritesDialog(RssStar(title = "Star title", group = "Star group"))
                ids += dialog.requireArguments().getString("requestId")!!
                assertNull(dialog.callback)
                dialog.showNow(activity.supportFragmentManager, "favorite-test")
            }
            await(scenario)
            awaitRendered("Star title", "Star group")
            assertEquals("Star title", driver.text("favorite-config-title"))
            assertEquals("Star group", driver.text("favorite-config-group"))
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
            awaitRendered("", "Legacy group")
            assertEquals("", driver.text("favorite-config-title"))
            assertEquals("Legacy group", driver.text("favorite-config-group"))
            val loaded = runBlocking { FileRssFavoriteConfigRepository(context).load(ids.last()) }
            assertNull(loaded.originalTitle)
            assertEquals("Legacy group", loaded.originalGroup)
        }
    }
}
