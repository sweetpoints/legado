package io.legado.app.ui.book.source.edit

import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.Stage
import io.flutter.embedding.android.FlutterFragment
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.R
import io.github.rosemoe.sora.widget.CodeEditor
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.flutter.FlutterUiTestDriver
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.utils.defaultSharedPreferences
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the normal Activity, actual Dart semantics, and production Room persistence. */
@RunWith(AndroidJUnit4::class)
class FlutterSourceEditorUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val savedPrefs = prefs.all
    private val savedLocal = LocalConfig.all
    private val driver = FlutterUiTestDriver(instrumentation)
    private var scenario: ActivityScenario<BookSourceEditActivity>? = null
    private val source = BookSource(
        bookSourceUrl = "https://editor-${UUID.randomUUID()}.invalid/",
        bookSourceName = "Editor fixture ${UUID.randomUUID()}",
        weight = 73,
        customOrder = -872,
        respondTime = 4242,
    )
    private val replacementUrl = source.bookSourceUrl + "renamed/"

    @Before
    fun prepare() {
        driver.enableAccessibility()
        prefs.edit().putString(PreferKey.language, "en")
            .putBoolean(PreferKey.autoCheckNewBackup, false).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true)
            .putLong("appVersionCode", appInfo.versionCode)
            .putInt("ruleHelpVersion", 1).putInt("bookSourceHelpVersion", 1).commit()
        appDb.bookSourceDao.insert(source)
    }

    @After
    fun cleanup() {
        scenario?.close()
        appDb.bookSourceDao.delete(source.bookSourceUrl)
        appDb.bookSourceDao.delete(replacementUrl)
        driver.close()
        prefs.edit().clear().apply { savedPrefs.forEach { (key, value) -> putValue(key, value) } }.commit()
        LocalConfig.edit().clear().apply { savedLocal.forEach { (key, value) -> putValue(key, value) } }.commit()
    }

    @Test
    fun editsEveryFormFieldAndOptionThroughFlutterAndSavesActualRoomRow() {
        launch()
        EditorImeBarrier().use { ime ->
            driver.click("source-options-toggle")
            driver.awaitIdentifier("source-option-enabled")
            listOf("enabled", "enabledExplore", "enabledCookieJar", "enabledReview", "eventListener", "customButton")
                .forEach { key ->
                    revealIn("source-option-$key", "source-options")
                    driver.click("source-option-$key")
                }
            // Type follows the six option chips at the trailing edge of the row.
            revealIn("source-type", "source-options")
            driver.click("source-type")
            driver.click("source-type-2")
            driver.click("source-options-toggle")
            val expected = projectBookSourceEditForm(source)
            val values = mutableMapOf<Pair<Int, String>, String>()
            expected.tabs.forEachIndexed { tab, fields ->
                selectTab(tab)
                fields.forEach { field ->
                    val value = when (field.key) {
                        "bookSourceUrl" -> source.bookSourceUrl
                        "maxBatchSize" -> "17"
                        else -> "fixture-$tab-${field.key}"
                    }
                    revealField(tab, field.key)
                    driver.setText("source-field-$tab-${field.key}", value) {
                        ime.settled(true)
                    }
                    values[tab to field.key] = value
                    closeSoftKeyboard()
                    ime.settled(false)
                }
            }
            driver.click("source-save")
            awaitFinished()
            val saved = requireNotNull(appDb.bookSourceDao.getBookSource(source.bookSourceUrl))
            val actual = projectBookSourceEditForm(saved)
            values.forEach { (field, value) ->
                assertEquals("Saved field ${field.first}/${field.second}", value,
                    actual.field(field.first, field.second)?.value)
            }
            assertFalse(actual.options.enabled)
            assertFalse(actual.options.enabledExplore)
            assertFalse(actual.options.enabledCookieJar)
            assertTrue(actual.options.enabledReview)
            assertTrue(actual.options.eventListener)
            assertTrue(actual.options.customButton)
            assertEquals(2, actual.options.type)
            // These fields are absent from the edit form and must survive a UI round trip.
            assertEquals(source.weight, saved.weight)
            assertEquals(source.customOrder, saved.customOrder)
            assertEquals(source.respondTime, saved.respondTime)
        }
    }

    @Test
    fun dirtyCloseCanContinueEditingAndDiscardDoesNotWriteRoom() {
        launch()
        driver.setText("source-field-0-bookSourceName", "unsaved change")
        closeSoftKeyboard()
        driver.click("source-close")
        driver.click("source-discard-cancel")
        driver.await("Draft retained after Continue editing") {
            driver.text("source-field-0-bookSourceName") == "unsaved change"
        }
        assertEquals(source.bookSourceName, appDb.bookSourceDao.getBookSource(source.bookSourceUrl)?.bookSourceName)
        driver.click("source-close")
        driver.click("source-discard-confirm")
        awaitFinished()
        assertEquals(source.bookSourceName, appDb.bookSourceDao.getBookSource(source.bookSourceUrl)?.bookSourceName)
    }

    @Test
    fun recreatedHostRestoresUnsavedFlutterDraftAndTabWithoutWritingRoom() {
        launch()
        EditorImeBarrier().use { ime ->
            fun input(tab: Int, key: String, text: String) {
                val deadline = SystemClock.uptimeMillis() + 30_000
                driver.setText("source-field-$tab-$key", text) { ime.settled(true) }
                // ACTION_SET_TEXT can update Android's predicted semantics immediately.
                // Confirm the real private draft before hiding the IME or recreating.
                var acknowledged = false
                while (SystemClock.uptimeMillis() < deadline) {
                    scenario!!.onActivity {
                        acknowledged = it.editorModel.state.value.document?.form?.field(tab, key)?.value == text
                    }
                    if (acknowledged) break
                    SystemClock.sleep(50)
                }
                assertTrue("Native editor draft did not acknowledge field $tab/$key", acknowledged)
                ime.settled(true)
                closeSoftKeyboard()
                ime.settled(false)
            }
            input(0, "bookSourceName", "retained draft")
            selectTab(4)
            input(4, "preUpdateJs", "retainedToc()")
        }
        scenario!!.recreate()
        driver.awaitIdentifier("source-field-4-preUpdateJs")
        driver.await("Restored actual Flutter TOC field") {
            driver.text("source-field-4-preUpdateJs") == "retainedToc()"
        }
        selectTab(0)
        driver.await("Restored actual Flutter name field") {
            driver.text("source-field-0-bookSourceName") == "retained draft"
        }
        assertEquals(source.bookSourceName, appDb.bookSourceDao.getBookSource(source.bookSourceUrl)?.bookSourceName)
        driver.click("source-menu")
        driver.awaitIdentifier("source-menu-DEBUG")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        driver.await("Restored editor Back closes only the popup") {
            val popup = driver.node("source-menu-DEBUG")
            val popupClosed = popup == null
            popup?.recycle()
            popupClosed
        }
        assertNull("Popup Back must not request draft discard", driver.node("source-discard-confirm"))
        driver.awaitIdentifier("source-field-0-bookSourceName")
        driver.click("source-close")
        driver.click("source-discard-confirm")
        awaitFinished()
    }

    @Test
    fun savingChangedAddressMovesIdentityInsteadOfLeavingDuplicateSource() {
        launch()
        driver.setText("source-field-0-bookSourceUrl", replacementUrl)
        closeSoftKeyboard()
        driver.setText("source-field-0-bookSourceName", "moved source")
        closeSoftKeyboard()
        driver.click("source-save")
        awaitFinished()
        assertNull(appDb.bookSourceDao.getBookSource(source.bookSourceUrl))
        val saved = requireNotNull(appDb.bookSourceDao.getBookSource(replacementUrl))
        assertEquals("moved source", saved.bookSourceName)
        assertEquals(source.weight, saved.weight)
        assertEquals(source.customOrder, saved.customOrder)
    }

    @Test
    fun oversizedRuleOpensNativeEditorWithCompletePrivateFileAndCancelKeepsRoomUnchanged() {
        val originalRule = "a".repeat(30_000)
        source.ruleSearch = SearchRule(name = originalRule)
        appDb.bookSourceDao.insert(source)
        launch()
        selectTab(1)
        revealField(1, "name")
        val nativeEditor = AtomicReference<CodeEditActivity>()
        val nativeIntent = AtomicReference<Intent>()
        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        // The real code editor clears its launch payload after accepting ownership.
        // Capture the original handoff before onCreate rather than racing that cleanup.
        val captureLaunch = ActivityLifecycleCallback { activity, stage ->
            if (activity is CodeEditActivity && stage == Stage.PRE_ON_CREATE) {
                nativeIntent.compareAndSet(null, Intent(activity.intent))
                nativeEditor.compareAndSet(null, activity)
            }
        }
        instrumentation.runOnMainSync { lifecycle.addLifecycleCallback(captureLaunch) }
        try {
            driver.click("source-field-1-name")
            driver.await("Real native editor receives oversized Flutter field") {
                var resumed = false
                instrumentation.runOnMainSync {
                    resumed = lifecycle.getActivitiesInStage(Stage.RESUMED)
                        .any { it is CodeEditActivity && it === nativeEditor.get() }
                }
                resumed
            }
            assertNotNull("Native transfer must be captured before editor initialization",
                nativeIntent.get())
            assertTrue("Large rule must use the private file transport",
                nativeIntent.get().getBooleanExtra("useTextFile", false))
            assertNull("Large rule must not be duplicated into a Binder text extra",
                nativeIntent.get().getStringExtra("text"))
            val textFile = File(requireNotNull(nativeIntent.get().getStringExtra("textFile")))
            assertTrue("Native editor transfer file must exist", textFile.isFile)
            assertEquals("Native transfer must contain the complete 30k rule",
                originalRule, textFile.readText(Charsets.UTF_8))
        } finally {
            instrumentation.runOnMainSync {
                lifecycle.removeLifecycleCallback(captureLaunch)
                nativeEditor.get()?.finish()
            }
        }
        driver.awaitIdentifier("source-field-1-name")
        assertEquals(originalRule,
            appDb.bookSourceDao.getBookSource(source.bookSourceUrl)?.ruleSearch?.name)
        driver.click("source-close")
        awaitFinished()
        assertEquals("Canceling external editing must leave the persisted rule untouched",
            originalRule, appDb.bookSourceDao.getBookSource(source.bookSourceUrl)?.ruleSearch?.name)
    }

    @Test
    fun sourceRuleFullscreenSaveReturnsToFlutterDraftThenCommitsOnlyOnSourceSave() {
        val originalRule = "a@text"
        val editedRule = "h1@text"
        source.ruleSearch = SearchRule(name = originalRule)
        appDb.bookSourceDao.insert(source)
        launch()
        selectTab(1)
        revealField(1, "name")
        driver.click("source-field-1-name")
        closeSoftKeyboard()
        driver.click("source-fullscreen")
        val nativeEditor = AtomicReference<CodeEditActivity>()
        try {
            driver.await("Fullscreen rule loads in actual native text surface") {
                var ready = false
                instrumentation.runOnMainSync {
                    val activity = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<CodeEditActivity>().singleOrNull()
                    val text = activity?.findViewById<CodeEditor>(R.id.editText)
                    ready = text?.isShown == true && text.isEditable && text.text.toString() == originalRule
                    if (ready) nativeEditor.set(activity)
                }
                ready
            }
            driver.awaitIdentifier("code-search-toggle")
            instrumentation.runOnMainSync {
                val editor = nativeEditor.get().findViewById<CodeEditor>(R.id.editText)
                editor.text.replace(0, editor.text.length, editedRule)
            }
            driver.click("code-save")
            driver.await("Fullscreen result updates the visible Flutter rule draft") {
                driver.text("source-field-1-name") == editedRule
            }
            assertEquals("Fullscreen return must not prematurely save the whole source", originalRule,
                appDb.bookSourceDao.getBookSource(source.bookSourceUrl)?.ruleSearch?.name)
            driver.click("source-save")
            awaitFinished()
            assertEquals(editedRule,
                appDb.bookSourceDao.getBookSource(source.bookSourceUrl)?.ruleSearch?.name)
        } finally {
            instrumentation.runOnMainSync {
                nativeEditor.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.finish()
            }
        }
    }

    /** Local to the source editor tests; one text action follows a settled actual IME viewport. */
    private inner class EditorImeBarrier : AutoCloseable {
        private val runningImeAnimations = mutableSetOf<WindowInsetsAnimationCompat>()
        private lateinit var root: View
        init {
            scenario!!.onActivity { activity ->
                root = activity.window.decorView
                ViewCompat.setWindowInsetsAnimationCallback(root,
                    object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                        override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                            if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0)
                                runningImeAnimations.add(animation)
                        }
                        override fun onProgress(
                            insets: WindowInsetsCompat,
                            runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                        ): WindowInsetsCompat = insets
                        override fun onEnd(animation: WindowInsetsAnimationCompat) {
                            runningImeAnimations.remove(animation)
                        }
                    })
            }
        }
        fun settled(visible: Boolean) {
            driver.await("IME visible=$visible and viewport animation completed") {
                var settled = false
                instrumentation.runOnMainSync {
                    val insets = ViewCompat.getRootWindowInsets(root)
                    settled = root.isAttachedToWindow && root.isLaidOut &&
                        insets?.isVisible(WindowInsetsCompat.Type.ime()) == visible &&
                        runningImeAnimations.isEmpty()
                }
                settled
            }
            val committed = CountDownLatch(1)
            instrumentation.runOnMainSync {
                assertTrue(root.isAttachedToWindow && root.isHardwareAccelerated)
                root.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                root.postInvalidateOnAnimation()
            }
            assertTrue("Editor viewport frame was not committed", committed.await(5, TimeUnit.SECONDS))
            instrumentation.uiAutomation.waitForIdle(100, 5_000)
        }
        override fun close() {
            instrumentation.runOnMainSync { ViewCompat.setWindowInsetsAnimationCallback(root, null) }
        }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(Intent(context, BookSourceEditActivity::class.java)
            .putExtra("sourceUrl", source.bookSourceUrl))
        driver.awaitIdentifier("source-field-0-bookSourceName")
        scenario!!.onActivity { activity ->
            assertTrue("Normal editor must contain FlutterFragment",
                activity.supportFragmentManager.fragments.any { it is FlutterFragment })
        }
        assertEquals(source.bookSourceName, driver.text("source-field-0-bookSourceName"))
    }

    private fun selectTab(tab: Int) {
        revealIn("source-tab-$tab", "source-tabs", backwards = tab == 0)
        driver.click("source-tab-$tab")
        // ACTION_CLICK only queues the Dart/native round trip. Do not scroll a
        // newly appearing container before its first field semantics are ready.
        val first = projectBookSourceEditForm(source).tabs[tab].first().key
        driver.awaitIdentifier("source-field-$tab-$first")
    }

    private fun revealField(tab: Int, key: String) = revealIn("source-field-$tab-$key", "source-fields-$tab")

    @Suppress("DEPRECATION")
    private fun revealIn(target: String, container: String?, backwards: Boolean = false) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        var scrolls = 0
        while (SystemClock.uptimeMillis() < deadline) {
            driver.node(target)?.let { it.recycle(); return }
            val root = if (container == null) instrumentation.uiAutomation.rootInActiveWindow else driver.node(container)
            val pending = ArrayDeque<AccessibilityNodeInfo>()
            root?.let(pending::add)
            var scrolled = false
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                if (!scrolled && node.isVisibleToUser && node.isScrollable &&
                    node.performAction(if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) scrolled = true
                if (!scrolled) for (index in 0 until node.childCount) node.getChild(index)?.let(pending::add)
                node.recycle()
            }
            if (scrolled) {
                scrolls++
                assertTrue("Exceeded scroll bound revealing $target", scrolls <= 60)
                SystemClock.sleep(100)
            } else SystemClock.sleep(50)
        }
        fail("Missing rendered editor field/control $target in $container")
    }

    private fun awaitFinished() = driver.await("Source editor finishes only after durable save/discard") {
        scenario!!.state == androidx.lifecycle.Lifecycle.State.DESTROYED
    }

    private fun SharedPreferences.Editor.putValue(key: String, value: Any?) {
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            null -> remove(key)
            else -> error("Unsupported preference fixture: $key")
        }
    }
}
