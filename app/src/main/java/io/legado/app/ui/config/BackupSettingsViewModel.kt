package io.legado.app.ui.config

import androidx.lifecycle.*
import io.legado.app.data.preferences.*
import io.legado.app.model.backup.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal data class BackupSettingsState(val loading: Boolean = true, val failed: Boolean = false, val busy: Boolean = false,
    val settings: BackupSettingsSnapshot? = null, val draft: BackupSettingsDraft? = null,
    val choices: List<BackupChoice> = emptyList(), val pendingCommit: Boolean = false, val invalidInterval: Boolean = false, val error: String? = null, val draftFailed: Boolean = false)
/** Settings editor foundation: accepted mutations finish; large text and credentials are private files. */
internal class BackupSettingsViewModel(private val repository: BackupSettingsRepository, private val choices: BackupChoicesRepository,
    private val drafts: BackupSettingsDraftRepository, private val saved: SavedStateHandle) : ViewModel() {
    val session = saved.get<String>("backupSession") ?: UUID.randomUUID().toString().also { saved["backupSession"] = it }
    private val mutable = MutableStateFlow(BackupSettingsState()); val state = mutable.asStateFlow()
    private var stopped = false; private var generation = 0; private var revision = 0L; private var initialized = false
    private var current = BackupSettingsDraft(); private var observer: Job? = null; private var operation: Job? = null
    private val updates = MutableStateFlow<BackupSettingsDraft?>(null); private val writeGate = Mutex()
    private var pendingClear: BackupSettingsDraft? = null; private var retryAction: (suspend () -> Unit)? = null
    private val writer = viewModelScope.launch {
        updates.filterNotNull().collect { value ->
            try { persist(value); currentCoroutineContext().ensureActive()
                if (!stopped && current.revision == value.revision) mutable.value = state.value.copy(draftFailed = false)
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped && current.revision == value.revision) mutable.value = state.value.copy(error = error.localizedMessage.orEmpty(), draftFailed = true) }
        }
    }
    init { initialize() }
    private fun nextRevision() = maxOf(System.nanoTime(), revision + 1).also { revision = it }
    private fun usable() = !stopped && !state.value.loading && !state.value.failed && !state.value.busy && state.value.settings != null
    private suspend fun persist(value: BackupSettingsDraft) = writeGate.withLock { drafts.write(session, value) }
    private fun update(value: BackupSettingsDraft) {
        current = value.copy(revision = nextRevision()); mutable.value = state.value.copy(draft = current); updates.value = current
    }
    private fun initialize() {
        observer?.cancel(); val token = ++generation; mutable.value = state.value.copy(loading = true, failed = false, error = null)
        observer = viewModelScope.launch {
            try {
                if (!initialized) { current = drafts.open(session); currentCoroutineContext().ensureActive(); revision = maxOf(revision, current.revision); initialized = true }
                repository.observe().collect { settings ->
                    currentCoroutineContext().ensureActive()
                    if (!stopped && token == generation) {
                        val projection = current
                        val rows = projection.form?.choiceGroup()?.let { group -> choices.load(group).map { it.copy(checked = projection.choices[it.key] ?: it.checked) } }.orEmpty()
                        currentCoroutineContext().ensureActive()
                        if (!stopped && token == generation) mutable.value = state.value.copy(loading = false, failed = false, settings = settings, draft = current, choices = if (current.revision == projection.revision) rows else state.value.choices)
                    }
                }
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped && token == generation) mutable.value = state.value.copy(loading = false, failed = true, error = error.localizedMessage.orEmpty()) }
        }
    }
    fun boolean(key: BackupSettingSwitch, value: Boolean) {
        if (state.value.settings?.enabled(key) != true) return
        run { persist(current); currentCoroutineContext().ensureActive(); repository.boolean(key, value) }
    }
    fun textForm(key: BackupSettingText) {
        if (!usable() || pendingClear != null) return
        update(current.copy(form = BackupForm.valueOf(key.name), text = state.value.settings!!.texts.getValue(key), choices = emptyMap()))
    }
    fun localPasswordForm() { if (usable() && pendingClear == null) update(current.copy(form = BackupForm.LocalPassword, text = "", choices = emptyMap())) }
    fun automaticForm() {
        if (!usable() || pendingClear != null) return
        val value = state.value.settings!!.automatic
        update(current.copy(form = BackupForm.Automatic, autoEnabled = value.enabled, autoWebDav = value.webDav, intervalText = value.intervalDays.toString(), choices = emptyMap()))
    }
    fun text(value: String) { if (usable() && current.form != null && pendingClear == null) update(current.copy(text = value)) }
    fun automaticEnabled(value: Boolean) { if (usable() && current.form == BackupForm.Automatic && pendingClear == null) update(current.copy(autoEnabled = value)) }
    fun automaticWebDav(value: Boolean) { if (usable() && current.form == BackupForm.Automatic && pendingClear == null) update(current.copy(autoWebDav = value)) }
    fun interval(value: String) { if (usable() && current.form == BackupForm.Automatic && pendingClear == null && (value.isEmpty() || value.all(Char::isDigit))) { update(current.copy(intervalText = value)); mutable.value = state.value.copy(invalidInterval = false) } }
    fun choicesForm(group: BackupChoiceGroup) = run {
        val rows = choices.load(group); currentCoroutineContext().ensureActive()
        if (!stopped) { update(current.copy(form = if (group == BackupChoiceGroup.Content) BackupForm.Content else BackupForm.Ignore,
            choices = rows.associate { it.key to it.checked }, text = "")); mutable.value = state.value.copy(choices = rows); persist(current) }
    }
    fun choice(key: String, checked: Boolean) {
        val group = current.form?.choiceGroup() ?: return
        if (state.value.choices.none { it.key == key }) return
        run {
            update(current.copy(choices = current.choices + (key to checked))); persist(current); currentCoroutineContext().ensureActive()
            val rows = choices.toggle(group, key, checked); currentCoroutineContext().ensureActive()
            if (!stopped) mutable.value = state.value.copy(choices = rows)
        }
    }
    fun path(value: String?) = run { persist(current); currentCoroutineContext().ensureActive(); repository.path(value) }
    fun confirm() {
        if (!usable() || current.form == null || pendingClear != null) return
        val form = current.form!!; val snapshot = current
        val days = snapshot.intervalText.toIntOrNull()
        if (form == BackupForm.Automatic && (days == null || days < 1)) { mutable.value = state.value.copy(invalidInterval = true); return }
        run {
            persist(snapshot); currentCoroutineContext().ensureActive()
            when (form) {
                BackupForm.Automatic -> repository.automatic(AutoBackupSettings(snapshot.autoEnabled, snapshot.autoWebDav, days!!))
                BackupForm.LocalPassword -> repository.localPassword(snapshot.text)
                BackupForm.Content, BackupForm.Ignore -> saveChoices(form.choiceGroup()!!, snapshot.choices)
                else -> repository.text(BackupSettingText.valueOf(form.name), snapshot.text)
            }
            currentCoroutineContext().ensureActive(); clearAcceptedForm()
        }
    }
    /** Closing content/ignore saves live selections; closing text/automatic forms discards their unaccepted draft. */
    fun dismiss() {
        if (!usable() || current.form == null || pendingClear != null) return
        val snapshot = current
        run {
            snapshot.form?.choiceGroup()?.let { group -> persist(snapshot); currentCoroutineContext().ensureActive(); saveChoices(group, snapshot.choices) }
            clearAcceptedForm()
        }
    }
    private suspend fun saveChoices(group: BackupChoiceGroup, values: Map<String, Boolean>) {
        values.forEach { (key, value) -> choices.toggle(group, key, value); currentCoroutineContext().ensureActive() }
        choices.save(); currentCoroutineContext().ensureActive()
    }
    private suspend fun clearAcceptedForm() {
        val cleared = current.copy(revision = nextRevision(), form = null, text = "", intervalText = "1", choices = emptyMap())
        pendingClear = cleared; mutable.value = state.value.copy(pendingCommit = true)
        persist(cleared); currentCoroutineContext().ensureActive()
        if (!stopped) { current = cleared; pendingClear = null; mutable.value = state.value.copy(draft = cleared, choices = emptyList(), draftFailed = false, pendingCommit = false) }
    }
    fun retry() {
        if (stopped || state.value.busy) return
        when {
            state.value.failed -> initialize()
            pendingClear != null && usable() -> run(recovering = true) { val value = pendingClear!!; persist(value); currentCoroutineContext().ensureActive()
                if (!stopped) { current = value; pendingClear = null; mutable.value = state.value.copy(draft = value, choices = emptyList(), draftFailed = false, pendingCommit = false) } }
            state.value.draftFailed && usable() -> run { persist(current); currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(draftFailed = false) }
            else -> retryAction?.let { run(action = it) }
        }
    }
    private fun run(recovering: Boolean = false, action: suspend () -> Unit) {
        if (!usable() || pendingClear != null && !recovering) return
        mutable.value = state.value.copy(busy = true, error = null, invalidInterval = false)
        operation = viewModelScope.launch {
            try { action(); currentCoroutineContext().ensureActive(); val settings = repository.load(); currentCoroutineContext().ensureActive()
                if (!stopped) { retryAction = null; mutable.value = state.value.copy(settings = settings) } }
            catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) { retryAction = action; mutable.value = state.value.copy(error = error.localizedMessage.orEmpty()) } }
            finally { if (!stopped && currentCoroutineContext().isActive) mutable.value = state.value.copy(busy = false) }
        }
    }
    suspend fun flush() { if (!stopped && initialized) persist(current) }
    fun stop() { if (!stopped) { stopped = true; generation++; observer?.cancel(); operation?.cancel(); writer.cancel() } }
    suspend fun release() { stop(); drafts.release(session) }
    override fun onCleared() { stop() }
    private fun BackupForm.choiceGroup(): BackupChoiceGroup? = when (this) { BackupForm.Content -> BackupChoiceGroup.Content; BackupForm.Ignore -> BackupChoiceGroup.Ignore; else -> null }
}
