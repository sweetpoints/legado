package io.legado.app.ui.about

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Snapshot-only state; the activity owns reader intents, images and lifecycle delivery. */
data class ReadingHistoryState(val query: String = "", val snapshot: ReadingHistorySnapshot = ReadingHistorySnapshot(emptyList(), 0, 0, emptyList()),
    val preferences: ReadingHistoryPreferences = ReadingHistoryPreferences(), val ready: Boolean = false,
    val loading: Boolean = true, val busy: Boolean = false, val preferencesDirty: Boolean = false, val error: String? = null,
    val confirmation: ReadingHistoryConfirmation? = null, val navigation: ReadingHistoryNavigation? = null)
class ReadingHistoryViewModel(private val repository: ReadingHistoryRepository,
    private val drafts: ReadingHistoryDraftRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(ReadingHistoryState())
    val state = mutable.asStateFlow()
    private var draft = ReadingHistoryDraft()
    private var ticket: String? = saved["history.ticket"]
    private var stopped = false
    private var generation = 0L
    private var loadJob: Job? = null
    private var actionJob: Job? = null
    private val actions = Mutex()
    private val preferences = Mutex()
    private data class PreferenceWrite(val field: ReadingHistoryPreference, val value: ReadingHistoryPreferences, val revision: Long)
    private val preferenceWrites = Channel<PreferenceWrite>(Channel.UNLIMITED)
    private var preferenceRevision = 0L
    private val fieldRevisions = mutableMapOf<ReadingHistoryPreference, Long>()
    private val failedPreferences = mutableSetOf<ReadingHistoryPreference>()
    init {
        viewModelScope.launch { for (write in preferenceWrites) preferences.withLock {
            try {
                repository.preferences(write.value, setOf(write.field))
                val latest = repository.preferences()
                currentCoroutineContext().ensureActive()
                if (fieldRevisions[write.field] == write.revision) failedPreferences.remove(write.field)
                publish { it.copy(preferences = if (preferenceRevision == write.revision && failedPreferences.isEmpty()) latest else it.preferences, preferencesDirty = failedPreferences.isNotEmpty()) }
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (fieldRevisions[write.field] == write.revision) failedPreferences.add(write.field)
                publish { it.copy(preferencesDirty = failedPreferences.isNotEmpty(), error = error.localizedMessage ?: "ERROR") }
            }
        } }
        viewModelScope.launch {
        try {
            val id = ticket ?: drafts.create().also { ticket = it; saved["history.ticket"] = it }
            val restored = drafts.read(id)
            val prefs = repository.preferences()
            currentCoroutineContext().ensureActive()
            if (stopped) return@launch
            draft = restored
            publish { it.copy(query = restored.query, confirmation = restored.confirmation, navigation = restored.navigation, preferences = prefs, ready = true) }
            refresh()
        } catch (error: Throwable) { currentCoroutineContext().ensureActive(); fail(error) }
    } }
    private fun publish(change: (ReadingHistoryState) -> ReadingHistoryState) { if (!stopped) mutable.value = change(mutable.value) }
    private fun fail(error: Throwable) = publish { it.copy(loading = false, busy = false, error = error.localizedMessage ?: "ERROR") }
    private fun changeDraft(change: (ReadingHistoryDraft) -> ReadingHistoryDraft): ReadingHistoryDraft {
        draft = change(draft).copy(revision = draft.revision + 1); return draft
    }
    private suspend fun persist(value: ReadingHistoryDraft) { drafts.write(checkNotNull(ticket), value) }
    private fun persistAsync(value: ReadingHistoryDraft) { viewModelScope.launch {
        try { withContext(NonCancellable) { persist(value) } }
        catch (error: Throwable) { currentCoroutineContext().ensureActive(); fail(error) }
    } }
    suspend fun resume() {
        if (!state.value.ready || stopped) return
        val revision = preferenceRevision
        preferences.withLock {
            try {
                val latest = repository.preferences(); currentCoroutineContext().ensureActive()
                if (revision == preferenceRevision && failedPreferences.isEmpty()) publish { it.copy(preferences = latest) }
                refresh()
            } catch (error: Throwable) { currentCoroutineContext().ensureActive(); fail(error) }
        }
    }
    fun refresh() {
        if (!state.value.ready || state.value.busy || stopped) return
        loadJob?.cancel(); val token = ++generation
        val query = state.value.query; val sort = state.value.preferences.sort
        publish { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            try {
                val snapshot = repository.load(query, sort)
                currentCoroutineContext().ensureActive()
                if (token == generation) publish { it.copy(snapshot = snapshot, loading = false) }
            } catch (error: Throwable) { currentCoroutineContext().ensureActive(); if (token == generation) fail(error) }
        }
    }
    fun query(value: String) {
        if (!state.value.ready || stopped || state.value.query == value) return
        persistAsync(changeDraft { it.copy(query = value) }); publish { it.copy(query = value) }; refresh()
    }
    private fun mergePreference(current: ReadingHistoryPreferences, field: ReadingHistoryPreference, value: ReadingHistoryPreferences): ReadingHistoryPreferences = when (field) {
        ReadingHistoryPreference.Enabled -> current.copy(enabled = value.enabled)
        ReadingHistoryPreference.Simple -> current.copy(simple = value.simple)
        ReadingHistoryPreference.Days -> current.copy(days = value.days)
        ReadingHistoryPreference.Seconds -> current.copy(seconds = value.seconds)
        ReadingHistoryPreference.Fixed -> current.copy(fixed = value.fixed)
        ReadingHistoryPreference.Sort -> current.copy(sort = value.sort)
    }
    fun preference(field: ReadingHistoryPreference, value: ReadingHistoryPreferences) {
        if (!state.value.ready || stopped) return
        val requested = mergePreference(state.value.preferences, field, value)
        val revision = ++preferenceRevision; fieldRevisions[field] = revision
        publish { it.copy(preferences = requested) }
        preferenceWrites.trySend(PreferenceWrite(field, requested, revision))
        if (field == ReadingHistoryPreference.Sort) refresh()
    }
    fun retry() {
        if (failedPreferences.isEmpty()) refresh()
        else failedPreferences.toList().forEach { preference(it, state.value.preferences) }
    }
    fun clear() = confirm(ReadingHistoryConfirmation())
    fun delete(identity: ReadingHistoryIdentity) {
        if (state.value.snapshot.rows.any { it.identity == identity }) confirm(ReadingHistoryConfirmation(identity))
    }
    fun chooseAuthor() {
        val identity = state.value.confirmation?.identity ?: return
        val row = state.value.snapshot.rows.find { it.identity == identity } ?: return
        if (row.combined && row.legacyAuthors.size > 1) confirm(ReadingHistoryConfirmation(identity, chooseAuthor = true))
    }
    fun removeAuthor(author: String) {
        val identity = state.value.confirmation?.identity ?: return
        val row = state.value.snapshot.rows.find { it.identity == identity } ?: return
        if (row.combined && row.legacyAuthors.size > 1 && author in row.legacyAuthors) confirm(ReadingHistoryConfirmation(identity, author))
    }
    private fun confirm(value: ReadingHistoryConfirmation?) {
        if (!state.value.ready || state.value.busy || stopped) return
        persistAsync(changeDraft { it.copy(confirmation = value) }); publish { it.copy(confirmation = value, error = null) }
    }
    fun dismissConfirmation() = confirm(null)
    fun confirmDelete() {
        val confirmation = state.value.confirmation ?: return
        if (!state.value.ready || state.value.busy || confirmation.chooseAuthor || stopped) return
        loadJob?.cancel(); ++generation
        publish { it.copy(busy = true, loading = false, error = null) }
        actionJob = viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    if (confirmation.identity == null) repository.clear()
                    else if (confirmation.author == null) repository.delete(confirmation.identity)
                    else repository.removeAuthor(confirmation.identity, confirmation.author)
                    persist(changeDraft { it.copy(confirmation = null) })
                }
                currentCoroutineContext().ensureActive()
                publish { it.copy(busy = false, confirmation = null) }; refresh()
            } catch (error: Throwable) { currentCoroutineContext().ensureActive(); fail(error); refresh() }
        }
    }
    fun open(identity: ReadingHistoryIdentity) {
        if (!state.value.ready || state.value.busy || stopped || state.value.navigation != null || state.value.snapshot.rows.none { it.identity == identity }) return
        publish { it.copy(busy = true, error = null) }
        actionJob = viewModelScope.launch {
            try {
                val destination = repository.destination(identity)
                currentCoroutineContext().ensureActive()
                if (stopped) return@launch
                val navigation = ReadingHistoryNavigation(UUID.randomUUID().toString(), destination)
                val value = changeDraft { it.copy(navigation = navigation) }
                withContext(NonCancellable) { persist(value) }
                currentCoroutineContext().ensureActive()
                publish { it.copy(busy = false, navigation = navigation) }
            } catch (error: Throwable) { currentCoroutineContext().ensureActive(); fail(error) }
        }
    }
    suspend fun consumeNavigation(id: String, canDeliver: () -> Boolean = { true }): ReadingHistoryDestination? = actions.withLock {
        if (stopped || state.value.navigation?.id != id) return@withLock null
        val navigation = checkNotNull(state.value.navigation)
        val caller = currentCoroutineContext()
        // Disk IO can outlive a paused collector. Roll back its claim before allowing a new owner.
        withContext(NonCancellable) {
            persist(changeDraft { it.copy(navigation = null) })
            if (!caller.isActive || !canDeliver() || stopped) {
                if (!stopped) persist(changeDraft { it.copy(navigation = navigation) })
                null
            } else {
                publish { it.copy(navigation = null) }; navigation.destination
            }
        }
    }

    fun dismissError() { publish { it.copy(error = null) } }
    suspend fun flush() { if (ticket != null) withContext(NonCancellable) { persist(draft) } }
    suspend fun abandon() {
        stopped = true; loadJob?.cancel(); actionJob?.cancel()
        ticket?.let { withContext(NonCancellable) { drafts.release(it) } }; saved.remove<String>("history.ticket")
    }
    fun stop() {
        stopped = true; loadJob?.cancel(); actionJob?.cancel()
        if (ticket != null) viewModelScope.launch(NonCancellable) { runCatching { persist(draft) } }
    }
    override fun onCleared() { stop() }
}
