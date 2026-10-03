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
    val introExpanded:Boolean=true,val childPending:Boolean=false) {
    val data:BookDetailData? get()=session?.data
    val canInteract:Boolean get()=loaded && !closed && !busy && session?.pendingMutation==null && session?.pendingNetwork==null && session?.pendingService==null && !childPending
}

/** Owns immutable private-session snapshots. Native callbacks belong exclusively to the resumed Route. */
class BookDetailViewModel(private val saved:SavedStateHandle,private val details:BookDetailRepository,
    private val sessions:BookDetailSessionRepository,private val network:BookDetailNetworkRepository,
    private val initialIdentity:BookDetailIdentity?,private val cleanupScope:CoroutineScope?=null,
    private val children:BookDetailChildRepository?=null,private val services:BookDetailServicesRepository?=null,
    private val childServices:BookDetailChildServicesRepository?=null,
    private val serviceSession:BookDetailServiceSessionRepository?=null):ViewModel() {
    val ticket:String=saved.get<String>(KEY) ?: UUID.randomUUID().toString().also{saved[KEY]=it}
    private val mutable=MutableStateFlow(BookDetailState(closed=saved.get<Boolean>(CLOSED)==true,
        loading=saved.get<Boolean>(CLOSED)!=true,introExpanded=saved.get<Boolean>(EXPANDED) ?: true))
    val state:StateFlow<BookDetailState> = mutable.asStateFlow()
    private val writes=Mutex();private val ready=CompletableDeferred<Unit>()
    private var dirty=false;private var loadJob:Job?=null;private var networkJob:Job?=null;private var mutationJob:Job?=null
    private var generation=0L;private var childJob:Job?=null;private var serviceJob:Job?=null
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
                val interrupted=(record.running && record.pendingNetwork==null) || record.pendingService!=null
                val hasChildren=children?.read(ticket)?.pending?.isNotEmpty()==true
                ensureActive();if(state.value.closed)return@launch
                // Publish the ledger gate atomically with loaded, before any native claim can leave ready.await().
                mutable.update{it.copy(session=record,loading=false,loaded=true,childPending=hasChildren,
                    error=if(interrupted)"Previous request was interrupted; retry to continue" else null)}
                if(!ready.isCompleted)ready.complete(Unit)
                if(hasChildren && record.pendingService==null){processChildren();return@launch}
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
    private fun publish(record:BookDetailSession){
        val oldIntro=state.value.data?.book?.intro;val nextIntro=record.data?.book?.intro
        if(state.value.loaded && oldIntro!=nextIntro){saved[EXPANDED]=true;mutable.update{it.copy(introExpanded=true)}}
        mutable.update{it.copy(session=record)};dirty=false
    }
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
            flushLocked();currentCoroutineContext().ensureActive();if(state.value.closed || state.value.childPending || state.value.session?.pendingService!=null || !canDeliver())return@withLock null
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
    /** Side effects continue across UI pauses, but a restored unfinished operation requires an explicit retry. */
    fun service(kind:BookDetailServiceKind,file:BookDetailWebFile?=null,uri:String?=null,entry:String?=null,
        deleteOriginal:Boolean=false,deleteRemote:Boolean=false,overwrite:Boolean=false,readAfter:Boolean=false,uploadImported:Boolean=false) {
        if(!state.value.canInteract)return
        val data=state.value.data ?: return
        runService(BookDetailServiceRequest(UUID.randomUUID().toString(),kind,data.book,data.source,file,uri,entry,
            deleteOriginal,deleteRemote,overwrite,readAfter,uploadImported))
    }
    private fun runService(request:BookDetailServiceRequest) {
        if(state.value.closed || state.value.busy || serviceSession==null)return
        val previous=networkJob;generation++;previous?.cancel()
        mutable.update{it.copy(busy=true,error=null)}
        serviceJob=viewModelScope.launch {
            try {
                previous?.join();ensureActive();if(state.value.closed)return@launch
                mutable.update{it.copy(networkLoading=false)}
                writes.withLock {
                    flushLocked();val stored=checkNotNull(state.value.session)
                    // Complete a previous Room receipt before resuming the service continuation.
                    val record=if(stored.pendingNetwork!=null || stored.pendingMutation!=null)
                        sessions.recover(ticket) ?: throw BookDetailMissing() else stored
                    ensureActive();if(state.value.closed)return@withLock
                    val current=checkNotNull(record.data)
                    val active=record.pendingService?.request ?: request.copy(book=current.book,source=current.source)
                    val completed=serviceSession.execute(ticket,record,active)
                    ensureActive();if(!state.value.closed)publish(completed)
                }
            }catch(error:Throwable){ensureActive();reloadReceipt();failure(error)}
            finally{if(!state.value.closed) {
                mutable.update{it.copy(busy=false,networkLoading=false)}
                if(state.value.childPending && state.value.session?.pendingService==null)processChildren()
            }}
        }
    }
    fun prompt(value:BookDetailPrompt?) {
        if(!state.value.canInteract)return
        val record=state.value.session ?: return
        publish(record.copy(prompt=value,revision=record.revision+1));dirty=true
        viewModelScope.launch{try{flush()}catch(error:Throwable){ensureActive();failure(error)}}
    }
    fun retry() {
        if(state.value.closed || state.value.busy)return
        if(!state.value.loaded){load();return}
        state.value.session?.pendingService?.let{runService(it.request);return}
        if(state.value.childPending){processChildren();return}
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
                val next=record.copy(data=latest,pendingNetwork=null,pendingMutation=null,pendingService=null,prompt=null,running=false,revision=record.revision+1)
                sessions.write(ticket,next);ensureActive()
                if(!state.value.closed)publish(next)
            }}catch(error:Throwable){ensureActive();failure(error)}finally{mutable.update{it.copy(busy=false)}}
        }
    }
    suspend fun registerChild(owner:BookDetailChildOwner) {
        check(!state.value.closed){"Book detail is closed"};checkNotNull(children).owner(ticket,owner)
        currentCoroutineContext().ensureActive()
    }
    /** The native result becomes durable before waiting for initial book/session loading. */
    suspend fun acceptChild(result:BookDetailChildResult) {
        if(state.value.closed)return
        withContext(NonCancellable){checkNotNull(children).result(ticket,result)}
        currentCoroutineContext().ensureActive();if(!state.value.closed)processChildren()
    }
    suspend fun childOwner(kind:BookDetailChildKind)=children?.read(ticket)?.owners?.firstOrNull{it.kind==kind}
    fun processChildren() {
        val ledger=children ?: return
        if(state.value.closed || state.value.session?.pendingService!=null || childJob?.isActive==true)return
        childJob=viewModelScope.launch {
            try {
                ready.await();ensureActive();if(state.value.closed)return@launch
                if(ledger.read(ticket).pending.isEmpty()){ensureActive();mutable.update{it.copy(childPending=false)};return@launch}
                ensureActive();mutable.update{it.copy(childPending=true)}
                // A returned child owns the next mutation; stop and join the earlier network request first.
                val previous=networkJob;generation++;previous?.cancelAndJoin()
                mutable.update{it.copy(networkLoading=false,busy=true,error=null)}
                writes.withLock {
                    flushLocked()
                    while(!state.value.closed) {
                        val result=ledger.read(ticket).pending.firstOrNull()
                        ensureActive();if(result==null){mutable.update{it.copy(childPending=false)};break}
                        val record=checkNotNull(state.value.session)
                        if(record.pendingMutation!=null || record.pendingNetwork!=null) {
                            val recovered=sessions.recover(ticket);ensureActive()
                            if(recovered!=null)publish(recovered)
                        }
                        val current=checkNotNull(state.value.session)
                        val active=checkNotNull(current.data)
                        if(result.owner.bookUrl!=active.book.bookUrl) {
                            ledger.complete(ticket,result.owner.token);ensureActive();continue
                        }
                        val next=applyChild(current,result)
                        ensureActive();if(state.value.closed)return@withLock
                        if(next!==current)publish(next)
                        withContext(NonCancellable){ledger.complete(ticket,result.owner.token)}
                        ensureActive()
                    }
                }
            }catch(error:Throwable){ensureActive();reloadReceipt();failure(error)}
            finally{if(!state.value.closed)mutable.update{it.copy(busy=false)}}
        }
    }
    private suspend fun applyChild(record:BookDetailSession,result:BookDetailChildResult):BookDetailSession {
        val data=checkNotNull(record.data);val token=result.owner.token
        suspend fun change(value:BookDetailMutation,native:BookDetailNativeKind?=null,
            titleLength:Int?=null,anchor:String?=null)=sessions.mutate(ticket,record,
                BookDetailOperation(token,value,native,titleLength,anchor))
        suspend fun persist(value:BookDetailSession):BookDetailSession {
            withContext(NonCancellable){sessions.write(ticket,value)};currentCoroutineContext().ensureActive();return value
        }
        when(result.owner.kind) {
            BookDetailChildKind.Cover->if(!result.canceled)return change(BookDetailMutation(BookDetailMutationKind.Cover,text=result.value))
            BookDetailChildKind.Group->if(!result.canceled)return change(BookDetailMutation(BookDetailMutationKind.Group,group=result.number))
            BookDetailChildKind.Variable->if(!result.canceled) {
                if(result.number==1L) {
                    if(data.source?.url!=result.owner.sourceUrl)return record
                    checkNotNull(services).sourceVariable(data.source,checkNotNull(result.owner.sourceUrl),result.value)
                    currentCoroutineContext().ensureActive();return record
                }
                return change(BookDetailMutation(BookDetailMutationKind.CustomVariable,text=result.value))
            }
            BookDetailChildKind.Source->if(!result.canceled) {
                return sessions.completeNetwork(ticket,record,data,checkNotNull(result.network),true,token)
            }
            BookDetailChildKind.Folder->if(!result.canceled && result.value!=null) {
                checkNotNull(childServices).folder(result.value);currentCoroutineContext().ensureActive()
            }
            BookDetailChildKind.Toc->{
                if(result.canceled) {
                    if(!data.inBookshelf) {
                        val removed=checkNotNull(childServices).discardTemporary(data.book)
                        currentCoroutineContext().ensureActive()
                        if(!removed) {
                            val latest=details.reload(data.book.bookUrl)
                            currentCoroutineContext().ensureActive()
                            if(latest!=null)return persist(record.copy(data=latest,revision=record.revision+1))
                        }
                    }
                }else {
                    val defer=result.highlightTitleLength!=null && checkNotNull(childServices).deferHighlight(data.book)
                    currentCoroutineContext().ensureActive()
                    val changed=record.copy(chapterChanged=result.chapterChanged,revision=record.revision+1)
                    persist(changed)
                    val operation=BookDetailOperation(token,BookDetailMutation(BookDetailMutationKind.PrepareRead,
                        position=result.position.takeUnless{defer}),BookDetailNativeKind.Reader,
                        result.highlightTitleLength.takeIf{defer},result.highlightAnchor.takeIf{defer})
                    val completed=sessions.mutate(ticket,changed,operation)
                    if(defer) {
                        val updated=completed.copy(effects=completed.effects.map{
                            if(it.token==token+":navigate")it.copy(position=result.position)else it
                        },revision=completed.revision+1)
                        return persist(updated)
                    }
                    return completed
                }
            }
            BookDetailChildKind.Reader->{
                if(result.resultCode==DELETED_RESULT) {
                    val effect=BookDetailNativeEffect(token,BookDetailNativeKind.Deleted,data.book,data.source,flag=false)
                    if(record.effects.any{it.token==token})return record
                    return persist(record.copy(effects=record.effects+effect,revision=record.revision+1))
                }
                val latest=details.reload(data.book.bookUrl)
                currentCoroutineContext().ensureActive()
                if(latest!=null)return persist(record.copy(data=latest,revision=record.revision+1))
            }
            BookDetailChildKind.InfoEditor,BookDetailChildKind.SourceEditor->if(!result.canceled) {
                val latest=details.reload(data.book.bookUrl) ?: throw BookDetailMissing()
                currentCoroutineContext().ensureActive()
                return persist(record.copy(data=latest,revision=record.revision+1))
            }
        }
        return record
    }
    fun close(){if(state.value.closed)return;saved[CLOSED]=true;mutable.update{it.copy(closed=true,loading=false,busy=false,networkLoading=false)};stop();release()}
    private fun release(){(cleanupScope ?: viewModelScope).launch{withContext(NonCancellable){runCatching{sessions.release(ticket)};runCatching{children?.release(ticket)}}}}
    fun stop(){generation++;loadJob?.cancel();networkJob?.cancel();mutationJob?.cancel();childJob?.cancel();serviceJob?.cancel()}
    override fun onCleared(){stop();super.onCleared()}
    companion object{private const val DELETED_RESULT=100;private const val KEY="book.detail.ticket";private const val CLOSED="book.detail.closed";private const val EXPANDED="book.detail.intro.expanded"}
}
