package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.RuleSub
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class RuleSubscriptionDraftRepositoryTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database:AppDatabase;private lateinit var directory:File
    @Before fun before() { database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build();directory=File(context.cacheDir,"rule-subscription-${UUID.randomUUID()}") }
    @After fun after() { database.close();directory.deleteRecursively() }
    private fun store(fault:(RuleSubscriptionDraft)->Unit={})=FileRuleSubscriptionDraftRepository(context,RoomRuleSubscriptionRepository(database),directory,fault)
    private fun draft(id:Long?=null)=RuleSubscriptionDraft(editor=RuleSubscriptionEditor(id,123456,"Edited","https://new",2,true,"24",true,true),revision=3)
    @Test fun millionCharacterEditorAndNavigationRestoreWithoutTruncationAndOlderWriterCannotOverwrite()=runBlocking {
        val ticket=UUID.randomUUID().toString();val large="https://host/"+"long".repeat(250000);val value=draft().copy(editor=draft().editor!!.copy(url=large),navigation=RuleSubscriptionOpen("token",1,large))
        store().write(ticket,value);store().write(ticket,value.copy(revision=2,editor=null));assertEquals(value,store().read(ticket))
    }
    @Test fun firstJournalFailureRollsBackRoomAndKeepsEditorDraft()=runBlocking {
        val ticket=UUID.randomUUID().toString();val value=draft();val store=store { if(it.pendingSave!=null) error("journal disk full") }
        store.write(ticket,value);assertTrue(runCatching { store.save(ticket,value) }.isFailure)
        withContext(Dispatchers.IO) { assertTrue(database.ruleSubDao.all.isEmpty()) };assertEquals(value,store.read(ticket))
    }
    @Test fun finalDraftFailureAfterNewInsertRestoresStableIdentityAndCompletesOnlyOnce()=runBlocking {
        val ticket=UUID.randomUUID().toString();val value=draft();var fail=true
        val store=store { if(it.editor==null && fail) { fail=false;error("final write failed") } }
        store.write(ticket,value);assertTrue(runCatching { store.save(ticket,value) }.isFailure)
        val pending=store.read(ticket)!!;assertNotNull(pending.pendingSave)
        withContext(Dispatchers.IO) { assertEquals(123456L,database.ruleSubDao.all.single().id) }
        val restored=store().save(ticket,pending);assertNull(restored.editor);assertNull(restored.pendingSave)
        assertEquals(restored,store().read(ticket));withContext(Dispatchers.IO) { assertEquals(1,database.ruleSubDao.all.size);assertEquals(123456L,database.ruleSubDao.all.single().id) }
    }
    @Test fun renameReceiptsPreserveLatestSchedulerMetadataAndRecoverAfterFinalWriteFailure()=runBlocking {
        val old=RuleSub(123456,"Original","old",customOrder=11,update=999,js="script",showRule="show",sourceUrl="source")
        withContext(Dispatchers.IO) { database.ruleSubDao.insert(old) };val ticket=UUID.randomUUID().toString();val value=draft(old.id)
        val store=store { if(it.editor==null) error("final write failed") };store.write(ticket,value);assertTrue(runCatching { store.save(ticket,value) }.isFailure)
        val pending=store.read(ticket)!!;assertEquals(999L,pending.pendingSave!!.target.lastUpdate);assertEquals("script",pending.pendingSave!!.target.js)
        store().save(ticket,pending);withContext(Dispatchers.IO) { val row=database.ruleSubDao.all.single();assertEquals(old.id,row.id);assertEquals("https://new",row.url);assertEquals(11,row.customOrder);assertEquals("source",row.sourceUrl);assertEquals(999L,row.update) }
    }
    @Test fun externalTargetModificationOrDeletionIsNeverOverwrittenByReceiptRecovery()=runBlocking {
        val ticket=UUID.randomUUID().toString();val value=draft();val store=store { if(it.editor==null) error("final write failed") }
        store.write(ticket,value);assertTrue(runCatching { store.save(ticket,value) }.isFailure);val pending=store.read(ticket)!!
        withContext(Dispatchers.IO) { database.ruleSubDao.insert(database.ruleSubDao.all.single().copy(name="External")) }
        assertTrue(runCatching { store().save(ticket,pending) }.exceptionOrNull() is RuleSubscriptionConflict)
        withContext(Dispatchers.IO) { assertEquals("External",database.ruleSubDao.all.single().name);database.ruleSubDao.delete(database.ruleSubDao.all.single()) }
        assertTrue(runCatching { store().save(ticket,pending) }.exceptionOrNull() is RuleSubscriptionConflict)
        withContext(Dispatchers.IO) { assertTrue(database.ruleSubDao.all.isEmpty()) }
    }
    @Test fun realCloseFencesLateWriterAcrossInstancesAndRemovesLargePrivateDraftAndBackup()=runBlocking {
        val ticket=UUID.randomUUID().toString();val value=draft();store().write(ticket,value)
        File(directory,"$ticket.json.bak").writeText("private backup");File(directory,"$ticket.json.new").writeText("private unfinished")
        store().release(ticket);assertNull(store().read(ticket))
        assertTrue(runCatching { store().write(ticket,value.copy(revision=4)) }.isFailure)
        assertTrue(File(directory,"$ticket.closed").renameTo(File(directory,"$ticket.closed.bak")))
        assertTrue(runCatching { store().write(ticket,value.copy(revision=5)) }.isFailure)
        assertTrue(runCatching { store().save(ticket,value.copy(revision=5)) }.isFailure)
        store().release(ticket)
        listOf(".json",".json.bak",".json.new").forEach { assertFalse(File(directory,ticket+it).exists()) }
    }
    @Test fun duplicateAndConcurrentDeletedExistingRowKeepDraftWithoutCreatingReplacement()=runBlocking {
        withContext(Dispatchers.IO) { database.ruleSubDao.insert(RuleSub(10,"Other","https://new")) }
        val ticket=UUID.randomUUID().toString();val value=draft();store().write(ticket,value)
        assertTrue(runCatching { store().save(ticket,value) }.exceptionOrNull() is RuleSubscriptionDuplicateUrl);assertEquals(value,store().read(ticket))
        val missing=draft(20);store().write(ticket,missing.copy(revision=4));assertTrue(runCatching { store().save(ticket,missing.copy(revision=4)) }.exceptionOrNull() is RuleSubscriptionMissing)
        withContext(Dispatchers.IO) { assertEquals(listOf(10L),database.ruleSubDao.all.map { it.id }) }
    }
}
