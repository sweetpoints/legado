package io.legado.app.ui.flutter

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.flutter.embedding.android.FlutterFragment
import io.flutter.embedding.engine.FlutterEngine
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.sourceEngine.DartSourceEngine
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.book.source.manage.SourceManagerDialog
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.main.my.openMyItem
import io.legado.app.utils.defaultSharedPreferences
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Requires an AAR containing the real embeddedUiMain entrypoint, not a mock. */
@RunWith(AndroidJUnit4::class)
class EmbeddedFlutterActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val savedPrefs = prefs.all
    private val savedLocal = LocalConfig.all
    private var savedAccessibilityFlags: Int? = null
    private var nativeScenario: ActivityScenario<MainActivity>? = null
    private val source = BookSource(
        bookSourceUrl = "https://embedded-${UUID.randomUUID()}.invalid/",
        bookSourceName = "Embedded Flutter ${UUID.randomUUID()}",
        customOrder = Int.MIN_VALUE,
    )

    @Before
    fun prepareNativeCaller() {
        instrumentation.uiAutomation.serviceInfo.let { info ->
            savedAccessibilityFlags = info.flags
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            instrumentation.uiAutomation.serviceInfo = info
        }
        prefs.edit()
            .putString(PreferKey.language, "en")
            .putString(PreferKey.defaultHomePage, "my")
            .putBoolean(PreferKey.autoRefresh, false)
            .putBoolean(PreferKey.autoCheckNewBackup, false)
            .putBoolean("autoUpdateVariant", false)
            .commit()
        LocalConfig.edit()
            .putBoolean("privacyPolicyOk", true)
            .putLong("appVersionCode", appInfo.versionCode)
            .putString("password", "")
            .putInt("bookSourceHelpVersion", 1)
            .putInt("ruleHelpVersion", 1)
            .commit()
        appDb.bookSourceDao.insert(source)
        nativeScenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun restoreOwnedFixture(): Unit = runBlocking {
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filter { it is BookSourceActivity || it is BookSourceEditActivity }
                .forEach { it.finish() }
        }
        nativeScenario?.close()
        appDb.bookSourceDao.delete(source.bookSourceUrl)
        DartSourceEngine.clearSourceState(source)
        prefs.edit().clear().apply {
            savedPrefs.forEach { (key, value) -> putValue(key, value) }
        }.commit()
        LocalConfig.edit().clear().apply {
            savedLocal.forEach { (key, value) -> putValue(key, value) }
        }.commit()
        savedAccessibilityFlags?.let { saved ->
            instrumentation.uiAutomation.serviceInfo.let { info ->
                info.flags = saved
                instrumentation.uiAutomation.serviceInfo = info
            }
        }
    }

    @Test
    fun normalManagerMutatesRoomRestoresQueryAndSelectionAndKeepsHeadlessEngineAlive() = runBlocking {
        assertEquals(41, (DartSourceEngine.evaluate(source, "globalThis.__embeddedHostProbe=41;__embeddedHostProbe") as Number).toInt())
        nativeScenario!!.onActivity { it.openMyItem("bookSourceManage") }
        val first = awaitManager()
        awaitRendered(source.bookSourceName)
        val firstEngine = engine(first)
        setRenderedText("source-manager-query", source.bookSourceName)
        awaitCondition("Normal Flutter manager query did not reach the retained native state") {
            first.managerModel.state.value.query == source.bookSourceName &&
                renderedValue("source-manager-query").contains(source.bookSourceName)
        }
        // Query input opens the real IME. Close it before testing the dialog's
        // Back handling; otherwise Android correctly consumes Back to hide it.
        closeSoftKeyboard()
        clickIdentifier("select:${source.bookSourceUrl}")
        awaitChecked("select:${source.bookSourceUrl}", true)
        clickIdentifier("enabled:${source.bookSourceUrl}")
        awaitCondition("Flutter switch did not update the existing Room source") {
            appDb.bookSourceDao.getBookSource(source.bookSourceUrl)?.enabled == false
        }
        awaitChecked("enabled:${source.bookSourceUrl}", false)

        clickIdentifier("source-manager-menu")
        clickIdentifier("source-manager-action:online")
        dismissManagerDialogWithBack(first)
        assertSame(first, awaitManager())
        awaitChecked("select:${source.bookSourceUrl}", true)
        // Capture only the synthetic filtered row for visual acceptance.
        requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
            try {
                File(context.cacheDir, "source-manager-acceptance.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
        }

        // The Activity retains its established native route. Flutter engine
        // selection cannot be supplied by a stale/debug launch Intent.
        instrumentation.runOnMainSync {
            first.intent.apply {
                action = Intent.ACTION_RUN
                putExtra("cached_engine_id", "headless-engine-must-not-be-used")
                putExtra("dart_entrypoint", "mustNotRun")
                putExtra("initial_route", "/must-not-navigate")
            }
            first.recreate()
        }
        val recreated = awaitManager(excluding = first)
        awaitCondition("Recreated manager lost the actual Flutter search value") {
            renderedValue("source-manager-query").contains(source.bookSourceName)
        }
        awaitChecked("select:${source.bookSourceUrl}", true)
        awaitChecked("enabled:${source.bookSourceUrl}", false)
        assertNotSame(firstEngine, engine(recreated))
        assertEquals(42, (DartSourceEngine.evaluate(source, "__embeddedHostProbe+1") as Number).toInt())

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitChecked("select:${source.bookSourceUrl}", false)
        assertSame(recreated, awaitManager())
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitNative()
        assertEquals(43, (DartSourceEngine.evaluate(source, "__embeddedHostProbe+2") as Number).toInt())
    }

    @Test
    fun normalManagerOpensFlutterEditorAndReturnsToTheSameFlutterPage() = runBlocking {
        assertEquals(17, (DartSourceEngine.evaluate(source, "globalThis.__managerEditProbe=17") as Number).toInt())
        nativeScenario!!.onActivity { it.openMyItem("bookSourceManage") }
        val manager = awaitManager()
        awaitRendered(source.bookSourceName)
        setRenderedText("source-manager-query", source.bookSourceName)
        closeSoftKeyboard()
        clickIdentifier("source-manager-action:edit:${source.bookSourceUrl}")
        awaitCondition("Flutter row action did not open the source editor host") {
            resumed().any { it is BookSourceEditActivity }
        }
        assertEquals(18, (DartSourceEngine.evaluate(source, "__managerEditProbe+1") as Number).toInt())
        FlutterUiTestDriver(instrumentation).use { driver ->
            driver.enableAccessibility()
            driver.awaitIdentifier("source-field-0-bookSourceName")
            assertEquals(source.bookSourceName, driver.text("source-field-0-bookSourceName"))
            driver.click("source-close")
        }
        assertSame(manager, awaitManager())
        awaitRendered(source.bookSourceName)
        assertEquals(source.bookSourceName, appDb.bookSourceDao.getBookSource(source.bookSourceUrl)?.bookSourceName)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitNative()
        assertEquals(19, (DartSourceEngine.evaluate(source, "__managerEditProbe+2") as Number).toInt())
    }

    private fun engine(activity: BookSourceActivity): FlutterEngine {
        val value = AtomicReference<FlutterEngine>()
        instrumentation.runOnMainSync {
            val fragment = activity.supportFragmentManager.findFragmentByTag("source-manager-flutter")
            assertTrue("Normal source Activity must render a real FlutterFragment", fragment is FlutterFragment)
            assertEquals("io.legado.app.ui.book.source.manage.SourceManagerFlutterFragment", fragment!!.javaClass.name)
            val getter = FlutterFragment::class.java.getDeclaredMethod("getFlutterEngine")
            getter.isAccessible = true
            value.set(getter.invoke(fragment) as FlutterEngine)
        }
        return value.get()
    }

    private fun resumed(): List<android.app.Activity> {
        val value = AtomicReference<List<android.app.Activity>>()
        instrumentation.runOnMainSync {
            value.set(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList())
        }
        return value.get()
    }

    private fun awaitManager(excluding: BookSourceActivity? = null): BookSourceActivity {
        var result: BookSourceActivity? = null
        awaitCondition("Normal book source manager did not resume") {
            result = resumed().filterIsInstance<BookSourceActivity>().firstOrNull { it !== excluding }
            result != null
        }
        return requireNotNull(result)
    }

    private fun awaitNative() = awaitCondition("Back did not return to native caller") {
        resumed().any { it is MainActivity } && resumed().none { it is BookSourceActivity }
    }

    @Suppress("DEPRECATION")
    private fun identifierExists(identifier: String): Boolean {
        val node = renderedNode(identifier = identifier) ?: return false
        node.recycle()
        return true
    }

    @Suppress("DEPRECATION")
    private fun renderedValue(identifier: String): String {
        val node = renderedNode(identifier = identifier) ?: return ""
        val value = node.text?.toString().orEmpty()
        node.recycle()
        return value
    }

    private fun awaitIdentifier(identifier: String) = awaitCondition("Flutter semantics missing: $identifier") {
        identifierExists(identifier)
    }

    @Suppress("DEPRECATION")
    private fun awaitChecked(identifier: String, expected: Boolean) = awaitCondition("Flutter checked state mismatch: $identifier=$expected") {
        val node = renderedNode(identifier = identifier) ?: return@awaitCondition false
        val matches = node.isCheckable && node.isChecked == expected
        node.recycle()
        matches
    }

    private fun setRenderedText(identifier: String, text: String) {
        FlutterUiTestDriver(instrumentation).use { driver ->
            driver.enableAccessibility()
            driver.setText(identifier, text)
        }
    }

    private fun clickIdentifier(identifier: String) {
        awaitCondition("Rendered Flutter action is not clickable: $identifier") {
            renderedNode(identifier = identifier)?.let(::clickNode) ?: false
        }
    }

    @Suppress("DEPRECATION")
    private fun awaitRendered(text: String) = awaitCondition("Flutter did not render: $text") {
        val node = renderedNode(text)
        val found = node != null
        node?.recycle()
        found
    }

    @Suppress("DEPRECATION")
    private fun clickNode(initial: AccessibilityNodeInfo): Boolean {
        // A stable identifier may wrap a Tooltip/Button subtree rather than the
        // node that owns ACTION_CLICK. Stay inside that identified control first.
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(AccessibilityNodeInfo.obtain(initial))
        while (pending.isNotEmpty()) {
            val child = pending.removeFirst()
            if (child.isVisibleToUser && child.isEnabled && child.isClickable &&
                child.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                child.recycle()
                pending.forEach { it.recycle() }
                initial.recycle()
                return true
            }
            for (index in 0 until child.childCount) child.getChild(index)?.let(pending::add)
            child.recycle()
        }
        var node: AccessibilityNodeInfo? = initial
        while (node != null) {
            if (node.isVisibleToUser && node.isEnabled && node.isClickable &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                node.recycle()
                return true
            }
            val parent = node.parent
            node.recycle()
            node = parent
        }
        return false
    }

    /** Native accessibility reads the actual Flutter semantic tree, with no
     * dependency on UIAutomator and no assertions on the Activity's class alone.
     */
    @Suppress("DEPRECATION")
    private fun renderedNode(text: String? = null, identifier: String? = null): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            val labels = listOf(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty())
            if (node.isVisibleToUser && ((identifier != null && node.viewIdResourceName == identifier) ||
                    (text != null && labels.any { it.contains(text) }))) {
                pending.forEach { it.recycle() }
                return node
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { pending.add(it) }
            node.recycle()
        }
        return null
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail(message)
    }

    /** One Back after the actual IME viewport settles; every phase shares the old 30s budget. */
    private fun dismissManagerDialogWithBack(activity: BookSourceActivity) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        val root = activity.window.decorView
        val running = mutableSetOf<WindowInsetsAnimationCompat>()
        var diagnostic = "viewport not sampled"
        fun nativeState(expected: SourceManagerDialog?): Boolean {
            var matched = false
            instrumentation.runOnMainSync {
                val state = activity.managerModel.state.value
                val insets = ViewCompat.getRootWindowInsets(root)
                diagnostic = "imeVisible=${insets?.isVisible(WindowInsetsCompat.Type.ime())}, " +
                    "imeBottom=${insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom}, " +
                    "animations=${running.size}, attached=${root.isAttachedToWindow}, " +
                    "laidOut=${root.isLaidOut}, windowFocus=${root.hasWindowFocus()}, " +
                    "dialog=${state.dialog}, busy=${state.busy}"
                matched = state.dialog == expected && !state.busy
            }
            return matched
        }
        fun awaitPhase(message: String, condition: () -> Boolean) {
            while (SystemClock.elapsedRealtime() < deadline) {
                if (condition()) return
                SystemClock.sleep(25)
            }
            fail("$message; $diagnostic")
        }
        fun viewportSettled(): Boolean {
            val acknowledged = nativeState(SourceManagerDialog.IMPORT)
            var settled = false
            instrumentation.runOnMainSync {
                val insets = ViewCompat.getRootWindowInsets(root)
                settled = root.isAttachedToWindow && root.isLaidOut && root.hasWindowFocus() &&
                    insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime()) && running.isEmpty()
            }
            return acknowledged && settled
        }
        awaitPhase("Flutter manager dialog did not acknowledge IMPORT") {
            nativeState(SourceManagerDialog.IMPORT) && identifierExists("source-manager-dialog")
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
            // The menu/dialog transition can restore query focus after the earlier hide.
            closeSoftKeyboard()
            awaitPhase("Manager dialog IME did not become hidden and idle before Back") {
                viewportSettled()
            }
            instrumentation.runOnMainSync {
                assertTrue("Manager viewport needs a hardware frame; $diagnostic", root.isHardwareAccelerated)
                root.viewTreeObserver.registerFrameCommitCallback(frame)
                root.postInvalidateOnAnimation()
            }
            awaitPhase("Manager dialog viewport frame did not commit before Back") {
                viewportSettled() && committed.count == 0L
            }
            assertTrue("Back budget exhausted before injection; $diagnostic", SystemClock.elapsedRealtime() < deadline)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitPhase("Back did not dismiss the Flutter manager dialog") {
                nativeState(null) && identifierExists("source-manager-query") &&
                    !identifierExists("source-manager-dialog")
            }
        } finally {
            instrumentation.runOnMainSync {
                root.viewTreeObserver.unregisterFrameCommitCallback(frame)
                ViewCompat.setWindowInsetsAnimationCallback(root, null)
            }
        }
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
