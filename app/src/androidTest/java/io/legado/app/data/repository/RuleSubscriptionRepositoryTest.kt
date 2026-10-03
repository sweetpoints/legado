package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.RuleSub
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class RuleSubscriptionRepositoryTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database:AppDatabase
    @Before fun before(){database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()}
    @After fun after(){database.close()}
    private fun repo()=RoomRuleSubscriptionRepository(database)
    @Test fun newRowsAppendAfterHighestLegacyOrderAndPersistAllEditableFields()=runBlocking {
        withContext(Dispatchers.IO){database.ruleSubDao.insert(RuleSub(10,"Lowest","old",customOrder=0),RuleSub(20,"Highest","high",customOrder=20))}
        val saved=repo().save(RuleSubscriptionInput(name="New",url="https://new",type=2,automatic=true,interval=24,silent=true))
        assertEquals(21,saved.order);assertTrue(saved.id>0);assertEquals(2,saved.type);assertTrue(saved.automatic);assertTrue(saved.silent);assertEquals(24,saved.interval)
        assertEquals(listOf("Lowest","Highest","New"),repo().rows().first().map{it.name})
    }
    @Test fun blankAndDuplicateUrlsRejectWithoutChangingExistingRowsAndDeletedEditorCannotRecreateItsId()=runBlocking {
        withContext(Dispatchers.IO){database.ruleSubDao.insert(RuleSub(10,"Original","url"),RuleSub(20,"Other","other"))}
        assertTrue(runCatching{repo().save(RuleSubscriptionInput(url=" "))}.exceptionOrNull() is RuleSubscriptionEmptyUrl)
        val duplicate=runCatching{repo().save(RuleSubscriptionInput(20,"Changed","url"))}.exceptionOrNull() as RuleSubscriptionDuplicateUrl;assertEquals("Original",duplicate.name)
        assertEquals(listOf("Original","Other"),repo().rows().first().map{it.name})
        repo().delete(10);assertTrue(runCatching{repo().save(RuleSubscriptionInput(10,"Resurrection","url"))}.exceptionOrNull() is RuleSubscriptionMissing);assertNull(repo().load(10));assertEquals(1,repo().rows().first().size)
    }
    @Test fun editorMergesLatestSchedulerAndScriptMetadataAndKeepsExistingIdentityAndOrder()=runBlocking {
        val old=RuleSub(10,"Original","url",customOrder=7,update=1,js="old",showRule="old",sourceUrl="source")
        withContext(Dispatchers.IO){database.ruleSubDao.insert(old)};val snapshot=repo().load(10)!!
        withContext(Dispatchers.IO){database.ruleSubDao.insert(old.copy(update=999,customOrder=15,js="fresh",showRule="fresh-show",sourceUrl="fresh-source"))}
        val saved=repo().save(RuleSubscriptionInput(snapshot.id,"Edited","renamed",1,true,48,true))
        assertEquals(10L,saved.id);assertEquals(15,saved.order);assertEquals(999L,saved.lastUpdate);assertEquals("fresh",saved.js);assertEquals("fresh-show",saved.showRule);assertEquals("fresh-source",saved.sourceUrl)
        assertEquals("Original",snapshot.name);assertEquals("old",snapshot.js);assertEquals("renamed",repo().load(10)!!.url)
    }
    @Test fun dragCommitPreservesUniqueOrderNumbersAndFreshMetadataAndSkipsDeletedIds()=runBlocking {
        withContext(Dispatchers.IO){database.ruleSubDao.insert(RuleSub(10,"A","a",customOrder=10),RuleSub(20,"B","b",customOrder=20),RuleSub(30,"C","c",customOrder=30))}
        val snapshot=repo().rows().first();withContext(Dispatchers.IO){database.ruleSubDao.insert(RuleSub(20,"B changed","b",customOrder=20,update=999,js="script"));database.ruleSubDao.delete(RuleSub(id=30))}
        repo().reorder(listOf(30,20,10));val rows=repo().rows().first();assertEquals(listOf(20L,10L),rows.map{it.id});assertEquals(listOf(10,20),rows.map{it.order});assertEquals(999L,rows.first().lastUpdate);assertEquals("script",rows.first().js);assertEquals("B changed",rows.first().name);assertEquals("B",snapshot[1].name)
    }
    @Test fun tiedLegacyOrdersNormalizeOnlyOnExplicitReorderAndIncludeConcurrentlyAddedRows()=runBlocking {
        withContext(Dispatchers.IO){database.ruleSubDao.insert(RuleSub(10,"A","a"),RuleSub(20,"B","b"))};assertEquals(listOf(0,0),repo().rows().first().map{it.order})
        withContext(Dispatchers.IO){database.ruleSubDao.insert(RuleSub(30,"C","c"))};repo().reorder(listOf(20,10));assertEquals(listOf(20L,10L,30L),repo().rows().first().map{it.id});assertEquals(listOf(1,2,3),repo().rows().first().map{it.order})
    }
    @Test fun independentRepositoriesSerializeDuplicateUrlValidationInActualRoomTransactions()=runBlocking {
        val results=awaitAll(async(Dispatchers.IO){runCatching{repo().save(RuleSubscriptionInput(name="One",url="shared"))}},async(Dispatchers.IO){runCatching{repo().save(RuleSubscriptionInput(name="Two",url="shared"))}})
        assertEquals(1,results.count{it.isSuccess});assertTrue(results.single{it.isFailure}.exceptionOrNull() is RuleSubscriptionDuplicateUrl);assertEquals(1,repo().rows().first().size)
    }
}
