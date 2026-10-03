package io.legado.app.ui.book.info.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class BookDetailState(val session:BookDetailSession?=null,val loading:Boolean=true,val loaded:Boolean=false,
    val networkLoading:Boolean=false,val busy:Boolean=false,val error:String?=null,val closed:Boolean=false,
    val introExpanded:Boolean=true) {
    val data:BookDetailData? get()=session?.data
    val canInteract:Boolean get()=loaded && !closed && !busy && session?.pendingMutation==null && session?.pendingNetwork==null
}

/** Owns immutable private-session snapshots. Native callbacks belong exclusively to the resumed Route. */
class BookDetailViewModel(private val saved:SavedStateHandle,private val details:BookDetailRepository,
    private val sessions:BookDetailSessionRepository,private val network:BookDetailNetworkRepository,
    private val initialIdentity:BookDetailIdentity?,private val cleanupScope:CoroutineScope?=null):ViewModel() {
    val ticket:String=saved.get<String>(KEY) ?: UUID.randomUUID().toString().also{saved[KEY]=it}
    private val mutable=MutableStateFlow(BookDetailState(closed=saved.get<Boolean>(CLOSED)==true,
        loading=saved.get<Boolean>(CLOSED)!=true,introExpanded=saved.get<Boolean>(EXPANDED) ?: true))
    val state:StateFlow<BookDetailState> = mutable.asStateFlow()
    private val writes=Mutex();private val ready=CompletableDeferred<Unit>()
    private var dirty=false;private var loadJob:Job?=null;private var networkJob:Job?=null;private var mutationJob:Job?=null
    private var generation=0L
    init{if(state.value.closed){ready.complete(Unit);release()}else load()}
    private fun load() {
        loadJob?.cancel();loadJob=viewModelScope.launch {
            mutable.update{it.copy(loading=true,error=null)}
            try {
                val stored=sessions.read(ticket)
                var record=stored ?: BookDetailSession(checkNotNull(initialIdentity){"Missing book identity"})
                if(record.data==null) {
                    val data=details.resolve(record.identity) ?: throw BookDetailMissing()
                    ensureActive();if(state.value.closed)return@launch
                    record=record.copy(data=data,revision=record.revision+1);sessions.write(ticket,record)
                }
                ensureActive();if(state.value.closed)return@launch
                val interrupted=record.running && record.pendingNetwork==null
                mutable.update{it.copy(session=record,loading=false,loaded=true,error=if(interrupted)"Previous request was interrupted; retry to continue" else null)}
                if(!ready.isCompleted)ready.complete(Unit)
                if(record.pendingNetwork!=null || record.pendingMutation!=null)recoverPending()
                else if(stored==null && !interrupted) {
                    val data=checkNotNull(record.data)
                    val tocBlank=withContext(Dispatchers.IO){data.book.materializeBook().tocUrl.isBlank()}
                    ensureActive();if(state.value.closed)return@launch
                    when {
                        tocBlank && !data.book.isLocal->requestNetwork(info=true,canRename=true,runPre=data.inBookshelf)
                        data.chapters.isEmpty()->requestNetwork(info=false,canRename=true,runPre=true,fromInfo=true)
                    }
                }
            }catch(error:Throwable){ensureActive();if(!state.value.closed)mutable.update{it.copy(loading=false,error=error.message ?: error.toString())}}
        }
    }
    suspend fun flush()=writes.withLock{flushLocked()}
    private suspend fun flushLocked() {
        val record=state.value.session ?: return
        if(!dirty || state.value.closed)return
        sessions.write(ticket,record)
        if(state.value.session?.revision==record.revision)dirty=false
    }
    private fun publish(record:BookDetailSession){mutable.update{it.copy(session=record)};dirty=false}
    private fun failure(error:Throwable){if(!state.value.closed)mutable.update{it.copy(error=error.message ?: error.toString())}}
    fun introExpanded(value:Boolean){saved[EXPANDED]=value;mutable.update{it.copy(introExpanded=value)}}
    fun refreshInfo()=requestNetwork(info=true,canRename=false,runPre=true)
    fun refreshToc()=requestNetwork(info=false,canRename=false,runPre=true,fromInfo=true)
    fun requestNetwork(info:Boolean,canRename:Boolean,runPre:Boolean,fromInfo:Boolean=false) {
        if(!state.value.canInteract)return
        val old=networkJob;val owner=++generation;old?.cancel()
        mutable.update{it.copy(networkLoading=true,error=null)}
        networkJob=viewModelScope.launch {
            try {
                old?.join();ensureActive();if(state.value.closed || owner!=generation)return@launch
                val request=writes.withLock {
                    flushLocked();val record=checkNotNull(state.value.session)
                    val next=record.copy(running=true,revision=record.revision+1);sessions.write(ticket,next);publish(next)
                    checkNotNull(next.data)
                }
                val result=if(info)network.info(request.book,request.source,canRename,runPre)
                    else network.toc(request.book,request.source,runPre,fromInfo)
                ensureActive();if(state.value.closed || owner!=generation)return@launch
                writes.withLock {
                    ensureActive();if(state.value.closed || owner!=generation)return@withLock
                    mutable.update{it.copy(busy=true)};flushLocked()
                    val record=checkNotNull(state.value.session)
                    val completed=sessions.completeNetwork(ticket,record,request,result,false,UUID.randomUUID().toString())
                    ensureActive();if(!state.value.closed && owner==generation)publish(completed)
                }
            }catch(error:Throwable){ensureActive();if(!state.value.closed && owner==generation){reloadReceipt();failure(error)}}
            finally{if(owner==generation)mutable.update{it.copy(networkLoading=false,busy=false)}}
        }
    }
    private suspend fun reloadReceipt() {
        val persisted=runCatching{sessions.read(ticket)}.getOrNull();currentCoroutineContext().ensureActive()
        if(persisted!=null && !state.value.closed)publish(persisted)
    }
    fun mutate(change:BookDetailMutation,navigation:BookDetailNativeKind?=null,
        highlightTitleLength:Int?=null,highlightAnchor:String?=null) {
        if(!state.value.canInteract)return
        mutable.update{it.copy(busy=true,error=null)}
        mutationJob=viewModelScope.launch {
            try{writes.withLock {
                flushLocked();val record=checkNotNull(state.value.session)
                val operation=BookDetailOperation(UUID.randomUUID().toString(),change,navigation,highlightTitleLength,highlightAnchor)
                val completed=sessions.mutate(ticket,record,operation)
                ensureActive();if(!state.value.closed)publish(completed)
            }}catch(error:Throwable){ensureActive();reloadReceipt();failure(error)}finally{mutable.update{it.copy(busy=false)}}
        }
    }
    fun queue(kind:BookDetailNativeKind,value:String?=null,flag:Boolean=false) {
        if(!state.value.canInteract)return
        val before=state.value.session ?: return;val data=before.data ?: return
        val effect=BookDetailNativeEffect(UUID.randomUUID().toString(),kind,data.book,data.source,value,flag)
        publish(before.copy(effects=before.effects+effect,revision=before.revision+1));dirty=true
        viewModelScope.launch{try{flush()}catch(error:Throwable){ensureActive();failure(error)}}
    }
    suspend fun consumeEffect(token:String,canDeliver:()->Boolean):BookDetailNativeEffect? {
        ready.await();mutable.update{it.copy(busy=true)}
        try{return writes.withLock {
            flushLocked();currentCoroutineContext().ensureActive();if(state.value.closed || !canDeliver())return@withLock null
            val before=state.value.session ?: return@withLock null
            val effect=before.effects.firstOrNull()?.takeIf{it.token==token} ?: return@withLock null
            val next=before.copy(effects=before.effects.drop(1),revision=before.revision+1)
            // Caller-side NonCancellable prevents the IO return hop from throwing after durable removal.
            withContext(NonCancellable){sessions.write(ticket,next)}
            if(state.value.closed){currentCoroutineContext().ensureActive();return@withLock null}
            if(!currentCoroutineContext().isActive || !canDeliver()) {
                val rollback=before.copy(revision=next.revision+1)
                withContext(NonCancellable){sessions.write(ticket,rollback)};publish(rollback)
                currentCoroutineContext().ensureActive();return@withLock null
            }
            publish(next);effect
        }}finally{mutable.update{it.copy(busy=false)}}
    }
    private fun recoverPending() {
        if(state.value.closed || state.value.busy)return
        mutable.update{it.copy(busy=true,error=null)}
        mutationJob=viewModelScope.launch {
            try{writes.withLock{val recovered=sessions.recover(ticket);ensureActive();if(recovered!=null && !state.value.closed)publish(recovered)}}
            catch(error:Throwable){ensureActive();reloadReceipt();failure(error)}finally{mutable.update{it.copy(busy=false)}}
        }
    }
    fun retry() {
        if(state.value.closed || state.value.busy)return
        if(!state.value.loaded){load();return}
        if(state.value.session?.pendingNetwork!=null || state.value.session?.pendingMutation!=null){recoverPending();return}
        refreshInfo()
    }
    fun reload() {
        if(!state.value.loaded || state.value.closed || state.value.busy || state.value.networkLoading)return
        mutable.update{it.copy(busy=true,error=null)}
        mutationJob=viewModelScope.launch {
            try{writes.withLock {
                flushLocked();val record=checkNotNull(state.value.session);val data=checkNotNull(record.data)
                val latest=if(data.inBookshelf) {
                    val committedUrl=record.pendingNetwork?.result?.book?.bookUrl
                    committedUrl?.let{details.reload(it)} ?: details.reload(data.book.bookUrl) ?: throw BookDetailMissing()
                }else details.describe(data.book,false)
                ensureActive();if(state.value.closed)return@withLock
                val next=record.copy(data=latest,pendingNetwork=null,pendingMutation=null,running=false,revision=record.revision+1)
                sessions.write(ticket,next);ensureActive()
                if(!state.value.closed)publish(next)
            }}catch(error:Throwable){ensureActive();failure(error)}finally{mutable.update{it.copy(busy=false)}}
        }
    }
    fun close(){if(state.value.closed)return;saved[CLOSED]=true;mutable.update{it.copy(closed=true,loading=false,busy=false,networkLoading=false)};stop();release()}
    private fun release(){(cleanupScope ?: viewModelScope).launch{withContext(NonCancellable){runCatching{sessions.release(ticket)}}}}
    fun stop(){generation++;loadJob?.cancel();networkJob?.cancel();mutationJob?.cancel()}
    override fun onCleared(){stop();super.onCleared()}
    companion object{private const val KEY="book.detail.ticket";private const val CLOSED="book.detail.closed";private const val EXPANDED="book.detail.intro.expanded"}
}
