package io.legado.app.ui.highlight

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class HighlightManagementState(val rules: List<HighlightManagedRule> = emptyList(),
    val groups: List<String> = emptyList(), val draft: HighlightManagementDraft = HighlightManagementDraft(),
    val loaded: Boolean = false, val rowsReady: Boolean = loaded, val loading: Boolean = true, val busy: Boolean = false,
    val error: String? = null, val closed: Boolean = false) {
    val visible: List<HighlightManagedRule> get() = rules.filter { rule -> when(draft.filter) {
        null -> true
        UNGROUPED -> rule.group.isNullOrBlank()
        else -> rule.group == draft.filter
    } }
    val selected: List<HighlightManagedRule> get() = visible.filter { it.uuid in draft.selection }
    val canAct: Boolean get() = loaded && rowsReady && !busy && !closed
    companion object { const val UNGROUPED = "\u0000" }
}

class HighlightManagementViewModel(private val saved: SavedStateHandle,
    private val rules: HighlightManagementRepository, private val sessions: HighlightManagementSessionRepository,
    private val cleanupScope: CoroutineScope? = null) : ViewModel() {
    val ticket: String = saved.get<String>(KEY) ?: UUID.randomUUID().toString().also { saved[KEY]=it }
    private val mutable=MutableStateFlow(HighlightManagementState(closed=saved.get<Boolean>(CLOSED)==true,
        loading=saved.get<Boolean>(CLOSED)!=true))
    val state: StateFlow<HighlightManagementState> = mutable.asStateFlow()
    private val writes=Mutex()
    private var rowJob:Job?=null
    private var groupJob:Job?=null
    private var loadJob:Job?=null
    private var dirty=false
    private var observeFailed=false
    private var orderBaseline:List<HighlightManagedRule>?=null
    private var rangeBaseline:Set<String>?=null
    private val ready=CompletableDeferred<Unit>()
    init { if(!state.value.closed) load() else {ready.complete(Unit);releaseSession()} }
    private fun load() {
        loadJob?.cancel()
        loadJob=viewModelScope.launch {
            mutable.update{it.copy(loading=true,error=null)}
            try {
                val restored=sessions.read(ticket) ?: HighlightManagementDraft()
                ensureActive();if(state.value.closed)return@launch
                mutable.update{it.copy(draft=restored,loaded=true,loading=true)}
                if(!ready.isCompleted)ready.complete(Unit)
                observe()
            } catch(error:Throwable){ensureActive();mutable.update{it.copy(loading=false,error=error.message ?: error.toString())}}
        }
    }
    private fun observe() {
        observeFailed=false
        rowJob?.cancel();groupJob?.cancel()
        rowJob=viewModelScope.launch {
            try {rules.rows().collect { latest ->
                if(state.value.closed)return@collect
                cancelGesture()
                val old=state.value
                val filter=old.draft.filter?.takeIf { it==HighlightManagementState.UNGROUPED || latest.any { row -> row.group==it } }
                val visible=latest.filter { filter==null || (filter==HighlightManagementState.UNGROUPED && it.group.isNullOrBlank()) || it.group==filter }
                val selection=old.draft.selection.intersect(visible.mapTo(hashSetOf()){it.uuid})
                // Room updates cancel an incomplete gesture; only explicit release commits its preview.
                orderBaseline=null;rangeBaseline=null
                mutable.update{it.copy(rules=latest,rowsReady=true,loading=false,error=null)}
                if(filter!=old.draft.filter || selection!=old.draft.selection) edit { copy(filter=filter,selection=selection) }
            }} catch(error:Throwable){ensureActive();observeFailed=true;mutable.update{it.copy(loading=false,error=error.message ?: error.toString())}}
        }
        groupJob=viewModelScope.launch {
            try {rules.groups().collect{groups ->mutable.update{it.copy(groups=groups)}}}
            catch(error:Throwable){ensureActive();observeFailed=true;mutable.update{it.copy(error=error.message ?: error.toString())}}
        }
    }
    private fun edit(block: HighlightManagementDraft.()->HighlightManagementDraft) {
        if(state.value.closed)return
        val old=state.value.draft
        mutable.update{it.copy(draft=old.block().copy(revision=old.revision+1))};dirty=true
        viewModelScope.launch { try{flush()}catch(error:Throwable){ensureActive();mutable.update{it.copy(error=error.message ?: error.toString())}} }
    }
    suspend fun flush()=writes.withLock {
        if(!state.value.loaded || state.value.closed || !dirty)return@withLock
        val draft=state.value.draft.let { current -> rangeBaseline?.let { current.copy(selection=it) } ?: current };sessions.write(ticket,draft)
        if(state.value.draft.revision==draft.revision)dirty=false
    }
    fun filter(group:String?) {if(!state.value.canAct)return;cancelGesture();edit{copy(filter=group,selection=emptySet())}}
    fun select(uuid:String) {if(!state.value.canAct)return;edit{copy(selection=if(uuid in selection) selection-uuid else selection+uuid)}}
    fun selectAll(all:Boolean=true) {if(!state.value.canAct)return;val ids=state.value.visible.mapTo(linkedSetOf()){it.uuid}
        edit{copy(selection=if(all)selection+ids else (selection-ids)+(ids-selection))}}
    fun beginRange() {if(state.value.canAct && rangeBaseline==null)rangeBaseline=state.value.draft.selection}
    fun range(uuids:Set<String>) {
        val baseline=rangeBaseline ?: return
        val valid=uuids.intersect(state.value.visible.mapTo(hashSetOf()){it.uuid})
        mutable.update{it.copy(draft=it.draft.copy(selection=(baseline-valid)+(valid-baseline)))}
    }
    fun finishRange() {if(rangeBaseline==null)return;val selected=state.value.draft.selection;rangeBaseline=null;edit{copy(selection=selected)}}
    fun beginReorder() {if(state.value.canAct && orderBaseline==null)orderBaseline=state.value.rules}
    fun reorder(from:String,to:String) {
        if(orderBaseline==null)return
        val visible=state.value.visible.toMutableList();val a=visible.indexOfFirst{it.uuid==from};val b=visible.indexOfFirst{it.uuid==to}
        if(a<0 || b<0 || a==b)return
        val row=visible.removeAt(a);visible.add(b,row);val ids=visible.mapTo(hashSetOf()){it.uuid};val next=visible.iterator()
        mutable.update{it.copy(rules=it.rules.map{row ->if(row.uuid in ids)next.next() else row})}
    }
    fun finishReorder() {
        if(orderBaseline==null)return
        val ids=state.value.visible.map{it.uuid};orderBaseline=null
        mutate {rules.reorder(ids)}
    }
    fun cancelGesture() {
        orderBaseline?.let { baseline ->mutable.update{it.copy(rules=baseline)} };orderBaseline=null
        rangeBaseline?.let { baseline ->mutable.update{it.copy(draft=it.draft.copy(selection=baseline))} };rangeBaseline=null
    }
    fun move(uuid:String,toTop:Boolean)=mutate {rules.move(setOf(uuid),toTop)}
    fun moveSelection(toTop:Boolean) {val ids=state.value.selected.mapTo(hashSetOf()){it.uuid};if(ids.isNotEmpty())mutate{rules.move(ids,toTop)}}
    fun enable(uuid:String,enabled:Boolean)=mutate{rules.enable(setOf(uuid),enabled)}
    fun enableSelection(enabled:Boolean) {val ids=state.value.selected.mapTo(hashSetOf()){it.uuid};if(ids.isNotEmpty())mutate{rules.enable(ids,enabled)}}
    fun requestDelete(uuid:String?=null) {
        if(!state.value.canAct)return
        val selected=if(uuid==null)state.value.selected else state.value.visible.filter{it.uuid==uuid}
        if(selected.isNotEmpty())edit{copy(deletion=selected.mapTo(linkedSetOf()){it.uuid},deletionName=if(uuid==null)null else selected.single().displayName)}
    }
    fun dismissDelete(){edit{copy(deletion=emptySet(),deletionName=null)}}
    fun deleteConfirmed() {val ids=state.value.draft.deletion;if(ids.isEmpty())return;mutate{rules.delete(ids);edit{copy(deletion=emptySet(),deletionName=null)}}}
    private fun mutate(block:suspend ()->Unit) {
        if(!state.value.canAct)return
        cancelGesture();mutable.update{it.copy(busy=true,error=null)}
        viewModelScope.launch {
            try {block();ensureActive();if(!state.value.closed)queue(HighlightManagementAction.Refresh)}
            catch(error:Throwable){ensureActive();mutable.update{it.copy(error=error.message ?: error.toString())}}
            finally{mutable.update{it.copy(busy=false)}}
        }
    }
    fun action(action:HighlightManagementAction,id:Long?=null) {
        if(!state.value.canAct)return
        require(action in setOf(HighlightManagementAction.Add,HighlightManagementAction.Edit,HighlightManagementAction.Import,HighlightManagementAction.Groups))
        queue(action,id=id)
    }
    fun export(all:Boolean) {
        if(!state.value.canAct || state.value.draft.exporting!=null || state.value.draft.effects.any{it.action==HighlightManagementAction.Export})return
        val rows=if(all)state.value.rules else state.value.selected
        if(rows.isEmpty()){queue(HighlightManagementAction.EmptyExport);return}
        queue(HighlightManagementAction.Export,rows=rows)
    }
    fun share(){if(state.value.canAct && state.value.selected.isNotEmpty())queue(HighlightManagementAction.Share,rows=state.value.selected)}
    private fun queue(action:HighlightManagementAction,id:Long?=null,rows:List<HighlightManagedRule> = emptyList(),text:String?=null) {
        val effect=HighlightManagementEffect(UUID.randomUUID().toString(),action,id,rows,text)
        edit{copy(effects=effects+effect)}
    }
    suspend fun consume(token:String,canDeliver:()->Boolean):HighlightManagementEffect? {
        ready.await();cancelGesture();flush()
        mutable.update{it.copy(busy=true)}
        try { return writes.withLock {
            currentCoroutineContext().ensureActive()
            if(!canDeliver() || state.value.closed)return@withLock null
            val before=state.value.draft
            val effect=before.effects.firstOrNull{it.token==token} ?: return@withLock null
            val next=before.copy(effects=before.effects.filterNot{it.token==token},
                exporting=if(effect.action==HighlightManagementAction.Export)effect.token else before.exporting,revision=before.revision+1)
            sessions.write(ticket,next)
            // Non-cooperative IO may finish after PAUSE or cancellation: roll back the same receipt.
            if(state.value.closed) { currentCoroutineContext().ensureActive();return@withLock null }
            if(!currentCoroutineContext().isActive || !canDeliver()) {
                withContext(NonCancellable){sessions.write(ticket,before.copy(revision=next.revision+1))}
                mutable.update{it.copy(draft=before.copy(revision=next.revision+1))};dirty=false
                currentCoroutineContext().ensureActive();return@withLock null
            }
            mutable.update{it.copy(draft=next)};dirty=false;effect
        } } finally {mutable.update{it.copy(busy=false)}}
    }
    fun exportResult(url:String?,owner:String?=null)=viewModelScope.launch {
        ready.await()
        if(state.value.closed)return@launch
        try { writes.withLock {
            val before=state.value.draft
            if(before.exporting==null || (owner!=null && before.exporting!=owner) || state.value.closed)return@withLock
            mutable.update{it.copy(busy=true)}
            val next=before.copy(exporting=null,exportResult=url,revision=before.revision+1)
            withContext(NonCancellable){sessions.write(ticket,next)}
            ensureActive();if(!state.value.closed)mutable.update{it.copy(draft=next)}
            dirty=false
        } } catch(error:Throwable){ensureActive();deliveryFailure(error)} finally {mutable.update{it.copy(busy=false)}}
    }
    fun importResult(url:String?) {if(url!=null && !state.value.closed)viewModelScope.launch {ready.await();if(!state.value.closed)queue(HighlightManagementAction.Import,text=url)} }
    fun dismissExportResult(){edit{copy(exportResult=null)}}
    fun copyExportResult(){val url=state.value.draft.exportResult ?: return;queue(HighlightManagementAction.Copy,text=url);dismissExportResult()}
    fun deliveryFailure(error:Throwable){mutable.update{it.copy(error=error.message ?: error.toString())}}
    fun retry(){if(!state.value.loaded){load();return};if(observeFailed)observe();mutable.update{it.copy(error=null)};viewModelScope.launch{try{flush()}catch(error:Throwable){ensureActive();deliveryFailure(error)}}}
    fun close() {
        if(state.value.closed)return
        saved[CLOSED]=true;cancelGesture();mutable.update{it.copy(closed=true,loading=false)}
        loadJob?.cancel();rowJob?.cancel();groupJob?.cancel()
        releaseSession()
    }
    private fun releaseSession() {
        (cleanupScope ?: CoroutineScope(viewModelScope.coroutineContext.minusKey(Job)+SupervisorJob())).launch {
            try {sessions.release(ticket)} catch (_:Exception) { /* Durable fence prevents a late writer from resurrecting this session. */ }
        }
    }
    fun stop(){cancelGesture();viewModelScope.cancel()}
    companion object {const val KEY="highlight.management.ticket";private const val CLOSED="highlight.management.closed"}
}
