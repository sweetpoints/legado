package io.legado.app.ui.main.rss

import android.content.SharedPreferences
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.navigation.MainDestination
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.mainRssId
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class MainRssHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.defaultSharedPreferences
    private val beforePreferences = preferences.all
    private val beforeLocal = LocalConfig.all
    private val group = "main-rss-${UUID.randomUUID()}"
    private val source = RssSource(sourceUrl = "https://$group.invalid", sourceName = group, sourceGroup = group)
    private var scenario: ActivityScenario<MainActivity>? = null
    @Before fun before() {
        preferences.edit().putBoolean(PreferKey.showRss, true).putBoolean(PreferKey.autoRefresh, false)
            .putBoolean(PreferKey.autoCheckNewBackup, false).putBoolean("autoUpdateVariant", false)
            .putString(PreferKey.defaultHomePage, "rss").commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true).putLong("appVersionCode", appInfo.versionCode).putString("password", "").commit()
        runBlocking(Dispatchers.IO) { appDb.rssSourceDao.insert(source) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(timeoutMillis = 20_000) { compose.onAllNodesWithTag("main-rss-search").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("main-rss-search").performTextReplacement("group:$group")
        awaitRows()
    }
    @After fun after() {
        scenario?.close(); runBlocking(Dispatchers.IO) { appDb.rssSourceDao.delete(source.sourceUrl) }
        preferences.edit().clear().apply { beforePreferences.forEach { (key, value) -> restore(key, value) } }.commit()
        LocalConfig.edit().clear().apply { beforeLocal.forEach { (key, value) -> restore(key, value) } }.commit()
    }
    private fun SharedPreferences.Editor.restore(key: String, value: Any?) {
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }
    private fun awaitRows() { compose.waitUntil(timeoutMillis = 20_000) {
        var ready = false
        scenario!!.onActivity { ready = it.supportFragmentManager.fragments.filterIsInstance<RssFragment>().any { page ->
            page.homeModel.state.value.query == "group:$group" && page.homeModel.state.value.rows.singleOrNull()?.sourceUrl == source.sourceUrl
        } }
        ready
    } }
    @Test fun realMainPagerKeepsExactGroupQuerySelectionAndSingleComposeFragmentAcrossRecreate() {
        compose.onNodeWithTag("main-rss-card-${mainRssId(source.sourceUrl)}").assertIsDisplayed()
        compose.onNodeWithTag("main-rss-search").performTextInputSelection(androidx.compose.ui.text.TextRange(2, 7))
        scenario!!.recreate(); awaitRows()
        compose.onNodeWithTag("main-rss-search").assertTextContains("group:$group")
        scenario!!.onActivity { activity ->
            val rss = activity.supportFragmentManager.fragments.filterIsInstance<RssFragment>()
            assertEquals(1, rss.size); assertEquals(2, rss.single().homeModel.state.value.queryStart)
            assertEquals(7, rss.single().homeModel.state.value.queryEnd)
        }
    }
    @Test fun switchingRealMainPagerHidesConfirmationAndReturnsToPrivateDraftWithoutDeleting() {
        val id = mainRssId(source.sourceUrl)
        compose.onNodeWithTag("main-rss-card-$id").performTouchInput { longClick() }
        compose.onNodeWithTag("main-rss-delete-$id").performClick()
        compose.onNodeWithTag("main-rss-delete-confirm").assertIsDisplayed()
        scenario!!.onActivity { it.viewModel.selectDestination(MainDestination.My) }
        compose.onNodeWithTag("main-rss-delete-confirm").assertDoesNotExist()
        scenario!!.onActivity { it.viewModel.selectDestination(MainDestination.Rss) }; awaitRows()
        compose.onNodeWithTag("main-rss-delete-confirm").assertIsDisplayed()
        compose.onNodeWithTag("main-rss-delete-cancel").performClick()
        assertNotNull(runBlocking(Dispatchers.IO) { appDb.rssSourceDao.getByKey(source.sourceUrl) })
    }
}
