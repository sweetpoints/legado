package io.legado.app.ui.rss.source.manage

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.gson.JsonParser
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import io.flutter.embedding.android.FlutterFragment
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.rssSourceManagementId
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.flutter.FlutterUiTestDriver
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

/** Launches the normal Android entry and drives actual Dart semantics and Room mutations. */
class FlutterRssManagerUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val driver = FlutterUiTestDriver(instrumentation)
    private val context get() = instrumentation.targetContext
    private val name = "RSS-manager-${UUID.randomUUID()}"
    private val first = RssSource("https://$name.invalid/a", "$name A")
    private val second = RssSource("https://$name.invalid/b", "$name B")
    private val id = rssSourceManagementId(first.sourceUrl)
    private val preferences get() = context.defaultSharedPreferences
    private val originalLanguage = preferences.getString(PreferKey.language, null)
    private val hadBackup = preferences.contains(PreferKey.autoCheckNewBackup)
    private val hadPrivacy = LocalConfig.contains("privacyPolicyOk")
    private val hadVersion = LocalConfig.contains("appVersionCode")
    private val originalBackup = preferences.getBoolean(PreferKey.autoCheckNewBackup, true)
    private val originalPrivacy = LocalConfig.getBoolean("privacyPolicyOk", false)
    private val originalVersion = LocalConfig.getLong("appVersionCode", 0)
    private var scenario: ActivityScenario<RssSourceActivity>? = null

    @Before fun setup() {
        preferences.edit().putString(PreferKey.language, "en").putBoolean(PreferKey.autoCheckNewBackup, false).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true).putLong("appVersionCode", appInfo.versionCode).commit()
        appDb.rssSourceDao.insert(first, second)
        driver.enableAccessibility()
        scenario = ActivityScenario.launch(RssSourceActivity::class.java)
        driver.await("native manager loaded") {
            var loaded = false
            scenario!!.onActivity { loaded = it.managementModel.state.value.loaded }
            loaded
        }
        scenario!!.onActivity { activity ->
            assertTrue(activity.supportFragmentManager.fragments.any { it is FlutterFragment })
        }
        driver.awaitIdentifier("rss-manager-query")
    }

    @After fun cleanup() {
        try {
            scenario?.close()
        } finally {
            try {
                driver.close()
            } finally {
                try {
                    appDb.rssSourceDao.delete(first.sourceUrl)
                    appDb.rssSourceDao.delete(second.sourceUrl)
                } finally {
                    preferences.edit().apply {
                        if (originalLanguage == null) remove(PreferKey.language) else putString(PreferKey.language, originalLanguage)
                        if (hadBackup) putBoolean(PreferKey.autoCheckNewBackup, originalBackup) else remove(PreferKey.autoCheckNewBackup)
                    }.commit()
                    LocalConfig.edit().apply {
                        if (hadPrivacy) putBoolean("privacyPolicyOk", originalPrivacy) else remove("privacyPolicyOk")
                        if (hadVersion) putLong("appVersionCode", originalVersion) else remove("appVersionCode")
                    }.commit()
                }
            }
        }
    }

    @Test fun realQuerySelectionAndRoomEnabledMutationSurviveActivityRecreation() {
        driver.setText("rss-manager-query", name)
        driver.await("query acknowledged by native VM") {
            var matches = false
            scenario!!.onActivity { matches = it.managementModel.state.value.query == name && it.managementModel.state.value.rows.size == 2 }
            matches
        }
        closeSoftKeyboard()
        driver.click("rss-manager-select-$id")
        driver.await("selection acknowledged") {
            var selected = false
            scenario!!.onActivity { selected = id in it.managementModel.state.value.selected }
            selected
        }
        driver.click("rss-manager-enabled-$id")
        driver.await("native Room changed enabled") { appDb.rssSourceDao.getByKey(first.sourceUrl)?.enabled == false }
        driver.await("native mutation settled") {
            var idle = false
            scenario!!.onActivity { idle = !it.managementModel.state.value.busy }
            idle
        }
        scenario!!.recreate()
        driver.await("restored session query and selection") {
            var restored = false
            scenario!!.onActivity { restored = it.managementModel.state.value.loaded && it.managementModel.state.value.query == name && id in it.managementModel.state.value.selected }
            restored
        }
        driver.awaitIdentifier("rss-manager-select-$id")
        assertEquals(name, driver.text("rss-manager-query"))
        assertFalse(appDb.rssSourceDao.getByKey(first.sourceUrl)!!.enabled)
    }
    @Test fun nativeBridgeRejectsBackgroundCommandsAndRetiredBindings() {
        class Owner(base: Context) : ContextWrapper(base), LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val owner = Owner(context)
        val messenger = object : BinaryMessenger {
            override fun send(channel: String, message: ByteBuffer?) {}
            override fun send(channel: String, message: ByteBuffer?, callback: BinaryMessenger.BinaryReply?) { callback?.reply(null) }
            override fun setMessageHandler(channel: String, handler: BinaryMessenger.BinaryMessageHandler?) {}
        }
        var bridge: RssManagerChannelBridge? = null
        scenario!!.onActivity { bridge = RssManagerChannelBridge(owner, messenger, it.managementModel) {} }
        fun request(method: String, args: Any? = null): Pair<Any?,String?> {
            val results = LinkedBlockingQueue<Pair<Any?,String?>>()
            instrumentation.runOnMainSync {
                bridge!!.onMethodCall(MethodCall(method,args),object : MethodChannel.Result {
                    override fun success(result: Any?) { results.offer(result to null) }
                    override fun error(code: String, message: String?, details: Any?) {results.offer(null to code)}
                    override fun notImplemented() {results.offer(null to "not_implemented")}
                })
            }
            return requireNotNull(results.poll(5, TimeUnit.SECONDS))
        }
        try {
            val binding = JsonParser.parseString(request("snapshot").first as String).asJsonObject["binding"].asString
            instrumentation.runOnMainSync { owner.registry.currentState = Lifecycle.State.STARTED }
            val args = mapOf("op" to "query", "value" to name,"start" to name.length,"end" to name.length,"binding" to binding,"sequence" to 1)
            assertEquals("invalid_request",request("command",args).second)
            scenario!!.onActivity { assertNotEquals(name,it.managementModel.state.value.query) }
            instrumentation.runOnMainSync {owner.registry.currentState = Lifecycle.State.RESUMED}
            assertNull(request("command",args).second)
            scenario!!.onActivity {assertEquals(name,it.managementModel.state.value.query)}
            instrumentation.runOnMainSync {bridge!!.close()}
            scenario!!.onActivity {bridge=RssManagerChannelBridge(owner,messenger,it.managementModel){}}
            val replacement = JsonParser.parseString(request("snapshot").first as String).asJsonObject["binding"].asString
            assertNotEquals(binding,replacement)
            assertEquals("invalid_request",request("command",args + ("sequence" to 2)).second)
        } finally {
            instrumentation.runOnMainSync {bridge?.close()}
        }
    }

    @Test fun recreatedFlutterDialogSystemBackCancelsWithoutClosingTheManagerOrWritingRoom() {
        driver.setText("rss-manager-query", name)
        driver.await("filtered rows") {
            var ready = false
            scenario!!.onActivity { ready = it.managementModel.state.value.rows.size == 2 && it.managementModel.state.value.query == name }
            ready
        }
        closeSoftKeyboard()
        driver.click("rss-manager-select-$id")
        driver.click("rss-manager-bulk")
        driver.click("rss-manager-action:AddGroup")
        driver.awaitIdentifier("rss-manager-draft")
        driver.setText("rss-manager-draft", "private-$name")
        closeSoftKeyboard()
        driver.await("dialog draft persisted") {
            var saved = false
            scenario!!.onActivity { saved = it.managementModel.state.value.draft == "private-$name" }
            saved
        }
        scenario!!.recreate()
        driver.awaitIdentifier("rss-manager-draft")
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        driver.await("restored Dart dialog consumed system back") {
            var cancelled = false
            scenario!!.onActivity { cancelled = !it.isFinishing && it.managementModel.state.value.loaded && it.managementModel.state.value.dialog == null }
            cancelled
        }
        assertNull(appDb.rssSourceDao.getByKey(first.sourceUrl)!!.sourceGroup)
    }

}
