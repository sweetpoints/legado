package io.legado.app.ui.login

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.data.repository.*
import io.legado.app.model.login.LoginUiV2
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SourceLoginFormAction {
    Legacy,
    Login,
    OpenUrl,
    Copy,
    Log,
    Toast,
}

data class SourceLoginFormEffect(
    val id: Long,
    val action: SourceLoginFormAction,
    val text: String = "",
    val long: Boolean = false,
    val values: Map<String, String> = emptyMap(),
    val resource: Int = 0,
)

data class SourceLoginFormUiState(
    val title: String,
    val v2: Boolean,
    val rows: List<SourceLoginRow> = emptyList(),
    val values: Map<String, String> = emptyMap(),
    val rendered: Boolean = false,
    val loading: Boolean = false,
    val rendering: Boolean = false,
    val busy: Boolean = false,
    val errors: Map<String, String> = emptyMap(),
    val error: String? = null,
    val clear: Boolean = false,
    val header: String? = null,
    val countdowns: Map<String, Int> = emptyMap(),
    val finished: Boolean = false,
    val pending: List<SourceLoginFormEffect> = emptyList(),
)

class SourceLoginFormViewModel(
    private val repository: SourceLoginFormRepository,
    private val saved: SavedStateHandle,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel(), SourceLoginJsExtensions.Callback {
    private var renderJob: Job? = null
    private var initializationJob: Job? = null
    private var countdownJob: Job? = null
    private var repositoryReady = false
    private val hasSavedValues = saved.get<String>("loginForm.values") != null
    private var actionJob: Job? = null
    private val debounceJobs = mutableMapOf<String, Job>()
    private var generation = 0L
    private var headerGeneration = 0L
    private var nextId = saved.get<Long>("loginForm.next") ?: 0L
    private var stateJson = saved.get<String>("loginForm.state") ?: "{}"
    private var submitted = saved.get<Boolean>("loginForm.submitted") ?: false
    private var changed = saved.get<Boolean>("loginForm.changed") ?: false
    private var lastClick = Long.MIN_VALUE
    private val deadlines =
        saved
            .get<String>("loginForm.deadlines")
            ?.let { GSON.fromJsonObject<Map<String, Long>>(it).getOrNull() }
            .orEmpty()
            .toMutableMap()
    private val mutable =
        MutableStateFlow(
            SourceLoginFormUiState(
                saved["loginForm.title"] ?: repository.definition.title,
                saved["loginForm.v2"] ?: repository.definition.v2,
                loading = true,
                rows =
                    saved
                        .get<String>("loginForm.rows")
                        ?.let { GSON.fromJsonArray<SourceLoginRow>(it).getOrNull() }
                        .orEmpty(),
                values =
                    saved.get<String>("loginForm.values")?.let {
                        GSON.fromJsonObject<Map<String, String>>(it).getOrNull()
                    } ?: repository.definition.values,
                rendered = saved["loginForm.rendered"] ?: false,
                header = saved["loginForm.header"],
                clear = saved["loginForm.clear"] ?: false,
                finished = saved["loginForm.finished"] ?: false,
                errors =
                    saved
                        .get<String>("loginForm.errors")
                        ?.let { GSON.fromJsonObject<Map<String, String>>(it).getOrNull() }
                        .orEmpty(),
                pending =
                    saved
                        .get<String>("loginForm.pending")
                        ?.let { GSON.fromJsonArray<SourceLoginFormEffect>(it).getOrNull() }
                        .orEmpty(),
            )
        )
    val state = mutable.asStateFlow()

    init {
        if (!state.value.finished) initialize()
        tickCountdowns()
    }

    fun initialize(retry: Boolean = false) {
        if (state.value.finished) return
        initializationJob?.cancel()
        if (retry) repository.retry()
        mutable.update { it.copy(loading = true, error = null) }
        initializationJob = execute {
            try {
                val definition = repository.ready()
                if (state.value.finished) return@execute
                repositoryReady = true
                mutable.update {
                    it.copy(
                        title = definition.title,
                        v2 = definition.v2,
                        loading = false,
                        values =
                            if (!hasSavedValues && !changed && !it.rendered) definition.values
                            else it.values,
                    )
                }
                if (!state.value.rendered) render() else persist()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!state.value.finished) {
                    mutable.update {
                        it.copy(loading = false, error = error.localizedMessage ?: error.toString())
                    }
                    persist()
                }
            }
        }
    }

    fun retry() {
        if (repositoryReady) render() else initialize(retry = true)
    }

    private fun persist() {
        val value = state.value
        saved["loginForm.title"] = value.title
        saved["loginForm.v2"] = value.v2
        saved["loginForm.rows"] = GSON.toJson(value.rows)
        saved["loginForm.values"] = GSON.toJson(value.values)
        saved["loginForm.rendered"] = value.rendered
        saved["loginForm.header"] = value.header
        saved["loginForm.clear"] = value.clear
        saved["loginForm.finished"] = value.finished
        saved["loginForm.errors"] = GSON.toJson(value.errors)
        saved["loginForm.pending"] = GSON.toJson(value.pending)
        saved["loginForm.state"] = stateJson
        saved["loginForm.next"] = nextId
        saved["loginForm.changed"] = changed
        saved["loginForm.submitted"] = submitted
        saved["loginForm.deadlines"] = GSON.toJson(deadlines)
    }

    private fun execute(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(error)
        }
    }

    fun fail(error: Exception) {
        if (state.value.finished) return
        mutable.update { it.copy(error = error.localizedMessage ?: error.toString(), busy = false) }
    }

    private fun effect(
        action: SourceLoginFormAction,
        text: String = "",
        long: Boolean = false,
        values: Map<String, String> = emptyMap(),
        resource: Int = 0,
    ) {
        if (state.value.finished) return
        val event = SourceLoginFormEffect(++nextId, action, text, long, values.toMap(), resource)
        mutable.update { it.copy(pending = it.pending + event) }
        persist()
    }

    fun consume(id: Long) {
        mutable.update { it.copy(pending = it.pending.filterNot { e -> e.id == id }) }
        persist()
    }

    fun render(
        candidate: String = stateJson,
        errors: Map<String, String> = emptyMap(),
        delta: Boolean = false,
    ) {
        if (state.value.finished) return
        renderJob?.cancel()
        val revision = ++generation
        val snapshot = state.value.values.toMap()
        mutable.update {
            it.copy(loading = !it.rendered, rendering = true, busy = if (it.v2) true else it.busy)
        }
        renderJob = viewModelScope.launch {
            try {
                val rendered = repository.render(snapshot, candidate)
                if (revision != generation || state.value.finished) return@launch
                val previous = state.value.rows
                val nextRows =
                    if (delta && !state.value.v2)
                        rendered.rows.map { next ->
                            previous
                                .find { it.name == next.name }
                                ?.let { old ->
                                    next.copy(
                                        type = old.type,
                                        label = old.label,
                                        baseLabel = old.baseLabel,
                                        labelScript = null,
                                        action = old.action,
                                        options = old.options,
                                        style = old.style,
                                    )
                                } ?: next
                        }
                    else rendered.rows
                val values = state.value.values.toMutableMap()
                nextRows.forEach { row ->
                    when {
                        state.value.v2 &&
                            row.type in
                                listOf(
                                    RowUi.Type.text,
                                    RowUi.Type.password,
                                    RowUi.Type.select,
                                    RowUi.Type.toggle,
                                ) -> {
                            val value =
                                LoginUiV2.resolveFieldValue(
                                        row.value,
                                        values[row.key],
                                        rendered.stored[row.key],
                                    )
                                    .orEmpty()
                            values[row.key] =
                                if (row.type == RowUi.Type.select)
                                    value.takeIf { it in row.options } ?: row.options.first()
                                else if (row.type == RowUi.Type.toggle) (value == "true").toString()
                                else value
                        }
                        !state.value.v2 &&
                            row.type in listOf(RowUi.Type.text, RowUi.Type.password) ->
                            values.putIfAbsent(row.key, row.default.orEmpty())
                        !state.value.v2 &&
                            row.type in listOf(RowUi.Type.select, RowUi.Type.toggle) -> {
                            if (values[row.key].isNullOrEmpty()) {
                                changed = true
                                values[row.key] = row.default ?: row.options.firstOrNull().orEmpty()
                            }
                        }
                    }
                }
                stateJson = candidate
                // Names identify the legacy delta update, while the latest schema supplies
                // presentation.
                mutable.update {
                    it.copy(
                        rows = nextRows.toList(),
                        values = values.toMap(),
                        rendered = true,
                        loading = false,
                        rendering = false,
                        busy = false,
                        errors = errors.toMap(),
                        error = null,
                    )
                }
                persist()
                if (!state.value.v2)
                    nextRows.forEachIndexed { index, row ->
                        row.labelScript?.let { script ->
                            execute {
                                val label =
                                    try {
                                        repository.label(script, state.value.values).takeUnless {
                                            it.isNullOrEmpty()
                                        } ?: "null"
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (_: Exception) {
                                        "err"
                                    }
                                if (
                                    revision == generation &&
                                        state.value.rows.getOrNull(index)?.name == row.name &&
                                        !state.value.finished
                                ) {
                                    mutable.update {
                                        it.copy(
                                            rows =
                                                it.rows.mapIndexed { i, current ->
                                                    if (i == index)
                                                        current.copy(
                                                            label = label,
                                                            baseLabel = label,
                                                        )
                                                    else current
                                                }
                                        )
                                    }
                                    persist()
                                }
                            }
                        }
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (revision == generation) {
                    mutable.update {
                        it.copy(
                            loading = false,
                            rendering = false,
                            busy = false,
                            error = error.localizedMessage ?: error.toString(),
                            errors = errors,
                        )
                    }
                    persist()
                }
            }
        }
    }

    fun edit(key: String, value: String) {
        if (state.value.finished) return
        val row = state.value.rows.find { it.key == key } ?: return
        changed = true
        mutable.update { it.copy(values = it.values + (key to value)) }
        persist()
        if (
            !state.value.v2 &&
                row.action != null &&
                row.type in listOf(RowUi.Type.text, RowUi.Type.password)
        ) {
            debounce(row)
        }
    }

    private fun debounce(row: SourceLoginRow) {
        debounceJobs.remove(row.key)?.cancel()
        debounceJobs[row.key] = execute {
            delay(600)
            action(row, false, throttle = false)
        }
    }

    fun choose(row: SourceLoginRow, value: String) {
        edit(row.key, value)
        if (!state.value.v2) row.action?.let { action(row, false, false) }
    }

    fun toggle(row: SourceLoginRow, long: Boolean) {
        if (!allowed(row) || !state.value.v2 && !acceptLegacyClick()) return
        val value =
            if (state.value.v2) (state.value.values[row.key] != "true").toString()
            else
                row.options
                    .getOrNull(
                        (row.options.indexOf(state.value.values[row.key]) + 1) %
                            row.options.size.coerceAtLeast(1)
                    )
                    .orEmpty()
        edit(row.key, value)
        action(row, long, throttle = false)
    }

    private fun acceptLegacyClick(): Boolean {
        val time = now()
        if (lastClick != Long.MIN_VALUE && time - lastClick < 200) return false
        lastClick = time
        return true
    }

    private fun allowed(row: SourceLoginRow) =
        repositoryReady &&
            !state.value.finished &&
            !state.value.busy &&
            (row.action == null || state.value.countdowns.getOrDefault(row.action, 0) == 0)

    fun action(row: SourceLoginRow, long: Boolean, throttle: Boolean = true) {
        if (!allowed(row)) return
        val action = row.action ?: return
        if (!state.value.v2 && throttle && !acceptLegacyClick()) return
        if (!state.value.v2) {
            effect(
                if (action.isAbsUrl()) SourceLoginFormAction.OpenUrl
                else SourceLoginFormAction.Legacy,
                action,
                long,
                state.value.values,
            )
            return
        }
        val form = formValues()
        mutable.update { it.copy(busy = true, errors = emptyMap(), error = null) }
        actionJob = execute {
            try {
                val command = repository.action(action, stateJson, form)
                if (state.value.finished) return@execute
                if (command.malformed) error("登录UI v2 动作返回了无效命令")
                if (command.loginJson != null && !repository.store(command.loginJson))
                    error("登录UI v2 登录信息保存失败")
                if (command.close) {
                    finish()
                    return@execute
                }
                val errors = command.error.orEmpty()
                if (errors.isEmpty() && row.countdown != null && row.countdown > 0) {
                    deadlines[action] = now() + row.countdown.toLong() * 1000
                    tickCountdowns()
                }
                if (command.stateJson != null) render(command.stateJson, errors)
                else {
                    mutable.update { it.copy(busy = false, errors = errors) }
                    persist()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    private fun formValues(): Map<String, String> =
        if (!state.value.v2) state.value.values.toMap()
        else
            state.value.rows
                .filter {
                    it.type in
                        listOf(
                            RowUi.Type.text,
                            RowUi.Type.password,
                            RowUi.Type.select,
                            RowUi.Type.toggle,
                        )
                }
                .associate { it.key to state.value.values[it.key].orEmpty() }

    fun runLegacy(event: SourceLoginFormEffect, java: Any) {
        execute { repository.legacyAction(event.text, event.values, event.long, java) }
    }

    fun submit() {
        if (
            !state.value.v2 &&
                repositoryReady &&
                state.value.rendered &&
                !state.value.rendering &&
                !state.value.loading &&
                !state.value.busy
        )
            effect(SourceLoginFormAction.Login, values = state.value.values)
    }

    fun login(event: SourceLoginFormEffect, java: Any) {
        if (state.value.busy || state.value.finished) return
        submitted = true
        mutable.update { it.copy(busy = true, error = null) }
        persist()
        actionJob = execute {
            if (repository.legacyLogin(event.values, java)) {
                effect(SourceLoginFormAction.Toast, resource = R.string.success)
                finish()
            } else mutable.update { it.copy(busy = false) }
        }
    }

    override fun upUiData(data: Map<String, Any?>?) {
        viewModelScope.launch {
            if (state.value.finished || state.value.v2) return@launch
            changed = true
            val values = state.value.values.toMutableMap()
            val rows =
                state.value.rows.map { row ->
                    if (data == null || row.key in data) {
                        val input = data?.get(row.key)?.toString() ?: row.default
                        if (row.type != RowUi.Type.button && row.type != RowUi.Type.label)
                            values[row.key] =
                                input
                                    ?: when (row.type) {
                                        RowUi.Type.toggle,
                                        RowUi.Type.select -> row.options.firstOrNull().orEmpty()
                                        else -> ""
                                    }
                        if (row.type == RowUi.Type.button)
                            row.copy(
                                label = if (data == null) row.baseLabel else input ?: row.baseLabel
                            )
                        else row
                    } else row
                }
            if (data == null) {
                val names =
                    rows
                        .filter { it.type !in listOf(RowUi.Type.button, RowUi.Type.label) }
                        .map { it.key }
                        .toSet()
                values.keys.retainAll(names)
            } else
                data
                    .filterKeys { key -> rows.none { it.key == key } }
                    .forEach { (key, value) -> values[key] = value?.toString().orEmpty() }
            val previous = state.value.values
            mutable.update { it.copy(rows = rows, values = values.toMap()) }
            persist()
            rows
                .filter {
                    it.type in listOf(RowUi.Type.text, RowUi.Type.password) &&
                        it.action != null &&
                        previous[it.key] != values[it.key]
                }
                .forEach(::debounce)
            rows
                .filter {
                    it.type == RowUi.Type.select &&
                        it.action != null &&
                        previous[it.key] != values[it.key]
                }
                .forEach { action(it, false, throttle = false) }
        }
    }

    override fun reUiView(deltaUp: Boolean) {
        viewModelScope.launch {
            changed = true
            render(delta = deltaUp)
        }
    }

    private fun tickCountdowns() {
        val time = now()
        deadlines.entries.removeAll { it.value <= time }
        mutable.update {
            it.copy(countdowns = deadlines.mapValues { ((it.value - time + 999) / 1000).toInt() })
        }
        persist()
        if (deadlines.isNotEmpty() && countdownJob?.isActive != true && !state.value.finished)
            countdownJob = execute {
                while (deadlines.isNotEmpty() && !state.value.finished) {
                    delay(1000)
                    tickCountdowns()
                }
            }
    }

    fun showHeader() {
        val generation = ++headerGeneration
        execute {
            val value = repository.header().orEmpty()
            if (generation == headerGeneration && !state.value.finished) {
                mutable.update { it.copy(header = value) }
                persist()
            }
        }
    }

    fun closeHeader() {
        headerGeneration++
        mutable.update { it.copy(header = null) }
        persist()
    }

    fun copyHeader() {
        state.value.header?.takeIf(String::isNotBlank)?.let {
            effect(SourceLoginFormAction.Copy, it)
        }
    }

    fun deleteHeader() {
        execute { repository.deleteHeader() }
    }

    fun requestClear(value: Boolean) {
        mutable.update { it.copy(clear = value) }
        persist()
    }

    fun clear() {
        if (state.value.finished) return
        cancelWork()
        mutable.update { it.copy(busy = true, clear = false) }
        execute {
            repository.clear()
            changed = false
            mutable.update { it.copy(values = emptyMap()) }
            effect(SourceLoginFormAction.Toast, resource = R.string.success)
            finish()
        }
    }

    fun log() = effect(SourceLoginFormAction.Log)

    fun close() {
        if (state.value.finished) return
        cancelWork()
        mutable.update { it.copy(busy = true) }
        execute {
            if (repositoryReady && !state.value.v2 && changed && !submitted)
                repository.persist(state.value.values)
            finish()
        }
    }

    private fun cancelWork() {
        generation++
        initializationJob?.cancel()
        renderJob?.cancel()
        actionJob?.cancel()
        debounceJobs.values.forEach(Job::cancel)
        debounceJobs.clear()
    }

    private fun finish() {
        cancelWork()
        countdownJob?.cancel()
        mutable.update {
            it.copy(
                finished = true,
                busy = false,
                loading = false,
                pending =
                    it.pending.filter { effect -> effect.action == SourceLoginFormAction.Toast },
            )
        }
        persist()
    }
}
