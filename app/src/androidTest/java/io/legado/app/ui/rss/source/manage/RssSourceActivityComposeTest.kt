package io.legado.app.ui.rss.source.manage

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.rssSourceManagementId
import io.legado.app.utils.GSON
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Real Room + Activity recovery; the shared import/parser pipeline is intentionally unchanged. */
class RssSourceActivityComposeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun urlDraftAndSelectionSurviveActivityRecreationAndCancelDoesNotImport() {
        val group = "Management-${UUID.randomUUID()}"
        val source =
            RssSource(
                sourceUrl = "https://$group.invalid",
                sourceName = "Managed",
                sourceGroup = group,
                enabled = false,
                sourceComment = "Keep",
                ruleContent = "body@text",
            )
        runBlocking(Dispatchers.IO) { appDb.rssSourceDao.insert(source) }
        try {
            ActivityScenario.launch(RssSourceActivity::class.java).use { scenario ->
                var model: RssSourceManagementViewModel? = null
                scenario.onActivity { model = it.managementModel }
                compose.waitUntil { model!!.state.value.loaded }
                scenario.onActivity { it.managementModel.query("group:$group") }
                compose.waitUntil { model!!.state.value.rows.size == 1 }
                compose
                    .onNodeWithTag("rss-source-select-${rssSourceManagementId(source.sourceUrl)}")
                    .performClick()
                compose.onNodeWithTag("rss-source-more").performClick()
                compose.onNodeWithTag("rss-source-import-url").performClick()
                compose
                    .onNodeWithTag("rss-source-dialog-field")
                    .performTextReplacement("https://draft.invalid/import.json")
                compose
                    .onNodeWithTag("rss-source-dialog-field")
                    .performTextInputSelection(androidx.compose.ui.text.TextRange(8, 13))
                scenario.recreate()
                scenario.onActivity { model = it.managementModel }
                compose.waitUntil { model!!.state.value.loaded }
                compose
                    .onNodeWithTag("rss-source-dialog-field")
                    .assertTextEquals("https://draft.invalid/import.json")
                assertEquals(8, model!!.state.value.draftStart)
                assertEquals(13, model.state.value.draftEnd)
                scenario.onActivity { it.managementModel.cancelDialog() }
                compose.onNodeWithTag("rss-source-count").assertTextEquals("1/1")
                assertNull(model.state.value.pending)
                runBlocking(Dispatchers.IO) {
                    assertEquals(
                        GSON.toJson(source),
                        GSON.toJson(appDb.rssSourceDao.getByKey(source.sourceUrl)),
                    )
                }
            }
        } finally {
            runBlocking(Dispatchers.IO) { appDb.rssSourceDao.delete(source) }
        }
    }

    @Test
    fun deleteConfirmationCancelAndFreshEnableLeaveFullMetadataUntouched() {
        val group = "Cancel-${UUID.randomUUID()}"
        val source =
            RssSource(
                sourceUrl = "https://$group.invalid",
                sourceName = "Managed",
                sourceGroup = group,
                enabled = false,
                header = "headers",
                loginUrl = "https://login.invalid",
                ruleContent = "body@text",
                variableComment = "vars",
            )
        runBlocking(Dispatchers.IO) { appDb.rssSourceDao.insert(source) }
        try {
            ActivityScenario.launch(RssSourceActivity::class.java).use { scenario ->
                var model: RssSourceManagementViewModel? = null
                scenario.onActivity { model = it.managementModel }
                compose.waitUntil { model!!.state.value.loaded }
                scenario.onActivity { it.managementModel.query("group:$group") }
                compose.waitUntil { model!!.state.value.rows.size == 1 }
                val id = rssSourceManagementId(source.sourceUrl)
                compose.onNodeWithTag("rss-source-menu-$id").performClick()
                compose.onNodeWithTag("rss-source-delete-$id").performClick()
                scenario.recreate()
                scenario.onActivity { model = it.managementModel }
                compose.waitUntil { model!!.state.value.loaded }
                assertEquals(RssSourceManagementDialog.Delete, model!!.state.value.dialog)
                scenario.onActivity { it.managementModel.cancelDialog() }
                compose.onNodeWithTag("rss-source-enabled-$id").performClick()
                compose.waitUntil {
                    runBlocking(Dispatchers.IO) {
                        appDb.rssSourceDao.getByKey(source.sourceUrl)?.enabled == true
                    }
                }
                runBlocking(Dispatchers.IO) {
                    assertEquals(
                        GSON.toJson(source.copy(enabled = true)),
                        GSON.toJson(appDb.rssSourceDao.getByKey(source.sourceUrl)),
                    )
                }
            }
        } finally {
            runBlocking(Dispatchers.IO) { appDb.rssSourceDao.delete(source) }
        }
    }

    @Test
    fun slideSelectionCancelRestoresBaselineWithoutCheckboxToggleAndReleaseCommitsRange() {
        val group = "Slide-${UUID.randomUUID()}"
        val sources =
            (0..2).map { index ->
                RssSource(
                    sourceUrl = "https://$group.invalid/$index",
                    sourceName = "Source $index",
                    sourceGroup = group,
                    customOrder = index,
                )
            }
        runBlocking(Dispatchers.IO) { appDb.rssSourceDao.insert(*sources.toTypedArray()) }
        try {
            ActivityScenario.launch(RssSourceActivity::class.java).use { scenario ->
                var model: RssSourceManagementViewModel? = null
                scenario.onActivity { model = it.managementModel }
                compose.waitUntil { model!!.state.value.loaded }
                scenario.onActivity { it.managementModel.query("group:$group") }
                compose.waitUntil { model!!.state.value.rows.size == 3 }
                val ids = sources.map { rssSourceManagementId(it.sourceUrl) }
                val list = compose.onNodeWithTag("rss-source-list")
                val bounds = list.fetchSemanticsNode().boundsInRoot
                val first =
                    compose
                        .onNodeWithTag("rss-source-row-${ids[0]}")
                        .fetchSemanticsNode()
                        .boundsInRoot
                val last =
                    compose
                        .onNodeWithTag("rss-source-row-${ids[2]}")
                        .fetchSemanticsNode()
                        .boundsInRoot
                val x =
                    24 *
                        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                            .targetContext
                            .resources
                            .displayMetrics
                            .density
                fun begin() = list.performTouchInput {
                    down(androidx.compose.ui.geometry.Offset(x, first.center.y - bounds.top))
                    moveTo(androidx.compose.ui.geometry.Offset(x, last.center.y - bounds.top), 100)
                }
                begin()
                compose.waitUntil { model!!.state.value.selected.size == 3 }
                list.performTouchInput { cancel() }
                compose.waitUntil { model!!.state.value.selected.isEmpty() }
                begin()
                list.performTouchInput { up() }
                compose.waitUntil { model!!.state.value.selected == ids.toSet() }
                compose.onNodeWithTag("rss-source-count").assertTextEquals("3/3")
                scenario.recreate()
                scenario.onActivity { model = it.managementModel }
                compose.waitUntil { model!!.state.value.loaded }
                compose.onNodeWithTag("rss-source-count").assertTextEquals("3/3")
            }
        } finally {
            runBlocking(Dispatchers.IO) { appDb.rssSourceDao.delete(*sources.toTypedArray()) }
        }
    }
}
