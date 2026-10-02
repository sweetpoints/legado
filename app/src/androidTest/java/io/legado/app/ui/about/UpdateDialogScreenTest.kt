package io.legado.app.ui.about

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*

class UpdateDialogScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: UpdateDialogViewModel
    private val images = object : MarkdownImageRepository { override suspend fun load(source: String, width: Int) = null }
    private class Fake(var request: UpdateDialogRequest = UpdateDialogRequest("v1", "## Changes\n**Bold** and <i>HTML</i>\n\n[Site](https://example.test)\n\n| A | B |\n|---|---|\n| C | D |", "primary", "app.apk", "backup", "mirror", "alternate", 1024)) : UpdateDialogRepository {
        var gate: CompletableDeferred<Unit>? = null; var fail = false; val ignored = mutableListOf<String>()
        override suspend fun load(id: String): UpdateDialogRequest { gate?.await(); if (fail) error("failed"); return request }
        override suspend fun ignore(version: String) { ignored += version }
    }
    private class Owner : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle get() = registry }
    private fun show(repo: Fake, effect: (UpdateDialogEffect) -> Unit = {}, link: (String) -> Unit = {}, close: () -> Unit = {}, owner: Owner? = null) {
        compose.runOnIdle { model = UpdateDialogViewModel(repo, SavedStateHandle(), "id") }
        compose.setContent { LegadoComposeTheme {
            if (owner == null) UpdateDialogRoute(model, images, { true }, effect, link, {}, close, {})
            else CompositionLocalProvider(LocalLifecycleOwner provides owner) { UpdateDialogRoute(model, images, { true }, effect, link, {}, close, {}) }
        } }
    }
    private fun loaded() { compose.waitUntil(5000) { !model.state.value.loading } }
    private fun menu(tag: String) { compose.onNodeWithTag("update-menu").performClick(); compose.onNodeWithTag(tag).performClick() }
    @After fun cleanup() { if (::model.isInitialized) compose.runOnIdle { model.stop() } }
    @Test fun releaseTitleMetadataAndActualRichLogKeepHtmlStylesTablesAndLinks() {
        val links = mutableListOf<String>(); show(Fake(), link = { links += it }); loaded()
        compose.onNodeWithTag("update-title").assertTextEquals("v1"); compose.onNodeWithTag("update-metadata").assertTextEquals("1 kb")
        compose.onNodeWithTag("rich-table").assertExists()
        val text = compose.onNodeWithText("Bold and HTML").fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold && text.text.substring(it.start, it.end) == "Bold" })
        compose.onNodeWithText("Site").performTouchInput { click(Offset(10f, center.y)) }
        compose.runOnIdle { assertEquals(listOf("https://example.test"), links) }
    }
    @Test fun formalPrimaryButtonDeliversNativeDownloadAndClosesOnlyAfterHandoff() {
        val effects = mutableListOf<UpdateDialogEffect>(); var closes = 0; show(Fake(), { effects += it }, close = { closes++ }); loaded()
        compose.onNodeWithTag("update-now").performClick(); compose.waitUntil { closes == 1 }
        assertEquals(1, effects.size); assertEquals(UpdateDialogAction.Download, effects.single().action)
        assertEquals("primary", effects.single().url); assertEquals("app.apk", effects.single().fileName)
    }
    @Test fun betaMenuContainsBrowserOnlyAndBrowserDeliveryKeepsDialogOpen() {
        val repo = Fake().apply { request = request.copy(beta = true) }; val effects = mutableListOf<UpdateDialogEffect>(); var closes = 0
        show(repo, { effects += it }, close = { closes++ }); loaded(); compose.onNodeWithTag("update-menu").performClick()
        compose.onNodeWithTag("update-ignore").assertDoesNotExist(); compose.onNodeWithTag("update-download-Backup").assertDoesNotExist()
        compose.onNodeWithTag("update-download-Mirror").assertDoesNotExist(); compose.onNodeWithTag("update-download-AlternateMirror").assertDoesNotExist()
        compose.onNodeWithTag("update-browser").performClick(); compose.waitUntil { effects.size == 1 }
        assertEquals(UpdateDialogAction.Browser, effects.single().action); assertEquals("primary", effects.single().url); assertEquals(0, closes)
        compose.onNodeWithTag("update-now").assertIsEnabled(); assertTrue(repo.ignored.isEmpty())
    }
    @Test fun formalAlternateActionsStaySeparateAndFailedServiceHandoffLeavesRetryablePage() {
        val effects = mutableListOf<UpdateDialogEffect>(); show(Fake(), effect = { effects += it; error("native failed") }); loaded()
        menu("update-download-Mirror"); compose.onNodeWithTag("update-error").assertTextEquals("native failed"); assertEquals("mirror", effects.single().url)
        menu("update-download-AlternateMirror"); compose.waitUntil { effects.size == 2 }; assertEquals("alternate", effects.last().url)
        menu("update-download-Backup"); compose.waitUntil { effects.size == 3 }; assertEquals("backup", effects.last().url)
        compose.onNodeWithTag("update-now").assertIsEnabled(); assertFalse(model.state.value.finished)
    }
    @Test fun queuedDownloadWaitsForResumeAndDoesNotReplayAfterLifecycleReturns() {
        val owner = Owner(); val effects = mutableListOf<UpdateDialogEffect>(); var closes = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        show(Fake(), { effects += it }, close = { closes++ }, owner = owner); loaded()
        compose.onNodeWithTag("update-now").performClick(); compose.waitForIdle(); assertTrue(effects.isEmpty()); assertEquals(0, closes)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitUntil { effects.size == 1 && closes == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED; owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle(); assertEquals(1, effects.size)
    }
    @Test fun pendingAndFailedRequestsBlockDownloadUntilRetryAndCancellationDoesNotDeliverActions() {
        val repo = Fake().apply { gate = CompletableDeferred(); fail = true }; val effects = mutableListOf<UpdateDialogEffect>(); var closes = 0
        show(repo, { effects += it }, close = { closes++ }); compose.onNodeWithTag("update-now").assertIsNotEnabled()
        compose.runOnIdle { repo.gate!!.complete(Unit) }; loaded(); compose.onNodeWithTag("update-error").assertTextEquals("failed")
        compose.runOnIdle { repo.fail = false }; compose.onNodeWithTag("update-retry").performClick(); loaded()
        compose.onNodeWithTag("update-cancel").performClick(); compose.waitUntil { closes == 1 }; assertTrue(effects.isEmpty())
    }
    @Test fun formalIgnorePersistsVersionThenDeliversNoticeAndCloses() {
        val repo = Fake(); val effects = mutableListOf<UpdateDialogEffect>(); var closes = 0
        show(repo, { effects += it }, close = { closes++ }); loaded(); menu("update-ignore")
        compose.waitUntil { closes == 1 }; assertEquals(listOf("v1"), repo.ignored)
        assertEquals(UpdateDialogAction.IgnoredNotice, effects.single().action)
    }
}
