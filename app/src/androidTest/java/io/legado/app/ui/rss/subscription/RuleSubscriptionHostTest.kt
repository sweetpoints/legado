package io.legado.app.ui.rss.subscription

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.fragment.app.DialogFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.ui.association.*
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class RuleSubscriptionHostTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun actualRoomSubscriptionOpensMatchingNativeImporterWithOriginalPayloadAndNeverRepeatsAfterRotation() {
        val stamp=UUID.randomUUID().toString();val first=System.currentTimeMillis()+100000
        val payloads=listOf(GSON.toJson(listOf(BookSource(bookSourceUrl="book-$stamp",bookSourceName="Book fixture"))),
            GSON.toJson(listOf(RssSource(sourceUrl="rss-$stamp",sourceName="RSS fixture"))),GSON.toJson(listOf(ReplaceRule(name="Replacement fixture",pattern="before",replacement="after"))))
        val rows=payloads.mapIndexed { index,payload->RuleSub(first+index,"Fixture $index",payload,index,100000+index,js="metadata",showRule="show",sourceUrl="source") }
        val directory=File(context.filesDir,"rule-subscription-drafts");val before=directory.listFiles().orEmpty().map { it.name }.toSet();var own=emptyList<File>()
        runBlocking(Dispatchers.IO) { appDb.ruleSubDao.insert(*rows.toTypedArray()) }
        try { ActivityScenario.launch<RuleSubActivity>(Intent(context,RuleSubActivity::class.java)).use { scenario->
            compose.waitUntil(10000) { var loaded=false;scenario.onActivity { loaded=it.viewModel.state.value.loaded };loaded }
            val classes=listOf(ImportBookSourceDialog::class.java,ImportRssSourceDialog::class.java,ImportReplaceRuleDialog::class.java)
            rows.forEachIndexed { index,row->
                compose.onNodeWithTag("subscription-list").performScrollToNode(hasTestTag("subscription-row-${row.id}"));compose.onNodeWithTag("subscription-row-${row.id}").performClick()
                compose.waitUntil(10000) { var opened=false;scenario.onActivity { activity->opened=activity.supportFragmentManager.fragments.any { classes[index].isInstance(it) && it.isAdded } };opened }
                scenario.onActivity { activity->assertEquals(row.url,activity.supportFragmentManager.fragments.single { classes[index].isInstance(it) && it.isAdded }.arguments!!.getString("source"));assertNull(activity.viewModel.state.value.navigation) }
                if(index==0) {
                    scenario.recreate();compose.waitUntil(10000) { var loaded=false;scenario.onActivity { loaded=it.viewModel.state.value.loaded };loaded }
                    scenario.onActivity { activity->assertEquals(1,activity.supportFragmentManager.fragments.count { classes[index].isInstance(it) && it.isAdded });assertNull(activity.viewModel.state.value.navigation) }
                }
                scenario.onActivity { activity->activity.supportFragmentManager.fragments.filterIsInstance<DialogFragment>().forEach { it.dismissAllowingStateLoss() } };compose.waitForIdle()
            }
            own=directory.listFiles().orEmpty().filter { it.name.endsWith(".json") && it.name !in before };assertTrue(own.isNotEmpty())
            scenario.onActivity { it.finish() };compose.waitUntil(10000) { own.all { !it.exists() } }
        }
        runBlocking(Dispatchers.IO) { rows.forEach { expected->val actual=appDb.ruleSubDao.all.single { it.id==expected.id };assertEquals(GSON.toJson(expected),GSON.toJson(actual)) } }
        } finally {
            runBlocking(Dispatchers.IO) { appDb.ruleSubDao.delete(*rows.toTypedArray()) }
            own.forEach { body->listOf(".json",".json.bak",".json.new",".closed").forEach { File(body.parentFile,body.name.removeSuffix(".json")+it).delete() } }
        }
    }
}
