package io.legado.app.ui.login

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.getStoredLoginInfoMap
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class SourceLoginHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun webEntryRestoresLargeIdentityAndSmallSavedUuidWithoutLegacyFragmentShell() {
        val source =
            RssSource(
                sourceUrl = "https://login-${UUID.randomUUID()}.invalid/" + "K".repeat(200000),
                sourceName = "Owned source",
            )
        runBlocking(Dispatchers.IO) { appDb.rssSourceDao.insert(source) }
        try {
            ActivityScenario.launch<SourceLoginActivity>(
                    Intent(context, SourceLoginActivity::class.java)
                        .putExtra("type", "rssSource")
                        .putExtra("key", source.sourceUrl)
                )
                .use { scenario ->
                    compose.waitUntil(timeoutMillis = 20_000) {
                        var ready = false
                        scenario.onActivity { ready = it.hostModel.state.value.loaded }
                        ready
                    }
                    compose.onNodeWithTag("source-web-login-check").assertIsDisplayed()
                    scenario.onActivity {
                        assertEquals(source.sourceUrl, it.viewModel.source!!.getKey())
                        assertTrue(
                            it.supportFragmentManager.fragments.none { fragment ->
                                fragment is WebViewLoginFragment
                            }
                        )
                        val field =
                            SourceLoginHostViewModel::class.java.getDeclaredField("saved").apply {
                                isAccessible = true
                            }
                        val saved = field.get(it.hostModel) as SavedStateHandle
                        assertTrue(
                            saved.keys().all { key -> saved.get<Any?>(key).toString().length < 100 }
                        )
                    }
                    scenario.recreate()
                    compose.onNodeWithTag("source-web-login-check").assertIsDisplayed()
                    scenario.onActivity {
                        assertFalse(it.isFinishing)
                        assertEquals(source.sourceUrl, it.viewModel.source!!.getKey())
                    }
                }
        } finally {
            runBlocking(Dispatchers.IO) {
                source.removeLoginInfo()
                source.removeLoginHeader()
                appDb.rssSourceDao.delete(source.sourceUrl)
            }
        }
    }

    @Test
    fun formModeKeepsSingleRestoredDialogAndDoesNotSaveTypedDraftOnRotation() {
        val source =
            HttpTTS(
                id = System.nanoTime(),
                name = "Owned form",
                loginUi =
                    """[{"name":"user","type":"text"},{"name":"password","type":"password"}]""",
            )
        runBlocking(Dispatchers.IO) { appDb.httpTTSDao.insert(source) }
        try {
            ActivityScenario.launch<SourceLoginActivity>(
                    Intent(context, SourceLoginActivity::class.java)
                        .putExtra("type", "httpTts")
                        .putExtra("key", source.id.toString())
                )
                .use { scenario ->
                    compose
                        .onNodeWithTag("source-login-field:user")
                        .performTextReplacement("Private draft")
                    scenario.recreate()
                    compose
                        .onNodeWithTag("source-login-field:user")
                        .assertTextContains("Private draft")
                    scenario.onActivity {
                        assertFalse(it.isFinishing)
                        assertEquals(
                            1,
                            it.supportFragmentManager.fragments.count { fragment ->
                                fragment is SourceLoginDialog
                            },
                        )
                        assertEquals(source.getKey(), it.viewModel.source!!.getKey())
                    }
                    assertTrue(
                        runBlocking(Dispatchers.IO) {
                            source.getStoredLoginInfoMap().isNullOrEmpty()
                        }
                    )
                    compose.onNodeWithTag("source-login-close").performClick()
                    compose.waitUntil(timeoutMillis = 20_000) {
                        runBlocking(Dispatchers.IO) {
                            source.getStoredLoginInfoMap()?.get("user") == "Private draft"
                        }
                    }
                }
        } finally {
            runBlocking(Dispatchers.IO) {
                source.removeLoginInfo()
                source.removeLoginHeader()
                appDb.httpTTSDao.delete(source)
            }
        }
    }
}
