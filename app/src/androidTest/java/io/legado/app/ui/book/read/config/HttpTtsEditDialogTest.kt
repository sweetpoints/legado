package io.legado.app.ui.book.read.config

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.ui.about.AboutActivity
import org.junit.Rule
import org.junit.Test

class HttpTtsEditDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun unconfirmedDraftSurvivesActivityRecreationAndBackAsksBeforeDiscard() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { HttpTtsEditDialog().show(it.supportFragmentManager, "http-editor") }
            compose.onNodeWithTag("http-tts-field:Name").performTextReplacement("Unconfirmed engine")
            compose.onNodeWithTag("http-tts-field:JsLib").performScrollTo().performTextReplacement("function sign() {}")
            compose.onNodeWithTag("http-tts-cookie").performScrollTo().performClick()
            scenario.recreate()
            compose.onNodeWithTag("http-tts-field:Name").performScrollTo().assertTextContains("Unconfirmed engine")
            compose.onNodeWithTag("http-tts-field:JsLib").performScrollTo().assertTextContains("function sign() {}")
            compose.onNodeWithTag("http-tts-cookie").performScrollTo().assertIsOn()
            scenario.onActivity {
                @Suppress("DEPRECATION")
                (it.supportFragmentManager.findFragmentByTag("http-editor") as HttpTtsEditDialog).dialog!!.onBackPressed()
            }
            compose.onNodeWithText(context.getString(R.string.exit_no_save)).assertExists()
            compose.onNodeWithText(context.getString(R.string.yes)).performClick()
            compose.onNodeWithTag("http-tts-save").assertExists()
            compose.onNodeWithTag("http-tts-back").performClick()
            compose.onNodeWithText(context.getString(R.string.no)).performClick()
            compose.onNodeWithTag("http-tts-save").assertDoesNotExist()
        }
    }
}
