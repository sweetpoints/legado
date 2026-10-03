package io.legado.app.ui.rss.subscription

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

enum class RuleSubscriptionIssue { EmptyUrl, DuplicateUrl, Missing, Conflict, Failure }
data class RuleSubscriptionState(val rows: List<RuleSubscription> = emptyList(), val loading: Boolean = true, val loaded: Boolean = false,
    val editor: RuleSubscriptionEditor? = null, val busy: Boolean = false, val pendingSave: Boolean = false,
    val navigation: RuleSubscriptionOpen? = null, val issue: RuleSubscriptionIssue? = null,
    val error: String? = null, val closed: Boolean = false)

class RuleSubscriptionViewModel(private val rules: RuleSubscriptionRepository,
    private val drafts: RuleSubscriptionDraftRepository, private val saved: SavedStateHandle,
    cleanupScope: CoroutineScope? = null) : ViewModel() {
    private val ticket=saved.get<String>("rule.subscription.ticket") ?: UUID.randomUUID().toString().also { saved["rule.subscription.ticket"]=it }
    private val mutable=MutableStateFlow(RuleSubscriptionState(closed=saved["rule.subscription.closed"] ?: false,loading=saved.get<Boolean>("rule.subscription.closed")!=true))
    val state=mutable.asStateFlow()
    private val cleanup=cleanupScope ?: CoroutineScope(SupervisorJob()+viewModelScope.coroutineContext.minusKey(Job))
    private var record=RuleSubscriptionDraft()
    private var baseline=emptyList<RuleSubscription>();private var preview: List<Long>?=null
    private var stopped=false
    private val navigationGate=Mutex()
    private val writes=Channel<RuleSubscriptionDraft>(Channel.CONFLATED)
    private val writer=viewModelScope.launch {
        for (draft in writes) try { drafts.write(ticket,draft) } catch(error:Exception) { currentCoroutineContext().ensureActive();failure(error) }
    }
    private var rowsJob:Job?=null
    private var loadJob:Job?=null
    init {
        if (state.value.closed) { stop();release() }
        else { observeRows();loadDraft() }
    }
    private fun observeRows() {
        rowsJob?.cancel()
        rowsJob=viewModelScope.launch {
            try { rules.rows().collect { baseline=it;publishRows() } }
            catch(error:Exception) { currentCoroutineContext().ensureActive();failure(error) }
        }
    }
    private fun loadDraft() {
        mutable.value=state.value.copy(loading=true,issue=null,error=null)
        loadJob=viewModelScope.launch {
            var read=false
            try {
                val restored=drafts.read(ticket) ?: RuleSubscriptionDraft();currentCoroutineContext().ensureActive()
                read=true;record=restored
                if (restored.pendingSave!=null) record=drafts.save(ticket,restored)
                currentCoroutineContext().ensureActive()
                mutable.value=state.value.copy(loading=false,loaded=true,editor=record.editor,pendingSave=record.pendingSave!=null,navigation=record.navigation)
            } catch(error:Exception) {
                currentCoroutineContext().ensureActive();mutable.value=state.value.copy(loading=false,loaded=read,editor=record.editor,pendingSave=record.pendingSave!=null,navigation=record.navigation);failure(error)
            }
        }
    }
    fun retry() {
        if(stopped || state.value.loading || state.value.busy || state.value.closed) return
        if(!state.value.loaded) { loadDraft();return }
        if(state.value.pendingSave) { save();return }
        observeRows();mutable.value=state.value.copy(busy=true,issue=null,error=null)
        viewModelScope.launch {
            try { flush();currentCoroutineContext().ensureActive() }
            catch(error:Exception) { currentCoroutineContext().ensureActive();failure(error) }
            finally { if(!stopped) mutable.value=state.value.copy(busy=false) }
        }
    }
    private fun publishRows() {
        val ids=preview
        val rows=if(ids==null) baseline else ids.mapNotNull { id -> baseline.find { it.id==id } }+baseline.filter { it.id !in ids }
        mutable.value=state.value.copy(rows=rows)
    }
    private fun update(next: RuleSubscriptionDraft) {
        record=next.copy(revision=record.revision+1)
        mutable.value=state.value.copy(editor=record.editor,pendingSave=record.pendingSave!=null,navigation=record.navigation,issue=null,error=null)
        writes.trySend(record)
    }
    fun create() {
        if (blocked() || state.value.editor!=null) return
        update(record.copy(editor=RuleSubscriptionEditor(newId=(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1))))
    }
    fun edit(id:Long) {
        if (blocked() || state.value.editor!=null) return
        mutable.value=state.value.copy(busy=true,issue=null,error=null)
        viewModelScope.launch {
            try {
                val row=rules.load(id) ?: throw RuleSubscriptionMissing();currentCoroutineContext().ensureActive()
                update(record.copy(editor=RuleSubscriptionEditor(row.id,row.id,row.name,row.url,row.type.takeIf { it in 0..2 } ?: 0,row.automatic,row.interval.toString(),row.silent,row.interval>0,row.interval)))
            } catch(error:Exception) { currentCoroutineContext().ensureActive();failure(error) }
            finally { if (!stopped) mutable.value=state.value.copy(busy=false) }
        }
    }
    private fun change(block:(RuleSubscriptionEditor)->RuleSubscriptionEditor) {
        if (blocked() || state.value.pendingSave) return
        val editor=record.editor ?: return;update(record.copy(editor=block(editor)))
    }
    fun name(value:String)=change { it.copy(name=value) }
    fun url(value:String)=change { it.copy(url=value) }
    fun type(value:Int)=change { it.copy(type=value.coerceIn(0,2)) }
    fun automatic(value:Boolean)=change {
        val interval=if(!value) "0" else if(it.originalInterval==0) "24" else it.interval
        it.copy(automatic=value,interval=interval,silent=if(value) it.silent else false,silentEnabled=value)
    }
    fun interval(value:String)=change {
        if(value.toIntOrNull()==0) it.copy(interval=value,automatic=false,silent=false,silentEnabled=false)
        else it.copy(interval=value,silentEnabled=true)
    }
    fun silent(value:Boolean)=change { if(it.silentEnabled) it.copy(silent=value) else it }
    fun cancelEditor() {
        if(blocked()) return
        update(record.copy(editor=null,pendingSave=null))
    }
    fun save() {
        if(blocked() || record.editor==null) return
        mutable.value=state.value.copy(busy=true,issue=null,error=null)
        viewModelScope.launch {
            try {
                drafts.write(ticket,record);currentCoroutineContext().ensureActive()
                val completed=drafts.save(ticket,record);currentCoroutineContext().ensureActive()
                record=completed;mutable.value=state.value.copy(editor=null,pendingSave=false,issue=null,error=null)
            } catch(error:Exception) {
                currentCoroutineContext().ensureActive()
                // The durable pending receipt controls retry after Room committed but final file write failed.
                val disk=runCatching { drafts.read(ticket) }.getOrNull();currentCoroutineContext().ensureActive()
                if(disk?.pendingSave!=null) { record=disk;mutable.value=state.value.copy(editor=disk.editor,pendingSave=true) }
                failure(error)
            } finally { if(!stopped) mutable.value=state.value.copy(busy=false) }
        }
    }
    fun delete(id:Long) {
        if(blocked()) return
        mutable.value=state.value.copy(busy=true,issue=null,error=null)
        viewModelScope.launch { try { rules.delete(id);currentCoroutineContext().ensureActive() }
            catch(error:Exception) { currentCoroutineContext().ensureActive();failure(error) }
            finally { if(!stopped) mutable.value=state.value.copy(busy=false) } }
    }
    fun open(id:Long) {
        if(blocked() || record.navigation!=null) return
        val row=baseline.find { it.id==id } ?: return
        if(row.type !in 0..2) return
        update(record.copy(navigation=RuleSubscriptionOpen(UUID.randomUUID().toString(),row.type,row.url)))
    }
    fun openFailure(error:Exception) { failure(error) }
    suspend fun consumeOpen(token:String,canDeliver:()->Boolean):RuleSubscriptionOpen? = navigationGate.withLock {
        currentCoroutineContext().ensureActive()
        val navigation=record.navigation?.takeIf { it.token==token } ?: return@withLock null
        if(stopped || !canDeliver()) return@withLock null
        flush();currentCoroutineContext().ensureActive()
        if(stopped || !canDeliver() || record.navigation?.token!=token) return@withLock null
        val before=record
        val claimed=before.copy(navigation=null,revision=before.revision+1)
        var committed=false
        try {
            drafts.write(ticket,claimed);committed=true
            currentCoroutineContext().ensureActive()
            if(stopped || !canDeliver()) {
                val rollback=before.copy(revision=claimed.revision+1)
                withContext(NonCancellable) { drafts.write(ticket,rollback) }
                if(!stopped) { record=rollback;mutable.value=state.value.copy(navigation=navigation) }
                return@withLock null
            }
            record=claimed;mutable.value=state.value.copy(navigation=null);navigation
        } catch(error:Exception) {
            if(committed || error is CancellationException) {
                val rollback=before.copy(revision=claimed.revision+1)
                withContext(NonCancellable) { runCatching { drafts.write(ticket,rollback) } }
                if(!stopped) { record=rollback;mutable.value=state.value.copy(navigation=navigation) }
            }
            throw error
        }
    }
    fun beginDrag():Boolean {
        if(blocked() || state.value.editor!=null) return false
        if(preview==null) preview=state.value.rows.map { it.id }
        return true
    }
    fun move(id:Long,target:Long) {
        val current=preview ?: return
        val from=current.indexOf(id);val to=current.indexOf(target)
        if(from<0 || to<0 || from==to) return
        preview=current.toMutableList().also { it.add(to,it.removeAt(from)) };publishRows()
    }
    fun cancelDrag() { preview=null;publishRows() }
    fun finishDrag() {
        val ids=preview ?: return;preview=null;publishRows()
        if(ids==baseline.map { it.id } || stopped) return
        mutable.value=state.value.copy(busy=true,issue=null,error=null)
        viewModelScope.launch { try { rules.reorder(ids);currentCoroutineContext().ensureActive() }
            catch(error:Exception) { currentCoroutineContext().ensureActive();failure(error) }
            finally { if(!stopped) mutable.value=state.value.copy(busy=false) } }
    }
    private fun blocked()=stopped || !state.value.loaded || state.value.loading || state.value.busy || state.value.closed
    private fun failure(error:Exception) {
        if(error is CancellationException) throw error
        if(stopped) return
        val issue=when(error) { is RuleSubscriptionEmptyUrl->RuleSubscriptionIssue.EmptyUrl;is RuleSubscriptionDuplicateUrl->RuleSubscriptionIssue.DuplicateUrl
            is RuleSubscriptionMissing->RuleSubscriptionIssue.Missing;is RuleSubscriptionConflict->RuleSubscriptionIssue.Conflict;else->RuleSubscriptionIssue.Failure }
        mutable.value=state.value.copy(issue=issue,error=if(error is RuleSubscriptionDuplicateUrl) error.name else error.localizedMessage)
    }
    suspend fun flush() { drafts.write(ticket,record) }
    fun close() {
        if(state.value.closed) return
        saved["rule.subscription.closed"]=true;mutable.value=state.value.copy(closed=true);stop();release()
    }
    private fun release() { cleanup.launch { runCatching { drafts.release(ticket) } } }
    internal fun stop() { stopped=true;preview=null;writer.cancel();writes.close();viewModelScope.cancel() }
    override fun onCleared() { stop();super.onCleared() }
}
