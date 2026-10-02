package io.legado.app.ui.widget.dialog

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.fragment.app.Fragment
import androidx.test.core.app.ActivityScenario
import io.legado.app.ui.about.AboutActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CodeDialogHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun publicConstructorDraftRecreationAndParentSavePublishOriginalBeforeClosing() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity {
                val host = Host(); it.supportFragmentManager.beginTransaction().add(host, "code-host").commitNow()
                CodeDialog("original", false, "request", "derived", false, false).show(host.childFragmentManager, "code")
            }
            awaitBody(); compose.onNodeWithTag("code-body").performTextReplacement("edited")
            scenario.recreate(); awaitBody(); compose.onNodeWithTag("code-body").assertTextEquals("edited")
            compose.onNodeWithTag("code-preview-toggle").performClick().assertIsOn()
            compose.onNodeWithTag("code-save").assertDoesNotExist()
            compose.onNodeWithTag("code-preview-toggle").performClick().assertIsOff()
            compose.onNodeWithTag("code-save").performClick()
            compose.waitUntil { var saved = false; scenario.onActivity { saved = (it.supportFragmentManager.findFragmentByTag("code-host") as Host).saved != null }; saved }
            scenario.onActivity {
                val host = it.supportFragmentManager.findFragmentByTag("code-host") as Host
                assertEquals("edited", host.saved); assertEquals("request", host.request); assertTrue(host.attachedDuringSave)
                host.childFragmentManager.executePendingTransactions(); assertNull(host.childFragmentManager.findFragmentByTag("code"))
            }
        }
    }
    @Test fun defaultReadonlyAllowsSelectionAndCloseWithoutPublishing() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity {
                val host = Host(); it.supportFragmentManager.beginTransaction().add(host, "code-host").commitNow()
                CodeDialog("read only").show(host.childFragmentManager, "code")
            }
            awaitBody(); compose.onNodeWithTag("code-save").assertDoesNotExist(); compose.onNodeWithTag("code-fullscreen").assertDoesNotExist()
            compose.onNodeWithTag("code-body").performTextInputSelection(androidx.compose.ui.text.TextRange(0, 4))
            compose.onNodeWithTag("code-close").performClick()
            scenario.onActivity { assertNull((it.supportFragmentManager.findFragmentByTag("code-host") as Host).saved) }
        }
    }
    private fun awaitBody() = compose.waitUntil {
        compose.onAllNodesWithTag("code-body").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
    }
    class Host : Fragment(), CodeDialog.Callback {
        var saved: String? = null; var request: String? = null; var attachedDuringSave = false
        override fun onCodeSave(code: String, requestId: String?) {
            attachedDuringSave = childFragmentManager.findFragmentByTag("code")?.isAdded == true
            saved = code; request = requestId
        }
    }
}
