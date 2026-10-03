package io.legado.app.ui.config

import androidx.lifecycle.*
import com.google.gson.JsonParser
import io.legado.app.data.preferences.*
import io.legado.app.help.config.normalizeJsSourceApiToken
import io.legado.app.model.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal data class OtherSettingsState(val loading: Boolean = true, val failed: Boolean = false, val busy: Boolean = false,
    val settings: OtherSettingsSnapshot? = null, val draft: OtherSettingsDraft? = null, val error: String? = null,
    val writeFailed: Boolean = false, val pendingCommit: Boolean = false, val interrupted: Boolean = false, val invalidNumber: Boolean = false,
    val bookTreeEvent: String? = null)
internal class OtherSettingsViewModel(private val repository: OtherSettingsRepository, private val drafts: OtherSettingsDraftRepository,
    private val saved: SavedStateHandle) : ViewModel() {
    val session = saved.get<String>("otherSession") ?: UUID.randomUUID().toString().also { saved["otherSession"] = it }
    private val mutable = MutableStateFlow(OtherSettingsState(bookTreeEvent = saved["otherTreeEvent"])); val state = mutable.asStateFlow()
    private var current = OtherSettingsDraft(); private var revision = 0L; private var initialized = false; private var stopped = false; private var generation = 0
    private var observer: Job? = null; private var operation: Job? = null; private var pendingAccepted: OtherSettingsDraft? = null; private var earlyTree: String? = null
    private val gate = Mutex(); private val updates = MutableStateFlow<OtherSettingsDraft?>(null)
    private val writer = viewModelScope.launch { updates.filterNotNull().collect { value ->
        try { persist(value); currentCoroutineContext().ensureActive(); if (!stopped && current.revision == value.revision) mutable.value = state.value.copy(writeFailed = false) }
        catch (canceled: CancellationException) { throw canceled }
        catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped && current.revision == value.revision) mutable.value = state.value.copy(error = error.localizedMessage.orEmpty(), writeFailed = true) }
    } }
    init { initialize() }
    private fun nextRevision() = maxOf(System.nanoTime(), revision + 1).also { revision = it }
    private fun usable() = initialized && !stopped && !state.value.loading && !state.value.failed && !state.value.busy && !state.value.pendingCommit && !state.value.writeFailed
    private suspend fun persist(value: OtherSettingsDraft) = gate.withLock { drafts.write(session, value) }
    private fun update(value: OtherSettingsDraft) { current = value.copy(revision = nextRevision()); mutable.value = state.value.copy(draft = current); updates.value = current }
    private fun initialize() {
        observer?.cancel(); val token = ++generation; mutable.value = state.value.copy(loading = true, failed = false, error = null)
        observer = viewModelScope.launch {
            try {
                if (!initialized) {
                    current = drafts.open(session); currentCoroutineContext().ensureActive(); revision = maxOf(revision, current.revision); initialized = true
                    val consumed = saved.get<String>("otherConsumedEffect")
                    current.effects.indexOfFirst { it.id == consumed }.takeIf { it >= 0 }?.let { index -> update(current.copy(effects = current.effects.drop(index + 1))) }
                }
                repository.observe().collect { settings ->
                    currentCoroutineContext().ensureActive()
                    if (!stopped && generation == token) {
                        mutable.value = state.value.copy(loading = false, failed = false, settings = settings, draft = current, interrupted = current.mutation != null)
                        earlyTree?.takeIf { usable() && current.editor == null && current.mutation == null }?.let { earlyTree = null; textMutation(OtherText.BookTree, it) }
                    }
                }
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped && generation == token) mutable.value = state.value.copy(loading = false, failed = true, error = error.localizedMessage.orEmpty()) }
        }
    }
    fun edit(editor: OtherEditor) {
        if (!usable() || current.mutation != null || current.effects.isNotEmpty()) return
        mutable.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try {
                val text = when {
                    OtherText.entries.any { it.name == editor.name } -> repository.readText(OtherText.valueOf(editor.name))
                    OtherNumber.entries.any { it.name == editor.name } -> state.value.settings!!.numbers.getValue(OtherNumber.valueOf(editor.name)).toString()
                    else -> state.value.settings!!.choices.getValue(OtherChoice.valueOf(editor.name))
                }
                currentCoroutineContext().ensureActive(); if (!stopped) update(current.copy(editor = editor, text = text, selectionStart = text.length, selectionEnd = text.length))
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(error = error.localizedMessage.orEmpty()) }
            finally { if (!stopped && currentCoroutineContext().isActive) mutable.value = state.value.copy(busy = false) }
        }
    }
    fun text(value: String, start: Int = value.length, end: Int = start) {
        if (!usable() || current.editor == null || current.mutation != null) return
        update(current.copy(text = value, selectionStart = start.coerceIn(0, value.length), selectionEnd = end.coerceIn(0, value.length)))
        mutable.value = state.value.copy(invalidNumber = false)
    }
    fun dismiss() { if (usable() && current.mutation == null) update(current.copy(editor = null, text = "", selectionStart = 0, selectionEnd = 0)) }
    fun boolean(key: OtherSwitch, value: Boolean) {
        if (!usable() || current.editor != null || current.mutation != null || !state.value.settings!!.visible(key)) return
        val old = state.value.settings!!
        if (old.switches.getValue(key) == value) return
        val effects = when (key) {
            OtherSwitch.TokenRequired -> listOf(OtherEffect.RestartWeb, OtherEffect.RestartMcp)
            OtherSwitch.Log -> listOf(OtherEffect.LogConfiguration); OtherSwitch.Cronet -> if (value) listOf(OtherEffect.DownloadCronet) else emptyList()
            OtherSwitch.Discovery, OtherSwitch.Rss -> listOf(OtherEffect.NotifyMain)
            OtherSwitch.LiveNotifications -> if (value) listOf(OtherEffect.PromotedNotificationSettings) else emptyList(); else -> emptyList()
        }
        apply(OtherMutation(UUID.randomUUID().toString(), OtherMutationKind.Boolean, key.name, boolean = value, effects = effects))
    }
    fun confirm() {
        if (!usable() || current.mutation != null) return
        val editor = current.editor ?: return
        when {
            OtherNumber.entries.any { it.name == editor.name } -> {
                val key = OtherNumber.valueOf(editor.name); val value = current.text.toIntOrNull()
                if (value == null || value !in key.minimum..key.maximum) { mutable.value = state.value.copy(invalidNumber = true); return }
                val effects = if (state.value.settings!!.numbers.getValue(key) == value) emptyList() else when (key) {
                    OtherNumber.Threads -> listOf(OtherEffect.ThreadsChanged); OtherNumber.WebPort -> listOf(OtherEffect.RestartWeb)
                    OtherNumber.McpPort -> listOf(OtherEffect.RestartMcp); OtherNumber.BitmapCache -> listOf(OtherEffect.ResizeBitmapCache); else -> emptyList()
                }
                apply(OtherMutation(UUID.randomUUID().toString(), OtherMutationKind.Number, key.name, number = value, effects = effects))
            }
            OtherText.entries.any { it.name == editor.name } -> textMutation(OtherText.valueOf(editor.name), current.text)
            else -> {
                val key = OtherChoice.valueOf(editor.name); val value = current.text
                val effects = if (key == OtherChoice.Language && state.value.settings!!.choices.getValue(key) != value) listOf(OtherEffect.RestartApplication) else emptyList()
                apply(OtherMutation(UUID.randomUUID().toString(), OtherMutationKind.Choice, key.name, text = value, effects = effects))
            }
        }
    }
    private fun textMutation(key: OtherText, value: String) {
        if (!usable() || current.mutation != null) return
        mutable.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try {
                val old = repository.readText(key); currentCoroutineContext().ensureActive()
                val normalized = when (key) { OtherText.Token -> normalizeJsSourceApiToken(value)
                    OtherText.UserAgent -> value.takeUnless { it.isBlank() }
                    OtherText.Hosts -> value.takeIf { runCatching { JsonParser.parseString(it).isJsonObject }.getOrDefault(false) }; else -> value }
                val effects = if (key == OtherText.Token && old.takeIf { it.isNotEmpty() } != normalized && state.value.settings!!.switches.getValue(OtherSwitch.TokenRequired))
                    listOf(if (normalized == null) OtherEffect.StopMcp else OtherEffect.RestartMcp) else emptyList()
                currentCoroutineContext().ensureActive()
                perform(OtherMutation(UUID.randomUUID().toString(), OtherMutationKind.Text, key.name, text = value, effects = effects))
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(error = error.localizedMessage.orEmpty(), interrupted = current.mutation != null, pendingCommit = pendingAccepted != null) }
            finally { if (!stopped && currentCoroutineContext().isActive) mutable.value = state.value.copy(busy = false) }
        }
    }
    private fun apply(value: OtherMutation) {
        mutable.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try { perform(value) }
            catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(error = error.localizedMessage.orEmpty(), interrupted = current.mutation != null, pendingCommit = pendingAccepted != null) }
            finally { if (!stopped && currentCoroutineContext().isActive) mutable.value = state.value.copy(busy = false) }
        }
    }
    private suspend fun perform(value: OtherMutation) {
        check(!stopped)
        val prepared = current.copy(revision = nextRevision(), mutation = value)
        current = prepared; mutable.value = state.value.copy(draft = prepared)
        persist(prepared); currentCoroutineContext().ensureActive()
        val actual = when (value.kind) {
            OtherMutationKind.Boolean -> repository.boolean(OtherSwitch.valueOf(value.key), value.boolean!!)
            OtherMutationKind.Number -> repository.number(OtherNumber.valueOf(value.key), value.number!!)
            OtherMutationKind.Text -> repository.text(OtherText.valueOf(value.key), value.text.orEmpty())
            OtherMutationKind.Choice -> repository.choice(OtherChoice.valueOf(value.key), value.text!!)
        }
        currentCoroutineContext().ensureActive()
        val completed = current.copy(revision = nextRevision(), editor = null, text = "", selectionStart = 0, selectionEnd = 0, mutation = null,
            effects = current.effects + (value.effects + actual).distinct().map { OtherEffectReceipt(UUID.randomUUID().toString(), it) })
        pendingAccepted = completed; mutable.value = state.value.copy(pendingCommit = true)
        persist(completed); currentCoroutineContext().ensureActive()
        if (!stopped) { current = completed; pendingAccepted = null; mutable.value = state.value.copy(draft = current, interrupted = false, pendingCommit = false, writeFailed = false) }
        val settings = repository.load(); currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(settings = settings)
    }
    fun retry() {
        if (stopped || state.value.busy) return
        when {
            state.value.failed -> initialize()
            pendingAccepted != null -> {
                mutable.value = state.value.copy(busy = true, error = null)
                operation = viewModelScope.launch {
                    try { val value = pendingAccepted!!; persist(value); currentCoroutineContext().ensureActive()
                        if (!stopped) { current = value; pendingAccepted = null; mutable.value = state.value.copy(draft = value, pendingCommit = false, interrupted = false, writeFailed = false) }
                        val settings = repository.load(); currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(settings = settings)
                    } catch (canceled: CancellationException) { throw canceled }
                    catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(error = error.localizedMessage.orEmpty()) }
                    finally { if (!stopped && currentCoroutineContext().isActive) mutable.value = state.value.copy(busy = false) }
                }
            }
            state.value.error != null && current.mutation == null && !state.value.writeFailed -> initialize()
            state.value.writeFailed -> {
                mutable.value = state.value.copy(busy = true, error = null)
                operation = viewModelScope.launch {
                    try { persist(current); currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(writeFailed = false) }
                    catch (canceled: CancellationException) { throw canceled }
                    catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(error = error.localizedMessage.orEmpty()) }
                    finally { if (!stopped && currentCoroutineContext().isActive) mutable.value = state.value.copy(busy = false) }
                }
            }
        }
    }
    /** UI confirmation is required for an interrupted prepared mutation; its saved effect plan survives changed preferences. */
    fun retryMutationConfirmed() { if (usable()) current.mutation?.let(::apply) }
    fun consumeEffect(id: String): Boolean {
        if (!usable() || current.effects.firstOrNull()?.id != id || current.mutation != null) return false
        saved["otherConsumedEffect"] = id; update(current.copy(effects = current.effects.drop(1))); return true
    }
    fun pickBookTree() {
        if (!usable() || current.editor != null || current.mutation != null || saved.get<String>("otherTreeTicket") != null) return
        val id = UUID.randomUUID().toString(); saved["otherTreeTicket"] = id; saved["otherTreeEvent"] = id; mutable.value = state.value.copy(bookTreeEvent = id)
    }
    fun consumeBookTreeEvent(id: String): Boolean {
        if (!usable() || state.value.bookTreeEvent != id) return false
        saved.remove<String>("otherTreeEvent"); mutable.value = state.value.copy(bookTreeEvent = null); return true
    }
    fun bookTreeTicket(): String? = saved["otherTreeTicket"]
    fun pickedBookTree(value: String?, nonce: String? = null) {
        val ticket = bookTreeTicket() ?: return
        if (stopped || nonce != null && nonce != ticket) return
        saved.remove<String>("otherTreeTicket"); saved.remove<String>("otherTreeEvent"); mutable.value = state.value.copy(bookTreeEvent = null)
        if (value == null) return
        earlyTree = value
        if (usable() && current.editor == null && current.mutation == null) { earlyTree = null; textMutation(OtherText.BookTree, value) }
    }
    suspend fun flush() { if (initialized && !stopped) persist(current) }
    fun stop() { if (!stopped) { stopped = true; generation++; observer?.cancel(); operation?.cancel(); writer.cancel() } }
    suspend fun release() { stop(); drafts.release(session) }
    override fun onCleared() { stop() }
}
