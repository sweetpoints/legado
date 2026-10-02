package io.legado.app.ui.dict

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.CopyOnWriteArrayList
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

class DictionaryLookupScreenTest {
    @get:Rule val compose = createComposeRule()
    private val density get() = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
    private fun show(repo: Fake, saved: SavedStateHandle = SavedStateHandle(), photo: (String) -> Unit = {}, link: (String) -> Unit = {}, ready: () -> Unit = {}): DictionaryLookupViewModel {
        val model = DictionaryLookupViewModel(repo, saved, "甲乙")
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) = link(uri) }) {
                LegadoComposeTheme { DictionaryLookupRoute(model, {}, photo, onResultReady = ready) }
            }
        }
        compose.waitUntil(15000) { model.state.value.rules.isNotEmpty() || !model.state.value.loading }
        return model
    }
    @Test fun tabsSwitchDictionaryAndIgnoreLateReply() {
        val repo = Fake(); val old = CompletableDeferred<String>()
        repo.searching = { rule -> if (rule.name == "a") withContext(NonCancellable) { old.await() } else "fresh" }
        val model = show(repo.apply { values = listOf(DictionaryRuleSnapshot("a"), DictionaryRuleSnapshot("b")) }, ready = {})
        compose.onNodeWithTag("dictionary-tab-b").performClick()
        compose.waitUntil(15000) { model.state.value.document?.text == "fresh" }
        old.complete("obsolete")
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("b", model.state.value.selected); assertEquals("fresh", model.state.value.document?.text) }
    }
    @Test fun moreThanFourTabsRemainScrollableAndSelectable() {
        val repo = Fake().apply { values = (1..8).map { DictionaryRuleSnapshot("dictionary-$it") } }
        val model = show(repo)
        compose.onNodeWithTag("dictionary-tab-dictionary-8").performScrollTo().performClick()
        compose.waitUntil(15000) { model.state.value.selected == "dictionary-8" && !model.state.value.loading }
        compose.runOnIdle { assertEquals("dictionary-8", repo.queries.last()) }
    }
    @Test fun errorRetryUsesComposeButtonAndDisplaysNewResult() {
        val repo = Fake().apply { searching = { error("failed") } }; val model = show(repo)
        compose.waitUntil(15000) { model.state.value.error != null }
        compose.onNodeWithTag("dictionary-lookup-error").assertTextContains("failed")
        repo.searching = { "success" }
        compose.onNodeWithTag("dictionary-lookup-retry").performClick()
        compose.waitUntil(15000) { model.state.value.document?.text == "success" }
        compose.onNodeWithTag("dictionary-lookup-error").assertDoesNotExist()
    }
    @Test fun actualHtmlButtonTouchDispatchesExactRuleScriptWithBrowserJavaScriptDisabled() {
        val repo = Fake().apply { searching = { "<button onclick='unexpected()'>Run@onclick:java.toast('x')</button>" } }
        var ready = false; show(repo, ready = { ready = true }); compose.waitUntil(15000) { ready }
        compose.onNodeWithTag("dictionary-result").performTouchInput { click(Offset(40f * density, 30f * density)) }
        compose.waitUntil(15000) { repo.clicks.isNotEmpty() }
        compose.runOnIdle { assertEquals(Triple("a", "button Run", "java.toast('x')"), repo.clicks.single()) }
    }
    @Test fun actualHtmlLinkTouchOpensOriginalUrl() {
        val repo = Fake().apply { searching = { "<a href='https://example.org/?q=甲乙&amp;a=1'>Open dictionary result</a>" } }
        var ready = false; var opened = ""; show(repo, link = { opened = it }, ready = { ready = true })
        compose.waitUntil(15000) { ready }
        compose.onNodeWithTag("dictionary-result").performTouchInput { click(Offset(40f * density, 24f * density)) }
        compose.waitUntil(15000) { opened.isNotEmpty() }
        compose.runOnIdle { assertEquals("甲乙", android.net.Uri.parse(opened).getQueryParameter("q")); assertEquals("1", android.net.Uri.parse(opened).getQueryParameter("a")); assertTrue(repo.clicks.isEmpty()) }
    }
    @Test fun actualImageLongPressOpensPhotoWithOriginalOptionsWithoutClickingScript() {
        val source = "https://example.org/x.png,{\"width\":\"100\",\"click\":\"java.toast(2)\"}"
        val repo = Fake().apply { searching = { "<img src='$source'>" } }
        var ready = false; var photo = ""; show(repo, photo = { photo = it }, ready = { ready = true })
        compose.waitUntil(15000) { ready && repo.images.isNotEmpty() }
        compose.onNodeWithTag("dictionary-result").performTouchInput { longClick(Offset(40f * density, 40f * density), durationMillis = 800) }
        compose.waitUntil(15000) { photo.isNotEmpty() }
        compose.runOnIdle { assertEquals(source, photo); assertTrue(repo.clicks.isEmpty()) }
    }
    @Test fun unknownActionLinkIsConsumedWithoutScriptOrExternalNavigation() {
        val repo = Fake().apply { searching = { "<a href='https://dictionary-action.invalid/unrecognized/0'>Unknown action</a>" } }
        var ready = false; var opened = ""; show(repo, link = { opened = it }, ready = { ready = true }); compose.waitUntil(15000) { ready }
        compose.onNodeWithTag("dictionary-result").performTouchInput { click(Offset(40f * density, 24f * density)) }
        compose.runOnIdle { assertEquals("", opened); assertTrue(repo.clicks.isEmpty()) }
    }
    @Test fun restoredSelectedDictionaryDisplaysCachedResultWithoutRequest() {
        val repo = Fake().apply { values = listOf(DictionaryRuleSnapshot("a"), DictionaryRuleSnapshot("b", "url", "show")) }
        val saved = SavedStateHandle(mapOf("dictionary.lookup.selected" to "b", "dictionary.lookup.result.name" to "b", "dictionary.lookup.result.url" to "url", "dictionary.lookup.result.show" to "show", "dictionary.lookup.result.content" to "restored result"))
        val model = show(repo, saved)
        compose.waitUntil(15000) { model.state.value.document?.text == "restored result" }
        compose.onNodeWithTag("dictionary-tab-b").assertIsSelected()
        compose.runOnIdle { assertTrue(repo.queries.isEmpty()) }
    }
    @Test fun webViewReleasesWhenResultLeavesCompositionAndKeepsJavaScriptDisabled() {
        val document = dictionaryResultDocument("<p>text</p>")
        val visible = mutableStateOf(true); var ready = false
        compose.setContent { LegadoComposeTheme { if (visible.value) DictionaryResultWebView(document,
            { DictionaryImageData(png(), "image/png") }, { false }, {}, {}, Modifier.height(200.dp), { ready = true }) } }
        compose.waitUntil(15000) { ready }
        lateinit var native: DictionaryHtmlView
        compose.runOnIdle {
            fun find(view: View): DictionaryHtmlView? {
                if (view is DictionaryHtmlView) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                return null
            }
            native = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .mapNotNull { find(it.window.decorView) }.first()
            assertFalse(native.settings.javaScriptEnabled)
            visible.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(native.isReleased) }
    }
    @Test fun switchingAtoBtoAtoBRestoresEachActualWebViewScrollPosition() {
        val repo = Fake().apply {
            values = listOf(DictionaryRuleSnapshot("a", "url-a"), DictionaryRuleSnapshot("b", "url-b"))
            searching = { rule -> (1..100).joinToString("") { "<p>${rule.name}: row $it</p>" } }
        }
        var ready = 0; val model = show(repo, ready = { ready++ })
        compose.waitUntil(15000) { ready >= 1 }
        compose.runOnIdle { nativeResult().scrollTo(0, 400) }
        compose.waitForIdle()
        compose.onNodeWithTag("dictionary-tab-b").performClick()
        compose.waitUntil(15000) { ready >= 2 && model.state.value.selected == "b" }
        compose.runOnIdle { assertEquals(0, nativeResult().scrollY); nativeResult().scrollTo(0, 700) }
        compose.waitForIdle()
        compose.onNodeWithTag("dictionary-tab-a").performClick()
        compose.waitUntil(15000) { ready >= 3 && model.state.value.selected == "a" }
        compose.runOnIdle { assertEquals(400, nativeResult().scrollY) }
        compose.onNodeWithTag("dictionary-tab-b").performClick()
        compose.waitUntil(15000) { ready >= 4 && model.state.value.selected == "b" }
        compose.runOnIdle { assertEquals(700, nativeResult().scrollY) }
    }
    @Test fun mountingUnderAlreadyPausedOwnerSynchronizesNativePauseAndLaterResume() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        val document = dictionaryResultDocument("<p>paused result</p>")
        var ready = false
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            LegadoComposeTheme { DictionaryResultWebView(document, { DictionaryImageData(png(), "image/png") },
                { false }, {}, {}, Modifier.height(200.dp), { ready = true }) }
        } }
        compose.waitUntil(15000) { ready }
        compose.runOnIdle { assertFalse(nativeResult().isLifecycleResumed); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(nativeResult().isLifecycleResumed); owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(nativeResult().isLifecycleResumed) }
    }
    private fun nativeResult(): DictionaryHtmlView {
        fun find(view: View): DictionaryHtmlView? {
            if (view is DictionaryHtmlView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        return ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .mapNotNull { find(it.window.decorView) }.first()
    }
    private class Fake : DictionaryLookupRepository {
        var values = listOf(DictionaryRuleSnapshot("a"))
        var searching: suspend (DictionaryRuleSnapshot) -> String = { "result" }
        val queries = mutableListOf<String>(); val clicks = mutableListOf<Triple<String,String,String>>(); val images = CopyOnWriteArrayList<String>()
        override suspend fun rules() = values
        override suspend fun search(rule: DictionaryRuleSnapshot, word: String): String { queries += rule.name; return searching(rule) }
        override suspend fun click(rule: DictionaryRuleSnapshot, name: String, script: String) { clicks += Triple(rule.name, name, script) }
        override suspend fun image(source: String): DictionaryImageData { synchronized(images) { images += source }; return DictionaryImageData(png(), "image/png") }
    }
    companion object {
        private fun png(): ByteArray {
            val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            return try { ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); stream.toByteArray() } }
            finally { bitmap.recycle() }
        }
    }
}
