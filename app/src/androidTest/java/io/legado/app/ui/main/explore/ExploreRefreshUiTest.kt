package io.legado.app.ui.main.explore

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.view.ViewTreeObserver
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.model.ExploreInfoMapStore.exploreInfoMapList
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.defaultSharedPreferences
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Room/JS refresh, callback GC, stable Compose viewport and real keyboard regression. */
@RunWith(AndroidJUnit4::class)
class ExploreRefreshUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val savedPreferences = HashMap(preferences.all)
    private val savedLocal = HashMap(LocalConfig.all)
    private val group = "Explore refresh ${UUID.randomUUID()}"
    private val source =
        BookSource(
            bookSourceUrl = "https://explore-refresh.invalid/$group",
            bookSourceName = "Expandable source",
            bookSourceGroup = group,
            customOrder = -100,
            jsLib =
                """
                function changeKind(key, value) {
                    var source = this.source, java = this.java;
                    var state = JSON.parse(String(source.getLoginHeader() || '{}'));
                    state[key] = String(value);
                    if (state.forceGc) {
                        function marker() { return new Packages.java.lang.ref.WeakReference(new Packages.java.lang.Object()); }
                        var weak = marker();
                        for (var attempt = 0; weak.get() != null && attempt < 20; attempt++) {
                            Packages.java.lang.System.gc();
                            Packages.java.lang.System.runFinalization();
                            Packages.java.lang.Thread.sleep(10);
                        }
                        if (weak.get() != null) throw new Error('GC did not collect the control probe');
                        state.gcObserved = true;
                    }
                    source.putLoginHeader(JSON.stringify(state));
                    source.refreshExplore();
                    java.refreshExplore();
                }
                """
                    .trimIndent(),
            exploreUrl =
                """
                <js>
                var state = JSON.parse(String(source.getLoginHeader() || '{}'));
                var kinds = [], row = {layout_flexBasisPercent: 1};
                function label(title) { kinds.push({title: title, style: row}); }
                for (var i = 0; i < 36; i++) label('Category before controls ' + i);
                kinds.push({title:'More categories', type:'toggle', chars:['+ ', '- '],
                    action:"changeKind('expanded', infoMap['More categories'])", style:row});
                kinds.push({title:'Category mode', type:'select', chars:['Short list', 'Long list'],
                    action:"changeKind('mode', infoMap['Category mode'])", style:row});
                kinds.push({title:'Search categories', type:'text', style:row});
                label('Rendered ' + (state.expanded || '+ ') + (state.mode || 'Short list'));
                var count = state.expanded == '- ' || state.mode == 'Long list' ? 42 : 12;
                for (var i = 0; i < count; i++) label('Category after controls ' + i);
                JSON.stringify(kinds);
                </js>
                """
                    .trimIndent(),
        )
    private val sources =
        listOf(source) +
            (1..28).map { index ->
                BookSource(
                    bookSourceUrl = "https://explore-refresh.invalid/$group/$index",
                    bookSourceName = "Following source $index",
                    bookSourceGroup = group,
                    customOrder = index,
                    exploreUrl = "Only category::https://explore-refresh.invalid/category",
                )
            }
    private var scenario: ActivityScenario<MainActivity>? = null
    private val frames = ArrayList<String>()
    private var preDraw: ViewTreeObserver.OnPreDrawListener? = null

    @Before
    fun setUp() {
        preferences
            .edit()
            .putBoolean(PreferKey.showDiscovery, true)
            .putBoolean(PreferKey.autoRefresh, false)
            .putBoolean(PreferKey.autoCheckNewBackup, false)
            .putBoolean("autoUpdateVariant", false)
            .putString(PreferKey.defaultHomePage, "explore")
            .commit()
        LocalConfig.edit()
            .putBoolean("privacyPolicyOk", true)
            .putLong("appVersionCode", appInfo.versionCode)
            .putString("password", "")
            .commit()
        appDb.bookSourceDao.insert(*sources.toTypedArray())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("main destination migration") { it.hostMigration.value.ready }
        scenario!!.onActivity { it.explore.query("group:$group") }
        await("fixture sources") { it.explore.state.value.sources.size == sources.size }
        compose.onNodeWithText(source.bookSourceName).performClick()
        await("initial categories") {
            it.explore.state.value.controls.any { row ->
                row.label == "Rendered + Short list"
            }
        }
    }

    @After
    fun cleanUp() {
        scenario?.onActivity { activity ->
            preDraw?.let {
                activity.window.decorView.viewTreeObserver.removeOnPreDrawListener(it)
            }
        }
        scenario?.close()
        sources.forEach { fixture ->
            runBlocking { fixture.clearExploreKindsCache() }
            fixture.removeLoginHeader()
            exploreInfoMapList.remove(fixture.bookSourceUrl)
            appDb.bookSourceDao.delete(fixture.bookSourceUrl)
        }
        preferences
            .edit()
            .clear()
            .apply { savedPreferences.forEach { (key, value) -> putValue(key, value) } }
            .commit()
        LocalConfig.edit()
            .clear()
            .apply { savedLocal.forEach { (key, value) -> putValue(key, value) } }
            .commit()
    }

    @Test
    fun internalToggleGrowsAndShrinksBelowTheVisibleControl() {
        positionControls()
        verifyRefresh(
            "explore-toggle-expand",
            "+ More categories",
            "- More categories",
            "Rendered - Short list",
        ) {
            compose.onNodeWithText("+ More categories").performClick()
        }
        verifyRefresh(
            "explore-toggle-collapse",
            "- More categories",
            "+ More categories",
            "Rendered + Short list",
        ) {
            compose.onNodeWithText("- More categories").performClick()
        }
    }

    @Test
    fun internalSelectGrowsAndShrinksBelowTheVisibleControl() {
        positionControls()
        for (mode in listOf("Long list", "Short list")) {
            verifyRefresh(
                "explore-select-${mode.substringBefore(' ')}",
                "Category mode",
                "Category mode",
                "Rendered + $mode",
            ) {
                compose.onNodeWithTag("explore-home-control:37")
                    .onChildren().filter(hasClickAction()).onFirst().performClick()
                compose.onNodeWithText(mode).performClick()
            }
        }
    }

    @Test
    fun discoveryControlsKeepTheirRefreshCallbackThroughGarbageCollection() {
        source.putLoginHeader("""{"forceGc":true}""")
        positionControls()
        for (mode in listOf("Long list", "Short list")) {
            verifyRefresh(
                "explore-select-gc-${mode.substringBefore(' ')}",
                "Category mode",
                "Category mode",
                "Rendered + $mode",
            ) {
                compose.onNodeWithTag("explore-home-control:37")
                    .onChildren().filter(hasClickAction()).onFirst().performClick()
                compose.onNodeWithText(mode).performClick()
            }
            assertTrue(
                "Actual JS must observe GC before refresh",
                source.getLoginHeader().orEmpty().contains("\"gcObserved\":true"),
            )
        }
    }

    @Test
    fun discoveryInputRemainsVisibleAboveTheActualKeyboard() {
        val input = compose.onNodeWithTag("explore-home-control:38")
        input.performScrollTo()
        val originalHeight =
            compose.onNodeWithTag("explore-home-list").fetchSemanticsNode().boundsInRoot.height
        screenshot("explore-input-before-keyboard")
        input.performClick().performTextReplacement("reader")
        await("actual keyboard visible") {
            val insets = ViewCompat.getRootWindowInsets(it.window.decorView)
            insets?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0
        }
        screenshot("explore-input-with-keyboard")
        input.assertIsDisplayed().assertIsFocused().assertTextContains("reader")
        val inputBottom = input.fetchSemanticsNode().boundsInWindow.bottom
        scenario!!.onActivity { activity ->
            val decor = activity.window.decorView
            val keyboard =
                checkNotNull(ViewCompat.getRootWindowInsets(decor))
                    .getInsets(WindowInsetsCompat.Type.ime())
                    .bottom
            assertTrue("IME must have a measurable height", keyboard > 0)
            assertTrue(
                "Input bottom $inputBottom is hidden behind keyboard",
                inputBottom <= decor.height - keyboard,
            )
        }
        closeSoftKeyboard()
        await("keyboard dismissed") {
            ViewCompat.getRootWindowInsets(it.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == false
        }
        screenshot("explore-input-keyboard-dismissed")
        assertEquals(
            "Viewport recovers after hiding IME",
            originalHeight,
            compose.onNodeWithTag("explore-home-list").fetchSemanticsNode().boundsInRoot.height,
            1f,
        )
        input.assertTextContains("reader")
    }

    private fun positionControls() {
        // Keep both the toggle and the following select fully inside the viewport.
        compose.onNodeWithTag("explore-home-control:37").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("explore-home-control:36").assertIsDisplayed()
        scenario!!.onActivity { activity ->
            preDraw =
                ViewTreeObserver.OnPreDrawListener {
                        val state = activity.explore.state.value
                        frames.add(
                            "expanded=${state.expandedUrl}, controls=${state.controls.size}, loading=${state.panelLoading}"
                        )
                        true
                    }
                    .also {
                        activity.window.decorView.viewTreeObserver.addOnPreDrawListener(it)
                    }
        }
    }

    private fun verifyRefresh(
        name: String,
        beforeText: String,
        afterText: String,
        rendered: String,
        action: () -> Unit,
    ) {
        val controlId = if (beforeText == "Category mode") 37 else 36
        val tag = "explore-home-control:$controlId"
        val beforeBounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        var beforeCount = 0
        scenario!!.onActivity {
            beforeCount = it.explore.state.value.controls.size
            frames.clear()
        }
        screenshot("$name-before")
        try {
            action()
            await(rendered) {
                it.explore.state.value.controls.any { control ->
                    control.label == rendered
                }
            }
            compose.waitForIdle()
            if (controlId == 36) {
                compose.waitUntil(timeoutMillis = 15_000) {
                    runCatching {
                        compose.onNodeWithText(afterText).assertIsDisplayed()
                    }.isSuccess
                }
            }
            compose.onNodeWithTag(tag).assertIsDisplayed()
            if (controlId == 36) compose.onNodeWithText(afterText).assertIsDisplayed()
            val afterBounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
            assertTrue(
                "Control jumped: before=$beforeBounds after=$afterBounds",
                abs(beforeBounds.top - afterBounds.top) <=
                    2f * context.resources.displayMetrics.density,
            )
            scenario!!.onActivity { activity ->
                val state = activity.explore.state.value
                assertEquals(
                    "Following sources must not replace expanded owner",
                    source.bookSourceUrl,
                    state.expandedUrl,
                )
                assertTrue(
                    "Actual JS must change category count",
                    abs(state.controls.size - beforeCount) >= 30,
                )
                assertTrue(
                    "Intermediate frames must retain controls",
                    frames.none { it.contains("controls=0") },
                )
            }
        } finally {
            screenshot("$name-after")
            var recorded = ""
            scenario!!.onActivity { recorded = frames.joinToString("\n") }
            File(context.getExternalFilesDir("ui-regression"), "$name-frames.txt")
                .writeText(recorded)
        }
    }

    private val MainActivity.explore: ExploreHomeViewModel
        get() = exploreHomeModel

    private fun await(description: String, condition: (MainActivity) -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 15_000) {
                var ready = false
                scenario!!.onActivity { ready = condition(it) }
                ready
            }
            return
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            // Capture a compact fixture state without dumping source or session contents.
        }
        var diagnostics = ""
        scenario!!.onActivity {
            val state = it.explore.state.value
            diagnostics = "ready=${it.hostMigration.value.ready}, queryMatches=${state.query == "group:$group"}, " +
                "sources=${state.sources.size}/${sources.size}, groupPresent=${group in state.groups}, " +
                "loaded=${state.sessionLoaded}, loading=${state.loading}, busy=${state.busy}, " +
                "destination=${it.viewModel.uiState.value.selectedDestination}"
            val insets = ViewCompat.getRootWindowInsets(it.window.decorView)
            diagnostics += ", imeVisible=${insets?.isVisible(WindowInsetsCompat.Type.ime())}, " +
                "imeBottom=${insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom}, " +
                "decorHeight=${it.window.decorView.height}, softInputMode=${it.window.attributes.softInputMode}"
        }
        throw AssertionError("Timed out waiting for $description: $diagnostics")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val rendered = CountDownLatch(1)
        scenario!!.onActivity {
            it.window.decorView.postOnAnimation {
                it.window.decorView.postOnAnimation { rendered.countDown() }
            }
        }
        assertTrue("Window rendered before screenshot", rendered.await(5, TimeUnit.SECONDS))
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun SharedPreferences.Editor.putValue(key: String, value: Any?) {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is String -> putString(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            null -> remove(key)
        }
    }
}
