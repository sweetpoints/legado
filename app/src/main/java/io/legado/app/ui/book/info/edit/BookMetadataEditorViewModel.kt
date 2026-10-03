package io.legado.app.ui.book.info.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class BookMetadataCursor(val start:Int=0,val end:Int=0)
data class BookMetadataEditorState(val draft:BookMetadataDraft?=null,val loading:Boolean=true,
    val loaded:Boolean=false,val busy:Boolean=false,val saveFailed:Boolean=false,val error:String?=null,
    val cursors:Map<BookMetadataField,BookMetadataCursor> = emptyMap(),val closed:Boolean=false) {
    val finished:Boolean get()=draft?.finished==true
    val canEdit:Boolean get()=loaded && !busy && !closed && !saveFailed && draft?.pendingSave==null && draft?.completion==null && !finished
}

class BookMetadataEditorViewModel(private val saved:SavedStateHandle,
    private val books:BookMetadataEditorRepository,private val sessions:BookMetadataEditorSessionRepository,
    private val covers:BookMetadataCoverImportRepository,private val initialBookUrl:String?,
    private val cleanupScope:CoroutineScope?=null) : ViewModel() {
    val ticket:String=saved.get<String>(KEY) ?: UUID.randomUUID().toString().also{saved[KEY]=it}
    private val mutable=MutableStateFlow(BookMetadataEditorState(closed=saved.get<Boolean>(CLOSED)==true,loading=saved.get<Boolean>(CLOSED)!=true))
    val state:StateFlow<BookMetadataEditorState> = mutable.asStateFlow()
    private val writes=Mutex();private val ready=CompletableDeferred<Unit>()
    private var dirty=false;private var loadJob:Job?=null;private var importJob:Job?=null;private var saveJob:Job?=null
    init {if(state.value.closed){ready.complete(Unit);releaseSession()}else load()}
    private fun input(book:BookMetadataSnapshot)=BookMetadataInput(book.bookUrl,book.name,book.author,book.typeIndex,book.coverText,book.introText)
    private fun cursors()=BookMetadataField.entries.associateWith{field->BookMetadataCursor(saved.get<Int>("metadata.cursor.${field.name}.start") ?: 0,saved.get<Int>("metadata.cursor.${field.name}.end") ?: 0)}
    private fun load() {
        loadJob?.cancel();loadJob=viewModelScope.launch {
            mutable.update{it.copy(loading=true,error=null)}
            try {
                var draft=sessions.read(ticket) ?: BookMetadataDraft(checkNotNull(initialBookUrl){"Missing book URL"})
                if(draft.original==null || draft.input==null) {
                    val book=books.load(draft.bookUrl) ?: throw BookMetadataMissing()
                    ensureActive();if(state.value.closed)return@launch
                    draft=draft.copy(original=book,input=input(book),preview=book.preview(),revision=draft.revision+1)
                    sessions.write(ticket,draft)
                }
                ensureActive();if(state.value.closed)return@launch
                mutable.update{it.copy(draft=draft,loading=false,loaded=true,saveFailed=draft.pendingSave!=null,cursors=cursors())}
                if(!ready.isCompleted)ready.complete(Unit)
                if(draft.coverUri!=null)importPendingCover()
            } catch(error:Throwable){ensureActive();mutable.update{it.copy(loading=false,error=error.message ?: error.toString())}}
        }
    }
    private fun edit(block:BookMetadataDraft.()->BookMetadataDraft) {
        if(state.value.closed)return
        val before=state.value.draft ?: return
        mutable.update{it.copy(draft=before.block().copy(revision=before.revision+1))};dirty=true
        viewModelScope.launch{try{flush()}catch(error:Throwable){ensureActive();failure(error)}}
    }
    suspend fun flush()=writes.withLock {
        val draft=state.value.draft ?: return@withLock
        if(!dirty || state.value.closed)return@withLock
        sessions.write(ticket,draft);if(state.value.draft?.revision==draft.revision)dirty=false
    }
    fun text(field:BookMetadataField,value:String,start:Int=value.length,end:Int=start) {
        if(!state.value.canEdit || field==BookMetadataField.Type)return
        val cursor=BookMetadataCursor(start.coerceIn(0,value.length),end.coerceIn(0,value.length))
        saved["metadata.cursor.${field.name}.start"]=cursor.start;saved["metadata.cursor.${field.name}.end"]=cursor.end
        mutable.update{it.copy(cursors=it.cursors+(field to cursor))}
        val current=state.value.draft?.input ?: return
        val previous=when(field){BookMetadataField.Name->current.name;BookMetadataField.Author->current.author;BookMetadataField.Cover->current.cover;BookMetadataField.Intro->current.intro;BookMetadataField.Type->""}
        if(previous==value)return
        edit{val original=checkNotNull(input);copy(input=when(field){
            BookMetadataField.Name->original.copy(name=value,changed=original.changed+field)
            BookMetadataField.Author->original.copy(author=value,changed=original.changed+field)
            BookMetadataField.Cover->original.copy(cover=value,changed=original.changed+field)
            BookMetadataField.Intro->original.copy(intro=value,changed=original.changed+field)
            BookMetadataField.Type->original
        })}
    }
    fun type(index:Int){if(state.value.canEdit && index in 0..4)edit{copy(input=checkNotNull(input).copy(typeIndex=index,changed=input!!.changed+BookMetadataField.Type))}}
    fun refreshCover(){state.value.draft?.input?.cover?.let(::coverChanged)}
    fun coverChanged(url:String) {
        if(!state.value.canEdit)return
        edit{val original=checkNotNull(input);copy(input=original.copy(cover=url,changed=original.changed+BookMetadataField.Cover,refreshCover=true),preview=checkNotNull(this.original).preview(url,null))}
    }
    fun navigate(action:BookMetadataAction) {
        val current=state.value
        if(!current.canEdit || current.draft?.navigation!=null || current.draft?.pickerOwner!=null)return
        edit{copy(navigation=BookMetadataNavigation(UUID.randomUUID().toString(),action))}
    }
    suspend fun consumeNavigation(token:String,canDeliver:()->Boolean):BookMetadataNavigation? {
        ready.await();flush();mutable.update{it.copy(busy=true)}
        try{return writes.withLock {
            currentCoroutineContext().ensureActive();if(!canDeliver() || state.value.closed)return@withLock null
            val before=state.value.draft ?: return@withLock null
            val pending=before.navigation?.takeIf{it.token==token} ?: return@withLock null
            val next=before.copy(navigation=null,pickerOwner=if(pending.action==BookMetadataAction.PickCover)pending.token else before.pickerOwner,revision=before.revision+1)
            if(!claim(before,next,canDeliver))return@withLock null
            pending
        }}finally{mutable.update{it.copy(busy=false)}}
    }
    /** Result callbacks may precede disk initialization; the ownership check runs after load. */
    fun coverResult(owner:String,uri:String?)=viewModelScope.launch {
        ready.await();if(state.value.closed)return@launch
        var ownsBusy=false;var accepted=false
        try {writes.withLock {
            val before=state.value.draft ?: return@withLock
            if(before.pickerOwner!=owner || state.value.closed)return@withLock
            ownsBusy=true;mutable.update{it.copy(busy=true)}
            val next=before.copy(pickerOwner=null,coverUri=uri,revision=before.revision+1)
            // Retain the URI in current state if persistence fails, so explicit Retry can finish the import.
            mutable.update{it.copy(draft=next)};dirty=true
            withContext(NonCancellable){sessions.write(ticket,next)};ensureActive()
            if(!state.value.closed){dirty=false;accepted=true}
        }} catch(error:Throwable){ensureActive();if(!state.value.closed)failure(error)}
        finally{if(ownsBusy)mutable.update{it.copy(busy=false)}}
        if(accepted && uri!=null && !state.value.closed)importPendingCover()
    }
    private fun importPendingCover() {
        val uri=state.value.draft?.coverUri ?: return
        if(importJob?.isActive==true)return
        mutable.update{it.copy(busy=true,error=null)}
        importJob=viewModelScope.launch {
            try {
                val path=covers.install(uri);ensureActive();if(state.value.closed)return@launch
                writes.withLock {
                    val before=state.value.draft ?: return@withLock
                    if(before.coverUri!=uri)return@withLock
                    val original=checkNotNull(before.original);val input=checkNotNull(before.input)
                    val next=before.copy(input=input.copy(cover=path,changed=input.changed+BookMetadataField.Cover,refreshCover=true),preview=original.preview(path,null),coverUri=null,revision=before.revision+1)
                    sessions.write(ticket,next);ensureActive();if(!state.value.closed){mutable.update{it.copy(draft=next)};dirty=false}
                }
            } catch(error:Throwable){ensureActive();failure(error)}
            finally{mutable.update{it.copy(busy=false)}}
        }
    }
    fun save() {
        val current=state.value
        if(!current.loaded || current.busy || current.closed || current.finished || current.draft?.completion!=null || current.draft?.coverUri!=null || current.draft?.pickerOwner!=null || current.draft?.navigation!=null)return
        mutable.update{it.copy(busy=true,error=null)}
        saveJob=viewModelScope.launch {
            try {flush();val completed=writes.withLock {sessions.save(ticket,checkNotNull(state.value.draft))}
                ensureActive();if(!state.value.closed){mutable.update{it.copy(draft=completed,saveFailed=false)};dirty=false}
            } catch(error:Throwable){ensureActive()
                val persisted=runCatching{sessions.read(ticket)}.getOrNull();ensureActive()
                mutable.update{it.copy(draft=persisted ?: it.draft,saveFailed=true,error=error.message ?: error.toString())}
            } finally{mutable.update{it.copy(busy=false)}}
        }
    }
    suspend fun consumeCompletion(token:String,canDeliver:()->Boolean):BookMetadataCompletion? {
        ready.await();flush();mutable.update{it.copy(busy=true)}
        try{return writes.withLock {
            currentCoroutineContext().ensureActive();if(!canDeliver() || state.value.closed)return@withLock null
            val before=state.value.draft ?: return@withLock null
            val pending=before.completion?.takeIf{it.token==token} ?: return@withLock null
            if(!claim(before,before.copy(completion=null,finished=true,revision=before.revision+1),canDeliver))return@withLock null
            pending
        }}finally{mutable.update{it.copy(busy=false)}}
    }
    private suspend fun claim(before:BookMetadataDraft,next:BookMetadataDraft,canDeliver:()->Boolean):Boolean {
        withContext(NonCancellable){sessions.write(ticket,next)}
        if(state.value.closed){currentCoroutineContext().ensureActive();return false}
        if(!currentCoroutineContext().isActive || !canDeliver()) {
            val rollback=before.copy(revision=next.revision+1)
            withContext(NonCancellable){sessions.write(ticket,rollback)};mutable.update{it.copy(draft=rollback)};dirty=false
            currentCoroutineContext().ensureActive();return false
        }
        mutable.update{it.copy(draft=next)};dirty=false;return true
    }
    fun retry(){
        if(!state.value.loaded){load();return}
        if(state.value.draft?.pendingSave!=null || state.value.saveFailed){save();return}
        if(state.value.draft?.coverUri!=null){importPendingCover();return}
        mutable.update{it.copy(error=null)};viewModelScope.launch{try{flush()}catch(error:Throwable){ensureActive();failure(error)}}
    }
    /** Explicitly discard the failed plan/draft and reload latest metadata; never replay over a conflict. */
    fun reload() {
        val current=state.value
        if(!current.loaded || current.busy || current.closed || current.finished)return
        mutable.update{it.copy(busy=true,error=null)}
        viewModelScope.launch {
            try {writes.withLock {
                val persisted=sessions.read(ticket) ?: current.draft ?: error("Missing draft")
                val book=books.load(persisted.bookUrl) ?: throw BookMetadataMissing()
                ensureActive();if(state.value.closed)return@withLock
                val next=BookMetadataDraft(book.bookUrl,book,input(book),book.preview(),revision=persisted.revision+1)
                sessions.write(ticket,next);ensureActive();if(!state.value.closed){mutable.update{it.copy(draft=next,saveFailed=false)};dirty=false}
            }}catch(error:Throwable){ensureActive();if(!state.value.closed)failure(error)}finally{mutable.update{it.copy(busy=false)}}
        }
    }
    fun failure(error:Throwable){mutable.update{it.copy(error=error.message ?: error.toString())}}
    fun close(){if(state.value.closed)return;saved[CLOSED]=true;mutable.update{it.copy(closed=true,loading=false,busy=false)};loadJob?.cancel();importJob?.cancel();saveJob?.cancel();releaseSession()}
    private fun releaseSession(){(cleanupScope ?: CoroutineScope(viewModelScope.coroutineContext.minusKey(Job)+SupervisorJob())).launch{runCatching{sessions.release(ticket)}}}
    fun stop(){viewModelScope.cancel()}
    companion object{const val KEY="book.metadata.ticket";private const val CLOSED="book.metadata.closed"}
}
