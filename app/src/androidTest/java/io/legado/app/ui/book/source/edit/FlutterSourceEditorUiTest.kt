package io.legado.app.ui.book.source.edit

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
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
            fun until(deadline: Long, description: String, condition: () -> Boolean) {
                while (SystemClock.uptimeMillis() < deadline) {
                    if (condition()) return
                    SystemClock.sleep(minOf(50L, deadline - SystemClock.uptimeMillis()).coerceAtLeast(0))
                }
                fail("$description; ${ime.diagnostic()}")
            }
            fun clickWithin(id: String, deadline: Long) {
                until(deadline, "Missing clickable control $id") {
                    val initial = driver.node(id) ?: return@until false
                    val pending = ArrayDeque<AccessibilityNodeInfo>()
                    pending.add(AccessibilityNodeInfo.obtain(initial))
                    while (pending.isNotEmpty()) {
                        val node = pending.removeFirst()
                        if (node.isVisibleToUser && node.isEnabled && node.isClickable &&
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            node.recycle(); pending.forEach { it.recycle() }; initial.recycle()
                            return@until true
                        }
                        for (i in 0 until node.childCount) node.getChild(i)?.let(pending::add)
                        node.recycle()
                    }
                    var ancestor: AccessibilityNodeInfo? = initial
                    while (ancestor != null) {
                        if (ancestor.isVisibleToUser && ancestor.isEnabled && ancestor.isClickable &&
                            ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            ancestor.recycle(); return@until true
                        }
                        val parent = ancestor.parent
                        ancestor.recycle(); ancestor = parent
                    }
                    false
                }
            }
            fun input(tab: Int, key: String, text: String,
                alreadyFocused: Boolean = false, preFocus: (Long) -> Unit = {}) {
                val deadline = SystemClock.uptimeMillis() + 30_000
                val id = "source-field-$tab-$key"
                ime.observedFieldId = id
                preFocus(deadline)
                if (!alreadyFocused) clickWithin(id, deadline)
                until(deadline, "Focused editable source field $tab/$key") {
                    driver.node(id)?.let { node ->
                        try { node.isFocused && node.isEditable && node.isEnabled &&
                            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } }
                        finally { node.recycle() }
                    } ?: false
                }
                ime.settled(true, deadline)
                // The viewport boundary can replace semantics. Dispatch exactly once through
                // the fresh real focused field, then require both rendered and private ACKs.
                val field = requireNotNull(driver.node(id))
                try {
                    assertTrue(field.isFocused && field.isEditable && field.isEnabled)
                    assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    }))
                } finally { field.recycle() }
                until(deadline, "Native and rendered source draft ACK $tab/$key") {
                    var acknowledged = false
                    scenario!!.onActivity {
                        acknowledged = it.editorModel.state.value.document?.form?.field(tab, key)?.value == text
                    }
                    acknowledged && driver.text(id) == text
                }
                ime.settled(true, deadline)
                closeSoftKeyboard()
                ime.settled(false, deadline)
            }
            input(0, "bookSourceName", "retained draft")
            input(4, "preUpdateJs", "retainedToc()", alreadyFocused = true) { deadline ->
                revealIn("source-tab-4", "source-tabs", deadline = deadline)
                clickWithin("source-tab-4", deadline)
                until(deadline, "Native TOC tab with its cleared focus and rendered first field") {
                    var nativeTab = false
                    scenario!!.onActivity {
                        nativeTab = it.editorModel.state.value.document?.let { doc ->
                            doc.selectedTab == 4 && doc.focusedKey == null
                        } == true
                    }
                    val firstId = "source-field-4-${projectBookSourceEditForm(source).tabs[4].first().key}"
                    val first = driver.node(firstId)
                    val rendered = first != null
                    first?.recycle()
                    nativeTab && rendered
                }
                ime.committedFrame(deadline)
                // Clearing focus during the tab change has its own hide transition.
                // Settle it before requesting the next real field's FocusNode.
                ime.settled(false, deadline)
                clickWithin("source-nav-4-preUpdateJs", deadline)
                until(deadline, "Native TOC focus and actual editable field") {
                    var focused = false
                    scenario!!.onActivity {
                        focused = it.editorModel.state.value.document?.let { doc ->
                            doc.selectedTab == 4 && doc.focusedKey == "preUpdateJs"
                        } == true
                    }
                    val node = driver.node("source-field-4-preUpdateJs")
                    val rendered = node?.let {
                        try { it.isFocused && it.isEditable && it.isEnabled }
                        finally { it.recycle() }
                    } ?: false
                    focused && rendered
                }
                ime.committedFrame(deadline)
            }
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
        run {
            val deadline = SystemClock.uptimeMillis() + 30_000
            lateinit var root: View
            scenario!!.onActivity { root = it.window.decorView }
            val running = mutableSetOf<WindowInsetsAnimationCompat>()
            var diagnostic = "viewport not sampled"
            fun awaitPhase(message: String, condition: () -> Boolean) {
                while (SystemClock.uptimeMillis() < deadline) {
                    if (condition()) return
                    SystemClock.sleep(minOf(25L, deadline - SystemClock.uptimeMillis()).coerceAtLeast(0))
                }
                fail("$message; $diagnostic")
            }
            fun nativeAndViewport(requireHidden: Boolean): Boolean {
                var ready = false
                scenario!!.onActivity { activity ->
                    val state = activity.editorModel.state.value
                    val doc = state.document
                    val insets = ViewCompat.getRootWindowInsets(root)
                    val nativeReady = activity.window.decorView === root && !state.busy &&
                        !state.confirmDiscard && doc?.selectedTab == 0 && !doc.finished &&
                        doc.form.field(0, "bookSourceName")?.value == "retained draft"
                    diagnostic = "attached=${root.isAttachedToWindow},laidOut=${root.isLaidOut}," +
                        "windowFocus=${root.hasWindowFocus()},imeVisible=${insets?.isVisible(WindowInsetsCompat.Type.ime())}," +
                        "imeBottom=${insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom},animations=${running.size}," +
                        "busy=${state.busy},discard=${state.confirmDiscard},tab=${doc?.selectedTab},nativeDraftMatches=$nativeReady"
                    ready = nativeReady && root.isAttachedToWindow && root.isLaidOut && root.hasWindowFocus() &&
                        (!requireHidden || (insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime()) && running.isEmpty()))
                }
                return ready
            }
            val committed = CountDownLatch(1)
            val frame = Runnable { committed.countDown() }
            instrumentation.runOnMainSync {
                ViewCompat.setWindowInsetsAnimationCallback(root,
                    object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                        override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                            if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) running.add(animation)
                        }
                        override fun onProgress(insets: WindowInsetsCompat,
                            runningAnimations: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                            running.clear()
                            running.addAll(runningAnimations.filter { it.typeMask and WindowInsetsCompat.Type.ime() != 0 })
                            return insets
                        }
                        override fun onEnd(animation: WindowInsetsAnimationCompat) { running.remove(animation) }
                    })
            }
            try {
                awaitPhase("Restored source menu did not accept its single actual click") {
                    if (!nativeAndViewport(false)) return@awaitPhase false
                    val initial = driver.node("source-menu") ?: return@awaitPhase false
                    val pending = ArrayDeque<AccessibilityNodeInfo>()
                    pending.add(AccessibilityNodeInfo.obtain(initial))
                    while (pending.isNotEmpty()) {
                        val node = pending.removeFirst()
                        if (node.isVisibleToUser && node.isEnabled && node.isClickable &&
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            node.recycle(); pending.forEach { it.recycle() }; initial.recycle()
                            return@awaitPhase true
                        }
                        for (i in 0 until node.childCount) node.getChild(i)?.let(pending::add)
                        node.recycle()
                    }
                    var ancestor: AccessibilityNodeInfo? = initial
                    while (ancestor != null) {
                        if (ancestor.isEnabled && ancestor.isClickable &&
                            ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            ancestor.recycle(); return@awaitPhase true
                        }
                        val parent = ancestor.parent
                        ancestor.recycle(); ancestor = parent
                    }
                    false
                }
                awaitPhase("Restored source DEBUG popup did not become rendered") {
                    val node = driver.node("source-menu-DEBUG")
                    val shown = node?.isEnabled == true
                    node?.recycle()
                    shown
                }
                // A restored field can reopen the IME. Menu presence precedes completion
                // of its hide transition; otherwise this single Back is consumed by IME.
                closeSoftKeyboard()
                awaitPhase("Restored popup IME did not become hidden and idle before Back") {
                    nativeAndViewport(true)
                }
                instrumentation.runOnMainSync {
                    assertTrue("Restored popup needs a hardware frame; $diagnostic", root.isHardwareAccelerated)
                    root.viewTreeObserver.registerFrameCommitCallback(frame)
                    root.postInvalidateOnAnimation()
                }
                awaitPhase("Restored popup viewport frame did not commit before Back") {
                    nativeAndViewport(true) && committed.count == 0L
                }
                assertTrue("Popup Back budget exhausted before injection; $diagnostic", SystemClock.uptimeMillis() < deadline)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                awaitPhase("Restored editor Back closes only the popup") {
                    val popup = driver.node("source-menu-DEBUG")
                    val popupClosed = popup == null
                    popup?.recycle()
                    popupClosed && nativeAndViewport(false)
                }
            } finally {
                instrumentation.runOnMainSync {
                    root.viewTreeObserver.unregisterFrameCommitCallback(frame)
                    ViewCompat.setWindowInsetsAnimationCallback(root, null)
                }
            }
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
        private var prepared = 0
        private var ended = 0
        var observedFieldId: String? = null
        init {
            scenario!!.onActivity { activity ->
                root = activity.window.decorView
                ViewCompat.setWindowInsetsAnimationCallback(root,
                    object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                        override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                            if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                                prepared++
                                runningImeAnimations.add(animation)
                            }
                        }
                        override fun onProgress(
                            insets: WindowInsetsCompat,
                            runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                        ): WindowInsetsCompat = insets
                        override fun onEnd(animation: WindowInsetsAnimationCompat) {
                            if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) ended++
                            runningImeAnimations.remove(animation)
                        }
                    })
            }
        }
        fun diagnostic(): String {
            var result = ""
            instrumentation.runOnMainSync {
                val insets = ViewCompat.getRootWindowInsets(root)
                result = "attached=${root.isAttachedToWindow},laidOut=${root.isLaidOut}," +
                    "visible=${insets?.isVisible(WindowInsetsCompat.Type.ime())}," +
                    "bottom=${insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom}," +
                    "running=${runningImeAnimations.size},prepared=$prepared,ended=$ended"
            }
            val node = observedFieldId?.let(driver::node)
            return result + (node?.let {
                try { ",focused=${it.isFocused},editable=${it.isEditable},enabled=${it.isEnabled}" }
                finally { it.recycle() }
            } ?: ",observedFieldMissing=true")
        }
        fun settled(visible: Boolean, deadline: Long? = null) {
            fun ready(): Boolean {
                var settled = false
                instrumentation.runOnMainSync {
                    val insets = ViewCompat.getRootWindowInsets(root)
                    settled = root.isAttachedToWindow && root.isLaidOut &&
                        insets?.isVisible(WindowInsetsCompat.Type.ime()) == visible &&
                        runningImeAnimations.isEmpty()
                }
                return settled
            }
            if (deadline == null) {
                try { driver.await("IME visible=$visible and viewport animation completed", ::ready) }
                catch (failure: AssertionError) {
                    throw AssertionError("${failure.message}; ${diagnostic()}", failure)
                }
            } else {
                var accepted = false
                while (SystemClock.uptimeMillis() < deadline) {
                    if (ready()) { accepted = true; break }
                    SystemClock.sleep(minOf(50L, deadline - SystemClock.uptimeMillis()).coerceAtLeast(0))
                }
                assertTrue("IME visible=$visible did not settle within input phase; ${diagnostic()}", accepted)
            }
            committedFrame(deadline)
            val idleBudget = deadline?.let { minOf(5_000L, it - SystemClock.uptimeMillis()) } ?: 5_000L
            assertTrue("Input phase exhausted before viewport idle; ${diagnostic()}", idleBudget >= 100)
            instrumentation.uiAutomation.waitForIdle(100, idleBudget)
            if (deadline != null) assertTrue("Input phase exceeded while settling viewport; ${diagnostic()}",
                SystemClock.uptimeMillis() < deadline)
        }
        fun committedFrame(deadline: Long? = null) {
            val committed = CountDownLatch(1)
            instrumentation.runOnMainSync {
                assertTrue(root.isAttachedToWindow && root.isHardwareAccelerated)
                root.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                root.postInvalidateOnAnimation()
            }
            val frameBudget = deadline?.let { minOf(5_000L, it - SystemClock.uptimeMillis()).coerceAtLeast(0) } ?: 5_000L
            val accepted = committed.await(frameBudget, TimeUnit.MILLISECONDS)
            assertTrue("Editor viewport frame was not committed; ${diagnostic()}", accepted)
            if (deadline != null) assertTrue("Input phase exceeded committing viewport; ${diagnostic()}",
                SystemClock.uptimeMillis() < deadline)
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
    private fun revealIn(target: String, container: String?, backwards: Boolean = false,
        deadline: Long? = null) {
        val phaseDeadline = deadline ?: (SystemClock.uptimeMillis() + 30_000)
        var scrolls = 0
        while (SystemClock.uptimeMillis() < phaseDeadline) {
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
                SystemClock.sleep(if (deadline == null) 100 else
                    minOf(100L, phaseDeadline - SystemClock.uptimeMillis()).coerceAtLeast(0))
            } else SystemClock.sleep(if (deadline == null) 50 else
                minOf(50L, phaseDeadline - SystemClock.uptimeMillis()).coerceAtLeast(0))
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
