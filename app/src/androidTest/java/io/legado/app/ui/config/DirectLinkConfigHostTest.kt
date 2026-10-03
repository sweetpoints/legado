package io.legado.app.ui.config

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.legado.app.ui.about.AboutActivity
import org.junit.*
import org.junit.Assert.*

class DirectLinkConfigHostTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun actualDialogKeepsEditedDraftAcrossRecreationAndCancelClosesWithoutSave() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<AboutActivity>(Intent(context, AboutActivity::class.java)).use {
            scenario ->
            scenario.onActivity {
                DirectLinkUploadConfig().show(it.supportFragmentManager, "direct-config-test")
            }
            compose.waitUntil {
                var loaded = false
                scenario.onActivity {
                    loaded =
                        (it.supportFragmentManager.findFragmentByTag("direct-config-test")
                                as? DirectLinkUploadConfig)
                            ?.model
                            ?.state
                            ?.value
                            ?.loading == false
                }
                loaded
            }
            compose.onNodeWithTag("direct-link-summary").performTextReplacement("Compose draft")
            compose.waitForIdle()
            scenario.recreate()
            compose.onNodeWithTag("direct-link-summary").assertTextContains("Compose draft")
            compose.onNodeWithTag("direct-link-cancel").performClick()
            compose.waitUntil {
                var closed = false
                scenario.onActivity {
                    closed =
                        it.supportFragmentManager.findFragmentByTag("direct-config-test") == null
                }
                closed
            }
            scenario.onActivity {
                assertNull(it.supportFragmentManager.findFragmentByTag("direct-config-test"))
            }
        }
    }
}
