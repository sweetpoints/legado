package io.legado.app.ui.main.explore

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.ui.login.SourceLoginJsExtensions
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class ExploreHomeViewModel(
    private val repository: ExploreHomeRepository,
    private val storage: ExploreHomeSessionStorage,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    internal val sessionToken: String? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ExploreHomeState())
    val state = mutableState.asStateFlow()
    private val operationMutex = Mutex()
    private var session = ExploreHomeSession()
    private var restored = false
    private val restorationReady = MutableStateFlow(false)
    private val restorationCompleted = CompletableDeferred<Unit>()
    @Volatile private var terminated = false
    private var generation = 0L
    private var panelJob: Job? = null
    private var sourcesJob: Job? = null
    private var groupsJob: Job? = null
    private var resumed = false
    private var retainSessionOnClear = false
    private val activeCallbacks = mutableMapOf<String, SourceLoginJsExtensions.Callback>()

    init {
        viewModelScope.launch {
            try {
                operationMutex.withLock { restoreSession() }
            } finally {
                restorationCompleted.complete(Unit)
            }
        }
    }

    private suspend fun restoreSession() {
        restored = false
        restorationReady.value = false
        try {
            val loaded = withContext(ioDispatcher) { storage.read() }
            session = loaded.copy(pendingOperation = false)
            mutableState.update {
                it.copy(
                    query = loaded.query,
                    expandedUrl = loaded.expandedUrl,
                    deleteUrl = loaded.deleteUrl,
                    errorText = loaded.errorText,
                    effect = loaded.effect?.takeUnless { request -> request.id in loaded.receipts },
                    sessionLoaded = true,
                    loading = true,
                    error = if (loaded.pendingOperation) "上次操作被中断，请检查结果后重试" else null,
                )
            }
            restored = true
            restorationReady.value = true
        } catch (failure: Exception) {
            // Preserve the unreadable body. A default in-memory session cannot authorize any
            // edit, mutation or native receipt until Retry successfully reads the owned file.
            mutableState.update { it.copy(sessionLoaded = false) }
            fail(failure)
        }
    }

    /** The view's RESUMED collector owns Room observation and control loading. */
    suspend fun observeResumed() {
        restorationReady.filter { it }.first()
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

    /** Flushes private session state before the old Fragment owner is retired. */
    suspend fun prepareForHostMigration() {
        restorationCompleted.await()
        check(restored) { state.value.error ?: "发现会话恢复尚未完成" }
        resumed = false
        groupsJob?.cancel()
        sourcesJob?.cancel()
        panelJob?.cancel()
        generation++
        operationMutex.withLock {
            repository.savePendingValues()
            if (restored) persist()
        }
        retainSessionOnClear = true
    }

    suspend fun awaitHostRestore() {
        restorationCompleted.await()
        check(state.value.sessionLoaded) { state.value.error ?: "发现会话恢复尚未完成" }
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

    private suspend fun persist(
        base: ExploreHomeSession = session,
        effect: ExploreHomeEffect? = state.value.effect,
    ) {
        val current = state.value
        val snapshot =
            base.copy(
                revision = session.revision + 1,
                query = current.query,
                expandedUrl = current.expandedUrl,
                deleteUrl = current.deleteUrl,
                errorText = current.errorText,
                effect = effect,
            )
        // Only a committed revision becomes the accepted session. A superseded receipt must
        // never clear a pending effect or authorize native/business work.
        val accepted =
            try {
                withContext(ioDispatcher + NonCancellable) { storage.write(snapshot) }
            } catch (failure: Exception) {
                invalidateRestoration()
                throw failure
            }
        if (!accepted) invalidateRestoration()
        check(accepted) { "发现会话写入已由新的页面取代，请重新加载" }
        // Values may change on the main thread while the disk write is suspended.
        // Accept its durable revision without replacing those newer in-memory edits.
        session = snapshot.copy(values = session.values)
    }

    private fun invalidateRestoration() {
        restored = false
        restorationReady.value = false
        mutableState.update { it.copy(sessionLoaded = false) }
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
        if (!restored || terminated) return
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
        val controls = state.value.controls.map { row ->
            if (row.id == controlId) row.copy(value = value) else row
        }
        val values =
            controls
                .filter { it.type in setOf("text", "toggle", "select") }
                .associate { it.title to it.value }
                .toMap()
        session = session.copy(values = session.values + (url to values))
        // Publish the values before edit launches an immediate session checkpoint.
        edit { it.copy(controls = controls) }
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
        if (terminated || state.value.busy) return
        viewModelScope.launch {
            if (!restored) {
                operationMutex.withLock { restoreSession() }
            } else {
                mutableState.update { it.copy(error = null) }
                if (resumed) observeResumed()
            }
        }
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
                        persist(session.copy(pendingOperation = true))
                        action()
                        persist(session.copy(pendingOperation = false))
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
        if (
            !restored ||
                terminated ||
                state.value.effect?.id != effect.id ||
                effect.id in session.receipts
        )
            return@withLock false
        withContext(NonCancellable) {
            val previous = session
            var receiptAccepted = false
            var launched = false
            mutableState.update { it.copy(busy = true) }
            try {
                persist(session.copy(receipts = session.receipts + effect.id), effect = null)
                receiptAccepted = true
                mutableState.update { it.copy(effect = null) }
                if (!ready() || terminated) {
                    persist(previous, effect = effect)
                    mutableState.update { it.copy(effect = effect) }
                    false
                } else {
                    launched = true
                    launch()
                    true
                }
            } catch (failure: Exception) {
                if (receiptAccepted && !launched) {
                    persist(previous, effect = effect)
                    mutableState.update { it.copy(effect = effect) }
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
        if (!retainSessionOnClear) {
            val cleanup = CoroutineScope(SupervisorJob() + ioDispatcher)
            cleanup.launch {
                try {
                    operationMutex.withLock { storage.delete() }
                } finally {
                    cleanup.cancel()
                }
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
