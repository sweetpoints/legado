package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.HighlightRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.*
import org.junit.Assert.*

class HighlightManagementRepositoryTest {
    private lateinit var database:AppDatabase
    @Before fun before(){database=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java).build()}
    @After fun after(){database.close()}
    private fun repo()=RoomHighlightManagementRepository(database)
    private suspend fun insert(vararg rows:HighlightRule)=withContext(Dispatchers.IO){database.highlightRuleDao.insert(*rows)}
    private fun row(id:Long,name:String,order:Int,group:String?=null)=HighlightRule(id=id,name=name,pattern=name,order=order,group=group)
    @Test fun enablingMergesLatestMetadataAndDoesNotRecreateDeletedSelection()=runBlocking {
        val a=row(1,"A",10);val b=row(2,"B",20);insert(a,b)
        val old=repo().rows().first()
        insert(a.copy(name="Fresh",pattern="Fresh-pattern",style="fresh-style",scope="new scope",group="New",timeoutMillisecond=99,applyToTitle=true,applyToBody=false))
        repo().delete(setOf(b.uuid));repo().enable(old.mapTo(hashSetOf()){it.uuid},false)
        val latest=repo().rows().first().single()
        assertFalse(latest.isEnabled);assertEquals("Fresh",latest.name);assertEquals("fresh-style",latest.style)
        assertEquals("new scope",latest.scope);assertEquals("New",latest.group);assertEquals(99L,latest.timeoutMillisecond)
        assertTrue(latest.applyToTitle);assertFalse(latest.applyToBody);assertEquals("A",old.first().name)
    }
    @Test fun filteredDragPreservesHiddenSlotsUniqueOrdersAndConcurrentEdits()=runBlocking {
        val a=row(1,"A",10,"Visible");val h=row(2,"Hidden",20,"Hidden");val b=row(3,"B",30,"Visible")
        insert(a,h,b);val old=repo().rows().first()
        insert(b.copy(name="Fresh B",style="fresh"),row(4,"Concurrent",40,"Hidden"))
        repo().reorder(listOf(b.uuid,a.uuid))
        val latest=repo().rows().first();assertEquals(listOf(3L,2L,1L,4L),latest.map{it.id})
        assertEquals(listOf(10,20,30,40),latest.map{it.order});assertEquals("fresh",latest.first().style)
        assertEquals("B",old.last().name)
    }
    @Test fun deletedDraggedRowIsSkippedAndHiddenRowsRemainAtTheirExistingSlots()=runBlocking {
        val a=row(1,"A",10);val h=row(2,"Hidden",20);val b=row(3,"B",30);insert(a,h,b)
        repo().delete(setOf(b.uuid));repo().reorder(listOf(b.uuid,a.uuid))
        assertEquals(listOf(1L,2L),repo().rows().first().map{it.id})
        assertEquals(listOf(10,20),repo().rows().first().map{it.order})
    }
    @Test fun tiedLegacyOrdersNormalizeExplicitlyWithHiddenRelativePositionPreserved()=runBlocking {
        val a=row(1,"A",Int.MIN_VALUE);val h=row(2,"Hidden",Int.MIN_VALUE);val b=row(3,"B",Int.MIN_VALUE)
        insert(a,h,b);assertEquals(listOf(Int.MIN_VALUE,Int.MIN_VALUE,Int.MIN_VALUE),repo().rows().first().map{it.order})
        repo().reorder(listOf(b.uuid,a.uuid));val latest=repo().rows().first()
        assertEquals(listOf(3L,2L,1L),latest.map{it.id});assertEquals(listOf(0,1,2),latest.map{it.order})
    }
    @Test fun batchTopAndBottomUseFullTableStablePartition()=runBlocking {
        val rows=(1L..4L).map{row(it,"$it",it.toInt()*10)};insert(*rows.toTypedArray())
        repo().move(setOf(rows[1].uuid,rows[3].uuid),true)
        assertEquals(listOf(2L,4L,1L,3L),repo().rows().first().map{it.id})
        repo().move(setOf(rows[1].uuid,rows[3].uuid),false)
        assertEquals(listOf(1L,3L,2L,4L),repo().rows().first().map{it.id})
        assertEquals(listOf(0,1,2,3),repo().rows().first().map{it.order})
    }
    @Test fun groupFlowExcludesBlankButKeepsLiteralCommaGroupsAndNamedUngroupedLabel()=runBlocking {
        insert(row(1,"A",0,"A,B"),row(2,"B",1," "),row(3,"C",2,null),row(4,"D",3,"未分组"))
        assertEquals(setOf("A,B","未分组"),repo().groups().first().toSet())
    }
    @Test fun duplicateReorderRejectsWithoutChangingRows()=runBlocking {
        val a=row(1,"A",10);val b=row(2,"B",20);insert(a,b)
        assertTrue(runCatching{repo().reorder(listOf(b.uuid,b.uuid))}.isFailure)
        assertEquals(listOf(1L,2L),repo().rows().first().map{it.id})
    }
}
