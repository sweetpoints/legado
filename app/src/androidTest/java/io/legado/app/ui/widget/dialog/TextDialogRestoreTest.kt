package io.legado.app.ui.widget.dialog

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.about.AboutActivity
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TextDialogRestoreTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val requests = mutableListOf<String>()

    @After
    fun cleanup() {
        requests.forEach { id -> File(context.filesDir, "text-dialog-requests/$id.json").delete() }
    }

    @Test
    fun constructorStoresOnlyRequestIdAndFragmentRecreationLoadsFullSourceFromDisk() {
        val content = "Long text ".repeat(10000)
        var id = ""
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = TextDialog("Large log", content)
                assertEquals(setOf("requestId"), dialog.requireArguments().keySet())
                id = dialog.requireArguments().getString("requestId")!!
                requests += id
                assertTrue(id.length < 100)
                dialog.showNow(activity.supportFragmentManager, "text-test")
            }
            await(scenario) { !it.state.value.loading }
            scenario.recreate()
            await(scenario) { !it.state.value.loading }
            scenario.onActivity { activity ->
                val dialog =
                    activity.supportFragmentManager.findFragmentByTag("text-test") as TextDialog
                val model = ViewModelProvider(dialog)[TextDialogViewModel::class.java]
                assertEquals(content, model.state.value.request!!.content)
                assertEquals(id, dialog.requireArguments().getString("requestId"))
                assertTrue(model.state.value.document.text.length < content.length)
            }
            compose.onNodeWithTag("text-title").assertTextEquals("Large log")
        }
    }

    @Test
    fun restoredHelpKeepsSearchMatchAndTheOriginalCountdownDeadline() {
        var id = ""
        var before = 0L
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog =
                    TextDialog(
                        "Help",
                        "## One\nAlpha Alpha\n## Two\nBeta",
                        TextDialog.Mode.MD,
                        30000,
                        false,
                        true,
                    )
                id = dialog.requireArguments().getString("requestId")!!
                requests += id
                dialog.showNow(activity.supportFragmentManager, "text-test")
            }
            await(scenario) { !it.state.value.loading }
            compose.onNodeWithTag("text-menu").performClick()
            compose.onNodeWithTag("text-menu-search").performClick()
            compose.onNodeWithTag("text-search-input").performTextReplacement("Alpha")
            await(scenario) { !it.state.value.searching && it.state.value.matches.size == 2 }
            compose.onNodeWithTag("text-search-next").performClick()
            scenario.onActivity { activity ->
                before =
                    ViewModelProvider(
                            activity.supportFragmentManager.findFragmentByTag("text-test")!!
                        )[TextDialogViewModel::class.java]
                        .state
                        .value
                        .remaining
            }
            SystemClock.sleep(200)
            scenario.recreate()
            await(scenario) { !it.state.value.loading && !it.state.value.searching }
            compose.onNodeWithTag("text-search-input").assertTextEquals("Alpha")
            compose.onNodeWithTag("text-search-count").assertTextEquals("2/2")
            scenario.onActivity { activity ->
                val model =
                    ViewModelProvider(
                        activity.supportFragmentManager.findFragmentByTag("text-test")!!
                    )[TextDialogViewModel::class.java]
                assertTrue(model.state.value.help)
                assertTrue(model.state.value.remaining <= before)
                assertFalse(model.state.value.canCancel)
            }
        }
    }

    private fun await(
        scenario: ActivityScenario<AboutActivity>,
        condition: (TextDialogViewModel) -> Boolean,
    ) {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            var ready = false
            scenario.onActivity { activity ->
                val dialog =
                    activity.supportFragmentManager.findFragmentByTag("text-test") as? TextDialog
                if (dialog != null)
                    ready = condition(ViewModelProvider(dialog)[TextDialogViewModel::class.java])
            }
            if (ready) return
            SystemClock.sleep(25)
        }
        throw AssertionError("Text dialog did not restore")
    }
}
