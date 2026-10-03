package io.legado.app.ui.book.info.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class BookDetailRouteTest {
    @get:Rule val compose=createComposeRule()
    private class Owner:LifecycleOwner{val registry=LifecycleRegistry(this);override val lifecycle:Lifecycle get()=registry}
    private fun newModel(sessions:Sessions,runner:BookDetailServiceSessionRepository?=null)=BookDetailViewModel(SavedStateHandle(mapOf("book.detail.ticket" to UUID.randomUUID().toString())),Details(),sessions,Network(),null,serviceSession=runner)
    private fun install(model:BookDetailViewModel,owner:Owner,native:BookDetailNativeRepository,deliver:(BookDetailNativePayload)->Unit,
        close:()->Unit={},error:(String)->Unit={}) {
        compose.setContent{LegadoComposeTheme{CompositionLocalProvider(LocalLifecycleOwner provides owner){
            BookDetailRoute(model,BookDetailPreferences(true,false,false,false),native,BookDetailNativeTexts("source","book","task"),
                close,deliver,{},{_,_,_->},{},{},{},error,backdrop={_,layout->Box(layout)},cover={_,layout->Box(layout)})
        }}}
    }
    @Test fun nonCooperativePreparationCancelledDuringPauseCannotDeliverUntilFreshResumedClaim() {
        val owner=Owner();val sessions=Sessions();val native=Native();val gate=CompletableDeferred<Unit>();native.gate=gate
        val delivered=AtomicInteger();lateinit var model:BookDetailViewModel
        compose.runOnIdle{owner.registry.currentState=Lifecycle.State.RESUMED;model=newModel(sessions)}
        try {
            install(model,owner,native,{delivered.incrementAndGet()});compose.waitUntil(timeoutMillis=5000){native.calls.get()==1}
            compose.runOnIdle{owner.registry.currentState=Lifecycle.State.CREATED};gate.complete(Unit)
            compose.waitUntil(timeoutMillis=5000){native.returned.get()==1};compose.runOnIdle{assertEquals(0,delivered.get());assertEquals("share",sessions.record.effects.single().token)}
            native.gate=null;compose.runOnIdle{owner.registry.currentState=Lifecycle.State.RESUMED}
            compose.waitUntil(timeoutMillis=5000){delivered.get()==1};assertEquals(2,native.calls.get());assertTrue(sessions.record.effects.isEmpty())
        }finally{gate.complete(Unit);compose.runOnIdle{model.stop()}}
    }
    @Test fun brokenPreparationConsumesFailedReceiptAndDoesNotBlockFollowingNativeAction() {
        val owner=Owner();val sessions=Sessions();sessions.record=sessions.record.copy(effects=sessions.record.effects+BookDetailNativeEffect("log",BookDetailNativeKind.Log))
        val delivered=mutableListOf<BookDetailNativeKind>();val errors=mutableListOf<String>();lateinit var model:BookDetailViewModel
        compose.runOnIdle{owner.registry.currentState=Lifecycle.State.RESUMED;model=newModel(sessions)}
        val native=object:BookDetailNativeRepository{override suspend fun prepare(effect:BookDetailNativeEffect,texts:BookDetailNativeTexts):BookDetailNativePayload {
            if(effect.token=="share")error("invalid payload")
            return BookDetailNativePayload(effect,null,null)
        }}
        try{install(model,owner,native,{delivered+=it.effect.kind},error={errors+=it})
            compose.waitUntil(timeoutMillis=5000){delivered.size==1};compose.runOnIdle{assertEquals(listOf(BookDetailNativeKind.Log),delivered);assertEquals(listOf("invalid payload"),errors);assertTrue(sessions.record.effects.isEmpty())}
        }finally{compose.runOnIdle{model.stop()}}
    }
    @Test fun restoredClosedStateClosesOnlyWhenResumedAndDoesNotPrepareOldEffect() {
        val owner=Owner();val sessions=Sessions();val native=Native();val closed=AtomicInteger();lateinit var model:BookDetailViewModel
        compose.runOnIdle{owner.registry.currentState=Lifecycle.State.CREATED;model=BookDetailViewModel(SavedStateHandle(mapOf("book.detail.closed" to true)),Details(),sessions,Network(),null)}
        try{install(model,owner,native,{},close={closed.incrementAndGet()});compose.runOnIdle{assertEquals(0,closed.get())}
            compose.runOnIdle{owner.registry.currentState=Lifecycle.State.RESUMED};compose.waitUntil(timeoutMillis=5000){closed.get()==1}
            compose.runOnIdle{owner.registry.currentState=Lifecycle.State.CREATED;owner.registry.currentState=Lifecycle.State.RESUMED};compose.runOnIdle{assertEquals(1,closed.get());assertEquals(0,native.calls.get())}
        }finally{compose.runOnIdle{model.stop()}}
    }
    @Test fun restoredPendingServiceDoesNotPrepareOldEffectUntilExplicitRetryCompletes() {
        val owner=Owner();val sessions=Sessions();val native=Native();val delivered=AtomicInteger()
        sessions.record=sessions.record.copy(pendingService=BookDetailPendingService(BookDetailServiceRequest("service",BookDetailServiceKind.ClearCache,sessions.record.data!!.book)))
        val runner=object:BookDetailServiceSessionRepository {
            override suspend fun execute(ticket:String,record:BookDetailSession,request:BookDetailServiceRequest):BookDetailSession =
                record.copy(pendingService=null,completedOperations=record.completedOperations+request.token,revision=record.revision+1).also{sessions.record=it}
        }
        lateinit var model:BookDetailViewModel
        compose.runOnIdle{owner.registry.currentState=Lifecycle.State.RESUMED;model=newModel(sessions,runner)}
        try {
            install(model,owner,native,{delivered.incrementAndGet()})
            compose.waitUntil(timeoutMillis=5000){model.state.value.loaded}
            compose.runOnIdle{assertEquals(0,native.calls.get());assertEquals(0,delivered.get())}
            compose.mainClock.advanceTimeBy(1000);compose.runOnIdle{assertEquals(0,native.calls.get())}
            compose.runOnIdle{model.retry()};compose.waitUntil(timeoutMillis=5000){delivered.get()==1}
            compose.runOnIdle{assertEquals(1,native.calls.get());assertNull(sessions.record.pendingService);assertTrue(sessions.record.effects.isEmpty())}
        }finally{compose.runOnIdle{model.stop()}}
    }
    private class Native:BookDetailNativeRepository {
        val calls=AtomicInteger();val returned=AtomicInteger();var gate:CompletableDeferred<Unit>?=null
        override suspend fun prepare(effect:BookDetailNativeEffect,texts:BookDetailNativeTexts):BookDetailNativePayload {
            calls.incrementAndGet();gate?.let{withContext(NonCancellable){it.await()}};returned.incrementAndGet()
            return BookDetailNativePayload(effect,null,null)
        }
    }
    private class Details:BookDetailRepository {
        override suspend fun resolve(identity:BookDetailIdentity):BookDetailData?=null
        override suspend fun reload(bookUrl:String):BookDetailData?=null
        override suspend fun describe(book:BookDetailBook,inBookshelf:Boolean)=error("unexpected describe")
    }
    private class Sessions:BookDetailSessionRepository {
        @Volatile var record=BookDetailSession(BookDetailIdentity(bookUrl="book"),BookDetailData(BookDetailBook.from(Book(bookUrl="book",name="Name",tocUrl="toc")),null,emptyList(),emptyList(),emptyList(),true),effects=listOf(BookDetailNativeEffect("share",BookDetailNativeKind.Share)))
        override suspend fun read(ticket:String)=record
        override suspend fun write(ticket:String,record:BookDetailSession){this.record=record}
        override suspend fun mutate(ticket:String,record:BookDetailSession,operation:BookDetailOperation)=error("unexpected mutation")
        override suspend fun completeNetwork(ticket:String,record:BookDetailSession,request:BookDetailData,result:BookDetailNetworkResult,sourceChanged:Boolean,token:String)=error("unexpected network commit")
        override suspend fun recover(ticket:String)=record
        override suspend fun release(ticket:String)=Unit
    }
    private class Network:BookDetailNetworkRepository {
        override suspend fun info(book:BookDetailBook,source:BookDetailSource?,canRename:Boolean,runPreUpdate:Boolean)=error("unexpected info")
        override suspend fun toc(book:BookDetailBook,source:BookDetailSource?,runPreUpdate:Boolean,fromBookInfo:Boolean)=error("unexpected toc")
        override suspend fun files(book:BookDetailBook,source:BookDetailSource?)=emptyList<BookDetailWebFile>()
        override suspend fun cover(book:BookDetailBook):BookDetailBook?=null
    }
}
