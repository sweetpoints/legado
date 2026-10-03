package io.legado.app.ui.config

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.legado.app.R
import java.io.File
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class ConfigSearchHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun composeChromeRestoresPrivateLargeQuerySelectionAndExistingPageAcrossRecreate() =
        runBlocking {
            var session: String? = null
            val text = "owned-query-" + "large".repeat(220_000)
            try {
                ActivityScenario.launch<ConfigActivity>(
                        Intent(context, ConfigActivity::class.java)
                            .putExtra("configTag", ConfigTag.THEME_CONFIG)
                    )
                    .use { scenario ->
                        compose.waitUntil(timeoutMillis = 10000) {
                            var loaded = false
                            scenario.onActivity {
                                loaded =
                                    it.searchModel.state.value.editable &&
                                        it.supportFragmentManager
                                            .findFragmentByTag(ConfigTag.THEME_CONFIG)
                                            ?.view != null
                            }
                            loaded
                        }
                        scenario.onActivity {
                            session = it.searchModel.id
                            it.searchModel.searching(true)
                            it.searchModel.text(text, 2, 7)
                        }
                        compose.waitUntil(timeoutMillis = 10000) {
                            var same = false
                            scenario.onActivity {
                                same = it.searchModel.state.value.draft.text == text
                            }
                            same
                        }
                        scenario.recreate()
                        compose.waitUntil(timeoutMillis = 10000) {
                            var loaded = false
                            scenario.onActivity { loaded = it.searchModel.state.value.editable }
                            loaded
                        }
                        scenario.onActivity {
                            val draft = it.searchModel.state.value.draft
                            assertEquals(session, it.searchModel.id)
                            assertEquals(text, draft.text)
                            assertEquals(2, draft.start)
                            assertEquals(7, draft.end)
                            assertTrue(draft.searching)
                            assertEquals(
                                1,
                                it.supportFragmentManager.fragments
                                    .filterIsInstance<ThemeConfigFragment>()
                                    .size,
                            )
                            assertFalse(it.intent.hasExtra("query"))
                        }
                        compose.onNodeWithTag("config-back").performClick()
                        compose
                            .onNodeWithTag("config-title")
                            .assertTextEquals(context.getString(R.string.theme_setting))
                        scenario.onActivity {
                            assertFalse(it.searchModel.state.value.draft.searching)
                            it.finish()
                        }
                    }
                withTimeout(10000) {
                    while (
                        withContext(Dispatchers.IO) {
                            File(context.filesDir, "config-search/$session.json").exists()
                        }
                    ) delay(10)
                }
            } finally {
                withContext(Dispatchers.IO) {
                    session?.let { id ->
                        listOf(
                                "json",
                                "json.bak",
                                "json.new",
                                "json.closed",
                                "json.closed.bak",
                                "json.closed.new",
                            )
                            .forEach { File(context.filesDir, "config-search/$id.$it").delete() }
                    }
                }
            }
        }
}
