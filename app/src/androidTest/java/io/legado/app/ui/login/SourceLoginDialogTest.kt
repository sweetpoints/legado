package io.legado.app.ui.login

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.getStoredLoginInfoMap
import io.legado.app.ui.about.AboutActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SourceLoginDialogTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun dialogRecreationDoesNotSaveOrFinishAndExplicitClosePersistsTypedForm() {
        val source =
            HttpTTS(
                id = System.nanoTime(),
                name = "Compose login test",
                loginUi =
                    """[{"name":"user","type":"text"},{"name":"password","type":"password"}]""",
            )
        runBlocking(Dispatchers.IO) { appDb.httpTTSDao.insert(source) }
        try {
            ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[SourceLoginViewModel::class.java]
                    model.initData(
                        Intent().putExtra("type", "httpTts").putExtra("key", source.id.toString()),
                        success = {
                            SourceLoginDialog()
                                .show(activity.supportFragmentManager, "source-login")
                        },
                        error = { error("Source initialization failed") },
                    )
                }
                compose
                    .onNodeWithTag("source-login-field:user")
                    .performTextReplacement("unconfirmed")
                scenario.recreate()
                compose.onNodeWithTag("source-login-field:user").assertTextContains("unconfirmed")
                assertTrue(
                    runBlocking(Dispatchers.IO) { source.getStoredLoginInfoMap().isNullOrEmpty() }
                )
                compose.onNodeWithTag("source-login-close").performClick()
                compose.waitUntil {
                    runBlocking(Dispatchers.IO) {
                        source.getStoredLoginInfoMap()?.get("user") == "unconfirmed"
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
