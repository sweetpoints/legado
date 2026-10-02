package io.legado.app.ui.book.read

import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.fragment.app.Fragment
import androidx.test.core.app.ActivityScenario
import io.legado.app.help.HighlightStyle
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HighlightStyleDialogComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun realBottomSheetKeepsParentHostColorContractAndRestoresLiveStyleAfterRotation() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val host = Host(); activity.supportFragmentManager.beginTransaction().add(host, "style-host").commitNow()
                HighlightStyleDialog().show(host.childFragmentManager, "highlight-style")
            }
            compose.waitUntil { compose.onAllNodesWithTag("highlight-style-toggle-Bold").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("highlight-style-toggle-Bold").performScrollTo().performClick()
            scenario.onActivity { assertTrue((it.supportFragmentManager.findFragmentByTag("style-host") as Host).style.bold) }
            compose.onNodeWithTag("highlight-style-color-Fill").performScrollTo().performClick()
            scenario.onActivity {
                val host = it.supportFragmentManager.findFragmentByTag("style-host") as Host
                assertEquals(HighlightStyleDialog.HL_FILL, host.colorId); assertEquals(7, host.initialColor); assertTrue(host.alpha)
                host.style = host.style.copy(fill = 99)
                (host.childFragmentManager.findFragmentByTag("highlight-style") as HighlightStyleDialog).refresh()
            }
            scenario.recreate()
            compose.waitUntil { compose.onAllNodesWithTag("highlight-style-toggle-Bold").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("highlight-style-toggle-Bold").performScrollTo().assertIsOn()
            scenario.onActivity {
                val host = it.supportFragmentManager.findFragmentByTag("style-host") as Host
                assertEquals(99, host.style.fill); assertTrue(host.style.bold)
                val dialog = host.childFragmentManager.findFragmentByTag("highlight-style") as HighlightStyleDialog
                assertFalse(dialog.selectSystemTypefaceOnDefault)
            }
        }
    }
    class Host : Fragment(), HighlightStyleDialog.StyleHost {
        var style = HighlightStyle(fill = 7); var colorId = -1; var initialColor = 0; var alpha = false
        override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
            savedInstanceState?.getString("style")?.let { style = GSON.fromJsonObject<HighlightStyle>(it).getOrNull() ?: style } }
        override fun onSaveInstanceState(outState: Bundle) { outState.putString("style", GSON.toJson(style)); super.onSaveInstanceState(outState) }
        override fun currentHighlightStyle() = style
        override fun onHighlightStyleChanged(style: HighlightStyle) { this.style = style }
        override fun pickHighlightColor(dialogId: Int, initial: Int, withAlpha: Boolean) { colorId = dialogId; initialColor = initial; alpha = withAlpha }
    }
}
