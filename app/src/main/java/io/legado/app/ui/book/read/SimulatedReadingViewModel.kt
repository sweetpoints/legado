package io.legado.app.ui.book.read

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SimulatedReadingState(val loading: Boolean = true, val saving: Boolean = false,
    val settings: SimulatedReadingSettings? = null, val error: String? = null, val dateOpen: Boolean = false,
    val finished: Boolean = false, val applied: Boolean = false)
class SimulatedReadingViewModel(private val repository: SimulatedReadingRepository,
    private val requests: SimulatedReadingRequestRepository, private val ticket: String,
    private val saved: SavedStateHandle, private val cleanup: CoroutineScope) : ViewModel() {
    private val mutable = MutableStateFlow(SimulatedReadingState())
    val state: StateFlow<SimulatedReadingState> = mutable
    private var request: SimulatedReadingRequest? = null
    private var loading: Job? = null
    init { load() }
    fun load() {
        if(state.value.saving) return
        mutable.value=state.value.copy(loading=true,error=null)
        loading?.cancel()
        loading=viewModelScope.launch {
            try {
                val value=requests.read(ticket);currentCoroutineContext().ensureActive();request=value
                val initial= if(saved.get<Boolean>("simulation.loaded")==true) value.initial.copy(
                    enabled=saved.get<Boolean>("simulation.enabled") ?: value.initial.enabled,
                    date=saved.get<String>("simulation.date") ?: value.initial.date,
                    start=saved.get<String>("simulation.start") ?: value.initial.start,
                    daily=saved.get<String>("simulation.daily") ?: value.initial.daily) else value.initial.copy(start=value.initial.start.take(5),daily=value.initial.daily.take(5),date=value.initial.date.take(10))
                checkpoint(initial)
                mutable.value=SimulatedReadingState(loading=false,settings=initial,
                    dateOpen=saved.get<Boolean>("simulation.dateOpen")==true,
                    finished=saved.get<Boolean>("simulation.finished")==true,
                    applied=saved.get<Boolean>("simulation.applied")==true)
            } catch(error: CancellationException) { throw error }
            catch(error: Exception) { mutable.value=state.value.copy(loading=false,error=error.localizedMessage ?: "Unable to load simulation settings") }
        }
    }
    private fun checkpoint(value: SimulatedReadingSettings) {
        saved["simulation.loaded"]=true;saved["simulation.enabled"]=value.enabled;saved["simulation.date"]=value.date
        saved["simulation.start"]=value.start;saved["simulation.daily"]=value.daily
    }
    private fun change(block:(SimulatedReadingSettings)->SimulatedReadingSettings) {
        if(state.value.loading || state.value.saving || state.value.finished) return
        val current=state.value.settings ?: return;val next=block(current);checkpoint(next)
        mutable.value=state.value.copy(settings=next,error=null)
    }
    fun enabled(value:Boolean)=change { it.copy(enabled=value) }
    fun start(value:String) { if(value.length<=5 && value.all(Char::isDigit)) change { it.copy(start=value) } }
    fun daily(value:String) { if(value.length<=5 && value.all(Char::isDigit)) change { it.copy(daily=value) } }
    fun date(value:String) { if(value.length<=10 && runCatching { java.time.LocalDate.parse(value) }.isSuccess) { change { it.copy(date=value) };dateOpen(false) } }
    fun dateOpen(value:Boolean) { if(!state.value.saving && !state.value.loading && !state.value.finished) { saved["simulation.dateOpen"]=value;mutable.value=state.value.copy(dateOpen=value) } }
    fun save() {
        if(state.value.loading || state.value.saving || state.value.finished) return
        val value=state.value.settings ?: return;val owner=request ?: return
        mutable.value=state.value.copy(saving=true,error=null,dateOpen=false);saved["simulation.dateOpen"]=false
        viewModelScope.launch {
            try {
                val result=repository.save(owner.bookUrl,value);currentCoroutineContext().ensureActive()
                val config=requireNotNull(result.readConfig)
                val normalized=value.copy(date=config.startDate.toString(),start=(config.startChapter ?: 0).toString(),daily=config.dailyChapters.toString())
                checkpoint(normalized);mutable.value=state.value.copy(settings=normalized);finish(true)
            }
            catch(error: CancellationException) { throw error }
            catch(error: Exception) { mutable.value=state.value.copy(saving=false,error=error.localizedMessage ?: "Unable to save simulation settings") }
        }
    }
    fun retry() { if(request==null) load() else save() }
    fun cancel() { if(!state.value.saving && !state.value.finished) { loading?.cancel();finish(false) } }
    private fun finish(applied:Boolean) {
        saved["simulation.finished"]=true;saved["simulation.applied"]=applied
        mutable.value=state.value.copy(loading=false,saving=false,finished=true,applied=applied)
    }
    fun claim(): SimulatedReadingRequest? {
        if(!state.value.finished || !state.value.applied || saved.get<Boolean>("simulation.consumed")==true) return null
        val owner=request ?: return null
        saved["simulation.consumed"]=true
        return owner.copy(initial=state.value.settings ?: owner.initial)
    }
    fun release() { cleanup.launch { runCatching { requests.release(ticket) } } }
    fun stop() { viewModelScope.cancel() }
}
