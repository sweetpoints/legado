package io.legado.app.ui.browser

import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.repository.*
import io.legado.app.model.browser.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BrowserComposeTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<BrowserViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    @After fun clear() { compose.runOnIdle { gates.forEach { it.complete(Unit) }; models.forEach { it.stop(); it.viewModelScope.cancel() } } }
    private fun page(origin: String = "source") = BrowserPage(BrowserRequest("https://example.com", "title", "source name", origin),
        "https://example.com", "<html>full page</html>", true, emptyMap(), "UA", null)
    private fun state(origin: String = "source") = BrowserState(loading = false, page = page(origin), title = "Loaded title")
    private fun actions() = BrowserScreenActions({}, {}, {}, {}, {}, {})
    private class Owner : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle get() = registry }
    @Test fun toolbarAndMenuDeliverTypedActionsAndSourceItemsRespectOrigin() {
        var state by mutableStateOf(state()); val events = mutableListOf<BrowserMenu>(); var back = 0
        compose.setContent { LegadoComposeTheme { BrowserScreen(state, actions().copy(back = { back++ }, menu = { events += it }), webContent = {}) } }
        compose.onNodeWithTag("browser-title").assertTextEquals("Loaded title"); compose.onNodeWithTag("browser-subtitle").assertTextEquals("source name")
        compose.onNodeWithTag("browser-refresh").assertWidthIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("browser-confirm").assertWidthIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("browser-back").performClick(); assertEquals(1, back)
        listOf(BrowserMenu.Open, BrowserMenu.Copy, BrowserMenu.Fullscreen, BrowserMenu.Log, BrowserMenu.Disable).forEach { menu ->
            compose.onNodeWithTag("browser-menu").performClick(); compose.onNodeWithTag("browser-menu-${menu.name}").performClick()
        }
        assertEquals(browserMenus.dropLast(1), events)
        compose.runOnIdle { state = this@BrowserComposeTest.state(origin = "") }; compose.onNodeWithTag("browser-menu").performClick()
        compose.onNodeWithTag("browser-menu-Disable").assertDoesNotExist(); compose.onNodeWithTag("browser-menu-Delete").assertDoesNotExist()
    }
    @Test fun deleteRequiresConfirmationAndOpenConfirmationRestoresWithoutDeleting() {
        val tester = StateRestorationTester(compose); val events = mutableListOf<BrowserMenu>()
        tester.setContent { LegadoComposeTheme { BrowserScreen(state(), actions().copy(menu = { events += it }), webContent = {}) } }
        compose.onNodeWithTag("browser-menu").performClick(); compose.onNodeWithTag("browser-menu-Delete").performClick()
        assertTrue(events.isEmpty()); tester.emulateSavedInstanceStateRestore(); compose.onNodeWithTag("browser-delete-confirm").assertIsDisplayed()
        compose.onNodeWithTag("browser-delete-cancel").performClick(); assertTrue(events.isEmpty())
        compose.onNodeWithTag("browser-menu").performClick(); compose.onNodeWithTag("browser-menu-Delete").performClick()
        compose.onNodeWithTag("browser-delete-confirm").performClick(); assertEquals(listOf(BrowserMenu.Delete), events)
    }
    @Test fun imageMenuDistinguishesSaveFolderAndDismissWithoutImplicitSave() {
        var state by mutableStateOf(state().copy(imageActions = true)); var save = 0; var folder = 0; var dismiss = 0
        compose.setContent { LegadoComposeTheme { BrowserScreen(state, actions().copy(saveImage = { save++; state = state.copy(imageActions = false) },
            selectImageFolder = { folder++; state = state.copy(imageActions = false) }, imageDismiss = { dismiss++ }), webContent = {}) } }
        compose.onNodeWithTag("browser-image-save").assertHeightIsAtLeast(48.dp).performClick(); assertEquals(1, save); assertEquals(0, folder)
        compose.runOnIdle { state = state.copy(imageActions = true) }; compose.onNodeWithTag("browser-image-folder").performClick()
        assertEquals(1, folder); assertEquals(0, dismiss); assertEquals(1, save)
    }
    @Test fun busyAndFailedStateBlockUnsafeActionsButKeepBackAndExplicitRetry() {
        var state by mutableStateOf(state().copy(busy = true)); var retries = 0; var back = 0
        compose.setContent { LegadoComposeTheme { BrowserScreen(state, actions().copy(retry = { retries++ }, back = { back++ }), webContent = {}) } }
        compose.onNodeWithTag("browser-progress").assertExists(); compose.onNodeWithTag("browser-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("browser-refresh").assertIsNotEnabled(); compose.onNodeWithTag("browser-back").performClick()
        compose.runOnIdle { state = state.copy(busy = false, persistError = true, error = "disk full") }
        compose.onNodeWithTag("browser-error").assertTextEquals("disk full"); compose.onNodeWithTag("browser-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("browser-retry").performClick(); assertEquals(1, retries); assertEquals(1, back)
    }
    @Test fun fullscreenAndNativeVideoHideToolbarAndExposeOnlyVideoSemantics() {
        var state by mutableStateOf(state().copy(fullscreen = true)); var video by mutableStateOf(false)
        compose.setContent { LegadoComposeTheme { BrowserScreen(state, actions(), video = video,
            webContent = { Box(Modifier.fillMaxSize().testTag("visible-web")) }, videoContent = { Box(Modifier.fillMaxSize().testTag("visible-video")) }) } }
        compose.onNodeWithTag("browser-menu").assertDoesNotExist(); compose.onNodeWithTag("visible-web").assertIsDisplayed()
        compose.runOnIdle { video = true; state = state.copy(fullscreen = false) }
        compose.onNodeWithTag("visible-video").assertIsDisplayed(); compose.onNodeWithTag("browser-menu").assertDoesNotExist()
    }
    @Test fun systemAndKeyboardInsetsLeaveBrowserCoreAboveBottomAndToolbarBelowTop() {
        compose.setContent { LegadoComposeTheme { Box(Modifier.requiredSize(320.dp, 500.dp)) { BrowserScreen(state(), actions(),
            insets = WindowInsets(top = 24.dp, bottom = 180.dp), webContent = {}) } } }
        val page = compose.onNodeWithTag("browser-page").fetchSemanticsNode().boundsInRoot
        val back = compose.onNodeWithTag("browser-back").fetchSemanticsNode().boundsInRoot
        val core = compose.onNodeWithTag("browser-web-core").fetchSemanticsNode().boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue(back.top >= page.top + 24 * density - 1); assertTrue(core.bottom <= page.bottom - 180 * density + 1)
    }
    @Test fun pausedCookiePreparationCannotInstallAndResumingInstallsOnceBeforeNativeCapture() {
        val owner = Owner(); val gate = CompletableDeferred<Unit>(); gates += gate
        val repo = Repo(page().copy(request = page().request.copy(verificationEnabled = true, refetchAfterSuccess = false))).apply { cookieGate = gate }
        lateinit var vm: BrowserViewModel; lateinit var web: FrameLayout; var installs = 0; var captures = 0; var finishes = 0
        var document by mutableStateOf(false)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED; web = FrameLayout(InstrumentationRegistry.getInstrumentation().targetContext)
            vm = BrowserViewModel(repo, SavedStateHandle()) { repo.value.request }; models += vm }
        val host = BrowserHostActions({ true }, { installs > 0 }, { installs++ }, { id -> captures++; vm.captured(id, "captured", "resolved") }, {}, {}, {}, { finishes++ }, {}, {})
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { LegadoComposeTheme {
            BrowserRoute(vm, host, web, null, document, false, androidx.compose.material3.SnackbarHostState()) } } }
        compose.waitUntil(5000) { repo.cookieReads > 0 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED; gate.complete(Unit); vm.verify() }
        compose.waitUntil(5000) { vm.state.value.capture != null }; assertEquals(0, installs); assertEquals(0, captures)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitUntil(5000) { installs == 1 }; assertEquals(0, captures)
        compose.runOnIdle { document = true }; compose.waitUntil(5000) { captures == 1 && finishes == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }; compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle(); assertEquals(1, installs); assertEquals(1, captures); assertEquals(1, finishes)
    }
    @Test fun receiptDeliveredOnResumeIsConsumedBeforeHostAndCannotRepeatAfterCompositionRecreation() {
        val owner = Owner(); val repo = Repo(page().copy(request = page().request.copy(verificationEnabled = true)))
        lateinit var vm: BrowserViewModel; lateinit var web: FrameLayout; var delivered = 0; var closed = 0; var installed = false
        var visible by mutableStateOf(true)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED; web = FrameLayout(InstrumentationRegistry.getInstrumentation().targetContext)
            vm = BrowserViewModel(repo, SavedStateHandle()) { repo.value.request }; models += vm }
        val host = BrowserHostActions({ true }, { installed }, { installed = true }, {}, {
            assertNull(vm.state.value.receipt); assertEquals(BrowserReceiptKind.Verified, it.kind); delivered++ }, {}, {}, { closed++ }, {}, {})
        compose.setContent { if (visible) CompositionLocalProvider(LocalLifecycleOwner provides owner) { LegadoComposeTheme {
            BrowserRoute(vm, host, web, null, true, false, androidx.compose.material3.SnackbarHostState()) } } }
        compose.waitUntil(5000) { !vm.state.value.loading }; compose.runOnIdle { vm.verify() }; compose.waitUntil(5000) { vm.state.value.receipt != null }
        assertEquals(0, delivered); compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitUntil(5000) { delivered == 1 }
        compose.runOnIdle { visible = false }; compose.runOnIdle { visible = true }; compose.waitForIdle()
        assertEquals(1, delivered); assertTrue(closed >= 1)
    }
    @Test fun cookiePreparationFailureRequiresExplicitRetryWithoutReloadingPageOrDeliveringHostEarly() {
        val repo = Repo(page()).apply { cookieFailure = true }
        lateinit var vm: BrowserViewModel; lateinit var web: FrameLayout; var installed = 0
        compose.runOnIdle { web = FrameLayout(InstrumentationRegistry.getInstrumentation().targetContext)
            vm = BrowserViewModel(repo, SavedStateHandle()) { repo.value.request }; models += vm }
        val host = BrowserHostActions({ true }, { installed > 0 }, { installed++ }, {}, {}, {}, {}, {}, {}, {})
        compose.setContent { LegadoComposeTheme { BrowserRoute(vm, host, web, null, false, false, androidx.compose.material3.SnackbarHostState()) } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("browser-error").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, installed); compose.onNodeWithTag("browser-confirm").assertIsNotEnabled()
        compose.runOnIdle { repo.cookieFailure = false }; compose.onNodeWithTag("browser-retry").performClick()
        compose.waitUntil(5000) { installed == 1 }; assertEquals(2, repo.cookieReads)
        compose.onNodeWithTag("browser-error").assertDoesNotExist(); assertEquals("<html>full page</html>", repo.value.page!!.html)
    }
    private class Repo(page: BrowserPage) : BrowserRepository {
        var value = BrowserSession(page.request, page = page)
        var cookieReads = 0; var cookieFailure = false; var cookieGate: CompletableDeferred<Unit>? = null
        override suspend fun read(session: String) = value
        override suspend fun create(session: String, seed: BrowserSession) = value
        override suspend fun write(session: String, snapshot: BrowserSession) { if (snapshot.revision >= value.revision) value = snapshot }
        override suspend fun release(session: String) = Unit
        override suspend fun prepare(request: BrowserRequest) = value.page!!
        override suspend fun refetch(page: BrowserPage) = BrowserVerification("verified", page.baseUrl)
        override suspend fun captured(htmlJson: String, url: String) = BrowserVerification(htmlJson, url)
        override suspend fun saveImage(data: String, directory: String) = Unit
        override suspend fun imageDirectory(): String? = null
        override suspend fun imageDirectory(value: String) = Unit
        override suspend fun forgetImageDirectory(expected: String) = Unit
        override suspend fun disableSource(origin: String, type: Int) = Unit
        override suspend fun deleteSource(origin: String, type: Int) = Unit
        override suspend fun webCookies(url: String): BrowserWebCookies? { cookieReads++; cookieGate?.await(); if (cookieFailure) error("cookie read failed"); return null }
        override suspend fun cookie(url: String, value: String?) = Unit
    }
}
