package io.legado.app.ui.main.explore

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.ui.login.SourceLoginJsExtensions
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

internal class ExploreHomeViewModel(
    private val repository: ExploreHomeRepository,
    private val storage: ExploreHomeSessionStorage,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ExploreHomeState())
    val state = mutableState.asStateFlow()
    private val operationMutex = Mutex()
    private var session = ExploreHomeSession()
    private var restored = false
    @Volatile private var terminated = false
    private var generation = 0L
    private var panelJob: Job? = null
    private var sourcesJob: Job? = null
    private var groupsJob: Job? = null
    private var resumed = false
    private val activeCallbacks = mutableMapOf<String, SourceLoginJsExtensions.Callback>()

    init {
        viewModelScope.launch {
            operationMutex.withLock {
                try {
                    session = withContext(ioDispatcher) { storage.read() }
                    mutableState.update {
                        it.copy(
                            query = session.query,
                            expandedUrl = session.expandedUrl,
                            deleteUrl = session.deleteUrl,
                            errorText = session.errorText,
                            effect =
                                session.effect?.takeUnless { request ->
                                    request.id in session.receipts
                                },
                            error = if (session.pendingOperation) "上次操作被中断，请检查结果后重试" else null,
                        )
                    }
                    session = session.copy(pendingOperation = false)
                } catch (failure: Exception) {
                    fail(failure)
                }
                restored = true
            }
        }
    }

    /** The view's RESUMED collector owns Room observation and control loading. */
    suspend fun observeResumed() {
        while (!restored) yield()
        currentCoroutineContext().ensureActive()
        resumed = true
        groupsJob?.cancel()
        groupsJob = viewModelScope.launch {
            repository
                .groups()
                .catch { fail(it) }
                .collect { groups ->
                    mutableState.update { it.copy(groups = groups.toList()) }
                    val group = exploreGroupFromQuery(state.value.query)
                    if (group != null && group !in groups) query("")
                }
        }
        refreshPreferences()
        watchSources()
        state.value.expandedUrl?.let { loadPanel(it, false) }
    }

    private suspend fun refreshPreferences() {
        try {
            val showFastScroller = repository.showFastScroller()
            val eInkMode = repository.eInkMode()
            mutableState.update {
                it.copy(showFastScroller = showFastScroller, eInkMode = eInkMode)
            }
        } catch (failure: Exception) {
            fail(failure)
        }
    }

    private fun watchSources() {
        sourcesJob?.cancel()
        sourcesJob = viewModelScope.launch {
            repository
                .sources(state.value.query)
                .catch { fail(it) }
                .collect { sources ->
                    val expanded = state.value.expandedUrl
                    val previousRevision = state.value.sources.find { it.url == expanded }?.revision
                    mutableState.update { it.copy(sources = sources.toList(), loading = false) }
                    if (expanded != null && sources.none { it.url == expanded }) {
                        panelJob?.cancel()
                        generation++
                        edit {
                            it.copy(
                                expandedUrl = null,
                                controls = emptyList(),
                                panelLoading = false,
                            )
                        }
                    } else if (
                        expanded != null &&
                            previousRevision != null &&
                            sources.find { it.url == expanded }?.revision != previousRevision
                    ) {
                        loadPanel(expanded, false)
                    }
                }
        }
    }

    fun pause() {
        resumed = false
        groupsJob?.cancel()
        sourcesJob?.cancel()
        panelJob?.cancel()
        generation++
        viewModelScope.launch { runCatching { repository.savePendingValues() }.onFailure(::fail) }
    }

    fun hostFailure(failure: Throwable) = fail(failure)

    private fun fail(failure: Throwable) {
        if (failure is CancellationException) throw failure
        mutableState.update {
            it.copy(
                error = failure.localizedMessage ?: "操作失败",
                panelLoading = false,
                loading = false,
            )
        }
    }

    private suspend fun persist() {
        val current = state.value
        session =
            session.copy(
                query = current.query,
                expandedUrl = current.expandedUrl,
                deleteUrl = current.deleteUrl,
                errorText = current.errorText,
                effect = current.effect,
            )
        val snapshot = session
        withContext(ioDispatcher + NonCancellable) { storage.write(snapshot) }
    }

    private fun edit(transform: (ExploreHomeState) -> ExploreHomeState) {
        if (!restored || terminated || state.value.busy) return
        mutableState.update(transform)
        viewModelScope.launch {
            operationMutex.withLock {
                try {
                    persist()
                } catch (failure: Exception) {
                    fail(failure)
                }
            }
        }
    }

    fun query(value: String) {
        if (state.value.busy) return
        edit { it.copy(query = value, loading = true) }
        if (restored && resumed) watchSources()
    }

    fun expand(url: String) {
        if (state.value.busy) return
        if (state.value.expandedUrl == url) {
            panelJob?.cancel()
            generation++
            edit { it.copy(expandedUrl = null, controls = emptyList(), panelLoading = false) }
        } else {
            val index = state.value.sources.indexOfFirst { it.url == url }.coerceAtLeast(0)
            edit {
                it.copy(
                    expandedUrl = url,
                    controls = emptyList(),
                    scrollRequest = it.scrollRequest + 1,
                    scrollTarget = index,
                )
            }
            loadPanel(url, false)
        }
    }

    fun compressExplore() {
        if (state.value.expandedUrl != null) expand(state.value.expandedUrl!!)
        else edit { it.copy(scrollRequest = it.scrollRequest + 1, scrollTarget = 0) }
    }

    fun refresh(url: String = state.value.expandedUrl.orEmpty()) {
        if (url == state.value.expandedUrl) loadPanel(url, true)
        else
            viewModelScope.launch {
                try {
                    repository.panel(url, true)
                } catch (failure: Exception) {
                    fail(failure)
                }
            }
    }

    private fun loadPanel(url: String, refresh: Boolean) {
        panelJob?.cancel()
        val ownerGeneration = ++generation
        mutableState.update { it.copy(panelLoading = true) }
        panelJob = viewModelScope.launch {
            try {
                val saved = session.values[url].orEmpty().toMap()
                if (saved.isNotEmpty()) repository.saveValues(url, saved)
                val panel = repository.panel(url, refresh)
                if (!acceptsExplorePanel(ownerGeneration, generation, url, state.value.expandedUrl))
                    return@launch
                mutableState.update {
                    it.copy(controls = panel.controls.toList(), panelLoading = false)
                }
                session =
                    session.copy(
                        values =
                            session.values +
                                (url to
                                    panel.controls
                                        .filter { it.type in setOf("text", "toggle", "select") }
                                        .associate { it.title to it.value })
                    )
            } catch (failure: Exception) {
                if (acceptsExplorePanel(ownerGeneration, generation, url, state.value.expandedUrl))
                    fail(failure)
                else if (failure is CancellationException) throw failure
            }
        }
    }

    fun value(controlId: Int, value: String) {
        if (state.value.busy || !restored) return
        val url = state.value.expandedUrl ?: return
        if (state.value.controls.none { it.id == controlId }) return
        edit {
            it.copy(
                controls =
                    it.controls.map { row ->
                        if (row.id == controlId) row.copy(value = value) else row
                    }
            )
        }
        val values =
            state.value.controls
                .filter { it.type in setOf("text", "toggle", "select") }
                .associate { it.title to it.value }
                .toMap()
        session = session.copy(values = session.values + (url to values))
        viewModelScope.launch {
            operationMutex.withLock {
                try {
                    withContext(NonCancellable) { repository.saveValues(url, values) }
                } catch (failure: Exception) {
                    fail(failure)
                }
            }
        }
    }

    fun control(controlId: Int) {
        val control = state.value.controls.find { it.id == controlId } ?: return
        val url = state.value.expandedUrl ?: return
        when (control.type) {
            "url" -> {
                if (control.title.startsWith("ERROR:")) edit { it.copy(errorText = control.url) }
                else if (!control.url.isNullOrBlank())
                    effect("open", url, control.title, control.url)
            }
            "toggle" -> {
                val nextIndex = (control.choices.indexOf(control.value) + 1) % control.choices.size
                value(controlId, control.choices[nextIndex])
                control.action
                    ?.takeIf { it.isNotBlank() }
                    ?.let { action -> effect("script", url, title = control.title, value = action) }
            }
            "button",
            "text",
            "select" ->
                control.action
                    ?.takeIf { it.isNotBlank() }
                    ?.let { action -> effect("script", url, title = control.title, value = action) }
        }
    }

    fun effect(action: String, url: String = "", title: String = "", value: String = "") = edit {
        if (it.effect != null) it
        else
            it.copy(
                effect =
                    ExploreHomeEffect(
                        UUID.randomUUID().toString(),
                        action,
                        url,
                        title,
                        value,
                        session.values[url].orEmpty().toMap(),
                    )
            )
    }

    fun requestDelete(url: String) = edit { it.copy(deleteUrl = url) }

    fun dismiss() = edit { it.copy(deleteUrl = null, errorText = null, error = null) }

    fun retry() {
        dismiss()
        state.value.expandedUrl?.let { loadPanel(it, false) }
    }

    fun delete() {
        state.value.deleteUrl?.let { url ->
            operation { repository.delete(url) }
            mutableState.update { it.copy(deleteUrl = null) }
        }
    }

    fun top(url: String) = operation { repository.top(url) }

    private fun operation(action: suspend () -> Unit) {
        if (terminated || state.value.busy || !restored) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            operationMutex.withLock {
                try {
                    withContext(NonCancellable) {
                        session = session.copy(pendingOperation = true)
                        persist()
                        action()
                        session = session.copy(pendingOperation = false)
                        persist()
                    }
                } catch (failure: Exception) {
                    fail(failure)
                } finally {
                    mutableState.update { it.copy(busy = false) }
                }
            }
        }
    }

    suspend fun prepareSearch(effect: ExploreHomeEffect) =
        if (effect.action == "search") repository.searchSource(effect.sourceUrl) else null

    suspend fun deliver(
        effect: ExploreHomeEffect,
        ready: () -> Boolean,
        launch: () -> Unit,
    ): Boolean = operationMutex.withLock {
        if (terminated || state.value.effect?.id != effect.id || effect.id in session.receipts)
            return@withLock false
        withContext(NonCancellable) {
            val previous = session
            var launched = false
            try {
                session = session.copy(receipts = session.receipts + effect.id)
                mutableState.update { it.copy(effect = null, busy = true) }
                persist()
                if (!ready() || terminated) {
                    session = previous
                    mutableState.update { it.copy(effect = effect) }
                    persist()
                    false
                } else {
                    launched = true
                    launch()
                    true
                }
            } catch (failure: Exception) {
                if (!launched) {
                    session = previous
                    mutableState.update { it.copy(effect = effect) }
                    persist()
                }
                throw failure
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun execute(effect: ExploreHomeEffect, activity: AppCompatActivity?) {
        val values = effect.values.toMap()
        val callback =
            object : SourceLoginJsExtensions.Callback {
                override fun upUiData(data: Map<String, Any?>?) = Unit

                override fun reUiView(deltaUp: Boolean) {
                    viewModelScope.launch { if (!terminated && resumed) refresh(effect.sourceUrl) }
                }
            }
        // The native bridge holds a weak callback. Keep an explicit owner until the script's
        // final completion, including a script-triggered GC before refreshExplore().
        activeCallbacks[effect.id] = callback
        viewModelScope.launch {
            try {
                repository.execute(
                    effect.sourceUrl,
                    effect.value,
                    values,
                    activity,
                    callback,
                )
            } catch (failure: Exception) {
                fail(failure)
            } finally {
                activeCallbacks.remove(effect.id)
            }
        }
    }

    override fun onCleared() {
        terminated = true
        val cleanup = CoroutineScope(SupervisorJob() + ioDispatcher)
        cleanup.launch {
            try {
                operationMutex.withLock { storage.delete() }
            } finally {
                cleanup.cancel()
            }
        }
        super.onCleared()
    }

    companion object {
        fun token(savedState: SavedStateHandle): String =
            savedState.get<String>("exploreHome.session")
                ?: UUID.randomUUID().toString().also { savedState["exploreHome.session"] = it }
    }
}
