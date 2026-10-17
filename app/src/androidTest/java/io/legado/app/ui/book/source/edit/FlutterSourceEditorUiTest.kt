package io.legado.app.ui.book.source.edit

import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.flutter.embedding.android.FlutterFragment
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.flutter.FlutterUiTestDriver
import io.legado.app.utils.defaultSharedPreferences
import java.util.ArrayDeque
import java.util.UUID
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
        driver.click("source-options-toggle")
        listOf("enabled", "enabledExplore", "enabledCookieJar", "enabledReview", "eventListener", "customButton")
            .forEach { key ->
                revealIn("source-option-$key", "source-options")
                driver.click("source-option-$key")
            }
        revealIn("source-type", "source-options", backwards = true)
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
                driver.setText("source-field-$tab-${field.key}", value)
                values[tab to field.key] = value
                closeSoftKeyboard()
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
        driver.setText("source-field-0-bookSourceName", "retained draft")
        closeSoftKeyboard()
        selectTab(4)
        driver.setText("source-field-4-preUpdateJs", "retainedToc()")
        closeSoftKeyboard()
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
