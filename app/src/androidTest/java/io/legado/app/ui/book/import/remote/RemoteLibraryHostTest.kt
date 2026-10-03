package io.legado.app.ui.book.import.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.legado.app.help.config.AppConfig
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteLibraryHostTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun actualMissingStorageHostRestoresSamePrivateSessionAndNativeCancelClosesWithoutLaunchingWebDav() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val previous = withContext(Dispatchers.IO) { AppConfig.defaultBookTreeUri }
            var session: String? = null
            withContext(Dispatchers.IO) { AppConfig.defaultBookTreeUri = null }
            try {
                ActivityScenario.launchActivityForResult<RemoteBookActivity>(
                        Intent(context, RemoteBookActivity::class.java)
                    )
                    .use { scenario ->
                        compose.waitUntil(timeoutMillis = 10000) {
                            compose
                                .onAllNodesWithTag("remote-library-confirm")
                                .fetchSemanticsNodes()
                                .isNotEmpty()
                        }
                        scenario.onActivity {
                            session = it.model.session
                            assertNull(it.model.state.value.connection)
                            assertFalse(it.model.state.value.loading)
                        }
                        scenario.recreate()
                        compose.waitUntil(timeoutMillis = 10000) {
                            compose
                                .onAllNodesWithTag("remote-library-confirm")
                                .fetchSemanticsNodes()
                                .isNotEmpty()
                        }
                        scenario.onActivity {
                            assertEquals(session, it.model.session)
                            assertNull(it.model.state.value.connection)
                        }
                        compose.onNodeWithTag("remote-library-prompt-cancel").performClick()
                        assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
                        withTimeout(10000) {
                            while (
                                withContext(Dispatchers.IO) {
                                    File(context.filesDir, "remote-library-drafts/$session.json")
                                        .exists()
                                }
                            ) delay(10)
                        }
                    }
            } finally {
                withContext(Dispatchers.IO) {
                    AppConfig.defaultBookTreeUri = previous
                    session?.let { id ->
                        listOf(
                                "json",
                                "json.bak",
                                "json.new",
                                "json.closed",
                                "json.closed.bak",
                                "json.closed.new",
                            )
                            .forEach { suffix ->
                                File(context.filesDir, "remote-library-drafts/$id.$suffix").delete()
                            }
                    }
                }
            }
        }
}
