package io.legado.app.ui.autoTask

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.model.Debug
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class AutoTaskDebugHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun realHostAutomaticallyExecutesLogsAndRecreationRetainsCompletedOutputWithoutRunningAgain() {
        val id = UUID.randomUUID().toString()
        val task =
            AutoTaskRule(
                id,
                "Debug $id",
                false,
                script = "java.log('host fixture'); 42",
                lastRunAt = 99,
                lastLog = "runtime",
                lastError = "prior",
            )
        runBlocking(Dispatchers.IO) { appDb.autoTaskRuleDao.upsert(task) }
        try {
            ActivityScenario.launch<AutoTaskDebugActivity>(
                    AutoTaskDebugActivity.intent(context, id)
                )
                .use { scenario ->
                    compose.waitUntil(10000) {
                        var done = false
                        scenario.onActivity {
                            done =
                                !it.viewModel.uiState.value.isRunning &&
                                    it.viewModel.uiState.value.output.contains("[OK]")
                        }
                        done
                    }
                    var original = ""
                    scenario.onActivity { original = it.viewModel.uiState.value.output }
                    assertTrue(original.contains("host fixture"))
                    assertNull(Debug.callback)
                    scenario.recreate()
                    scenario.onActivity {
                        assertEquals(original, it.viewModel.uiState.value.output)
                        assertFalse(it.viewModel.uiState.value.isRunning)
                    }
                    runBlocking(Dispatchers.IO) {
                        assertEquals(task, appDb.autoTaskRuleDao.getById(id))
                    }
                    compose.onNodeWithTag("task-debug-run").performClick()
                    compose.waitUntil(10000) {
                        var done = false
                        scenario.onActivity {
                            done =
                                !it.viewModel.uiState.value.isRunning &&
                                    it.viewModel.uiState.value.output.contains("[OK]")
                        }
                        done
                    }
                    compose
                        .onNodeWithContentDescription(context.getString(R.string.back))
                        .performClick()
                }
        } finally {
            runBlocking(Dispatchers.IO) { appDb.autoTaskRuleDao.deleteByIds(listOf(id)) }
        }
    }

    @Test
    fun runningScriptLeaseSurvivesConfigurationAndBackReleasesOnlyItsOwnerWithoutWritingRuntime() {
        val id = UUID.randomUUID().toString()
        val task =
            AutoTaskRule(
                id,
                "Loop $id",
                false,
                script = "while (true) {}",
                lastRunAt = 99,
                lastLog = "retained",
            )
        runBlocking(Dispatchers.IO) { appDb.autoTaskRuleDao.upsert(task) }
        try {
            ActivityScenario.launch<AutoTaskDebugActivity>(
                    AutoTaskDebugActivity.intent(context, id)
                )
                .use { scenario ->
                    compose.waitUntil(10000) {
                        var running = false
                        scenario.onActivity {
                            running =
                                it.viewModel.uiState.value.isRunning &&
                                    it.viewModel.uiState.value.output.contains("Running")
                        }
                        running
                    }
                    val owner = Debug.callback
                    assertNotNull(owner)
                    scenario.recreate()
                    assertSame(owner, Debug.callback)
                    scenario.onActivity { assertTrue(it.viewModel.uiState.value.isRunning) }
                    compose
                        .onNodeWithContentDescription(context.getString(R.string.back))
                        .performClick()
                    compose.waitUntil { Debug.callback == null }
                    runBlocking(Dispatchers.IO) {
                        assertEquals(task, appDb.autoTaskRuleDao.getById(id))
                    }
                }
        } finally {
            runBlocking(Dispatchers.IO) { appDb.autoTaskRuleDao.deleteByIds(listOf(id)) }
        }
    }
}
