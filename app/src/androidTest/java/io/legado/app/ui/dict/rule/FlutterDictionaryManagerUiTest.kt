package io.legado.app.ui.dict.rule

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import io.flutter.embedding.android.FlutterFragment
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.DictRule
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.flutter.FlutterUiTestDriver
import io.legado.app.utils.defaultSharedPreferences
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** Actual normal Activity, rendered Flutter controls and native Room state. */
class FlutterDictionaryManagerUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val driver = FlutterUiTestDriver(instrumentation)
    private val preferences get() = context.defaultSharedPreferences
    private val language = preferences.getString(PreferKey.language, null)
    private val hadBackup = preferences.contains(PreferKey.autoCheckNewBackup)
    private val backup = preferences.getBoolean(PreferKey.autoCheckNewBackup, true)
    private val hadPrivacy = LocalConfig.contains("privacyPolicyOk")
    private val privacy = LocalConfig.getBoolean("privacyPolicyOk", false)
    private val hadVersion = LocalConfig.contains("appVersionCode")
    private val version = LocalConfig.getLong("appVersionCode", 0)
    private val suffix = UUID.randomUUID().toString()
    private val first = DictRule("Dictionary-A-$suffix", "url-A", "show-A", true, Int.MIN_VALUE)
    private val second = DictRule("Dictionary-B-$suffix", "url-B", "show-B", true, Int.MIN_VALUE + 1)
    private lateinit var original: List<DictRule>
    private var scenario: ActivityScenario<DictRuleActivity>? = null

    @Before fun launch() {
        preferences.edit().putString(PreferKey.language, "en").putBoolean(PreferKey.autoCheckNewBackup, false).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true).putLong("appVersionCode", appInfo.versionCode).commit()
        original = appDb.dictRuleDao.all
        appDb.dictRuleDao.insert(first, second)
        driver.enableAccessibility()
        scenario = ActivityScenario.launch(DictRuleActivity::class.java)
        driver.await("dictionary Room loaded") {
            var loaded = false
            scenario!!.onActivity { loaded = !it.viewModel.state.value.loading }
            loaded
        }
        scenario!!.onActivity { assertTrue(it.supportFragmentManager.fragments.any { fragment -> fragment is FlutterFragment }) }
        driver.awaitIdentifier("dictionary-select-${first.name}")
    }

    @After fun restore() {
        try { scenario?.close() } finally {
            try { driver.close() } finally {
                appDb.dictRuleDao.delete(first, second)
                if (::original.isInitialized) appDb.dictRuleDao.insert(*original.toTypedArray())
                preferences.edit().apply {
                    if (language == null) remove(PreferKey.language) else putString(PreferKey.language, language)
                    if (hadBackup) putBoolean(PreferKey.autoCheckNewBackup, backup) else remove(PreferKey.autoCheckNewBackup)
                }.commit()
                LocalConfig.edit().apply {
                    if (hadPrivacy) putBoolean("privacyPolicyOk", privacy) else remove("privacyPolicyOk")
                    if (hadVersion) putLong("appVersionCode", version) else remove("appVersionCode")
                }.commit()
            }
        }
    }

    @Test fun actualSelectionEnabledRoomAndRecreatePreserveMetadata() {
        driver.click("dictionary-select-${first.name}")
        driver.await("selected native identity") { var yes = false; scenario!!.onActivity { yes = first.name in it.viewModel.state.value.selected }; yes }
        driver.click("dictionary-enabled-${first.name}")
        driver.await("native Room enabled changed") { appDb.dictRuleDao.getByName(first.name)?.enabled == false }
        driver.await("enabled operation complete") { var idle = false; scenario!!.onActivity { idle = !it.viewModel.state.value.busy }; idle }
        scenario!!.recreate()
        driver.awaitIdentifier("dictionary-select-${first.name}")
        driver.await("selected identity restored") { var yes = false; scenario!!.onActivity { yes = first.name in it.viewModel.state.value.selected }; yes }
        val saved = requireNotNull(appDb.dictRuleDao.getByName(first.name))
        assertFalse(saved.enabled)
        assertEquals(first.urlRule, saved.urlRule)
        assertEquals(first.showRule, saved.showRule)
        assertEquals(first.sortNumber, saved.sortNumber)
    }

    @Test fun actualAccessibleReorderCommitsRoomAndOnlineDraftRecreatesThenBackCancels() {
        driver.click("dictionary-more-${first.name}")
        driver.click("dictionary-action:down")
        driver.await("Room moved exact first behind second") {
            val rows = appDb.dictRuleDao.all.map { it.name }
            rows.indexOf(first.name) > rows.indexOf(second.name)
        }
        driver.await("reorder operation complete") { var idle = false; scenario!!.onActivity { idle = !it.viewModel.state.value.busy }; idle }
        driver.click("dictionary-menu")
        driver.click("dictionary-action:online")
        val url = "https://dictionary-$suffix.invalid/rules.json"
        driver.setText("dictionary-online-input", url)
        driver.await("online draft acknowledged") { var yes = false; scenario!!.onActivity { yes = it.viewModel.state.value.onlineInput == url }; yes }
        closeSoftKeyboard()
        scenario!!.recreate()
        driver.awaitIdentifier("dictionary-online-input")
        assertEquals(url, driver.text("dictionary-online-input"))
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        driver.await("back dismissed only online dialog") { var yes = false; scenario!!.onActivity { yes = !it.viewModel.state.value.online && !it.isFinishing }; yes }
        assertEquals(first.urlRule, appDb.dictRuleDao.getByName(first.name)!!.urlRule)
        assertEquals(second.showRule, appDb.dictRuleDao.getByName(second.name)!!.showRule)
    }
    @Test fun actualManagerOpensFlutterEditorAndRoomChangesReturnToRenderedList() {
        driver.click("dictionary-edit-${first.name}")
        driver.awaitIdentifier("dictionary-rule-UrlRule")
        scenario!!.onActivity {
            assertTrue(it.supportFragmentManager.fragments.any { fragment -> fragment is DictRuleEditDialog })
        }
        val updated = "${first.urlRule}-edited-from-manager"
        driver.setText("dictionary-rule-UrlRule", updated)
        driver.await("editor native field ACK inside Flutter manager host") {
            var accepted = false
            scenario!!.onActivity { activity ->
                val editor = activity.supportFragmentManager.fragments.filterIsInstance<DictRuleEditDialog>().singleOrNull()
                accepted = editor?.viewModel?.state?.value?.urlRule == updated
            }
            accepted
        }
        closeSoftKeyboard()
        driver.click("dictionary-rule-save")
        driver.await("editor dismissed and Room change delivered back to manager") {
            var returned = false
            scenario!!.onActivity { activity ->
                returned = activity.supportFragmentManager.fragments.none { it is DictRuleEditDialog } &&
                    activity.viewModel.state.value.rules.any { it.name == first.name && it.urlRule == updated }
            }
            returned
        }
        driver.awaitIdentifier("dictionary-select-${first.name}")
        val saved = requireNotNull(appDb.dictRuleDao.getByName(first.name))
        assertEquals(updated, saved.urlRule)
        assertEquals(first.showRule, saved.showRule)
        assertEquals(first.enabled, saved.enabled)
        assertEquals(first.sortNumber, saved.sortNumber)
        scenario!!.recreate()
        driver.awaitIdentifier("dictionary-select-${first.name}")
        scenario!!.onActivity { activity ->
            assertTrue(activity.viewModel.state.value.rules.any { it.name == first.name && it.urlRule == updated })
        }
    }

    @Test fun physicalSlideAndReorderCancelOnPauseWithoutRoomWrite() {
        val firstBox = driver.bounds("dictionary-select-${first.name}")
        val secondBox = driver.bounds("dictionary-select-${second.name}")
        driver.touchDown(firstBox.exactCenterX(), firstBox.exactCenterY()).use { touch ->
            touch.moveTo(secondBox.exactCenterX(), secondBox.exactCenterY())
            driver.await("physical slide selected exact range") {
                var selected = false
                scenario!!.onActivity { selected = it.viewModel.state.value.selected == setOf(first.name, second.name) }
                selected
            }
            scenario!!.moveToState(Lifecycle.State.CREATED)
        }
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        driver.await("paused slide restored native baseline") {
            var restored = false
            scenario!!.onActivity { restored = it.viewModel.state.value.selected.isEmpty() }
            restored
        }
        val nameLeft = driver.bounds("dictionary-select-${first.name}").right.toFloat()
        val nameRight = driver.bounds("dictionary-enabled-${first.name}").left.toFloat()
        val y = driver.bounds("dictionary-select-${first.name}").exactCenterY()
        val destinationY = driver.bounds("dictionary-select-${second.name}").exactCenterY()
        driver.touchDown((nameLeft + nameRight) / 2, y).use { touch ->
            touch.hold(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 100)
            touch.moveTo((nameLeft + nameRight) / 2, destinationY)
            driver.await("physical reorder preview acknowledged") {
                var moved = false
                scenario!!.onActivity { activity ->
                    val names = activity.viewModel.state.value.rules.map { it.name }
                    moved = names.indexOf(first.name) > names.indexOf(second.name)
                }
                moved
            }
            scenario!!.moveToState(Lifecycle.State.CREATED)
        }
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        driver.await("paused reorder restored order") {
            var restored = false
            scenario!!.onActivity { activity ->
                val names = activity.viewModel.state.value.rules.map { it.name }
                restored = names.indexOf(first.name) < names.indexOf(second.name)
            }
            restored
        }
        assertEquals(first.sortNumber, appDb.dictRuleDao.getByName(first.name)!!.sortNumber)
        assertEquals(second.sortNumber, appDb.dictRuleDao.getByName(second.name)!!.sortNumber)
    }

}
