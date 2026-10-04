package io.legado.app.ui.autoTask

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AutoTaskDebugIssue {
    Busy,
    Interrupted,
}

data class AutoTaskDebugUiState(
    val output: String = "",
    val isLoading: Boolean = true,
    val isRunning: Boolean = false,
    val taskMissing: Boolean = false,
    val error: String? = null,
    val issue: AutoTaskDebugIssue? = null,
    val closed: Boolean = false,
)

private const val PREFIX = "autoTask.debug."

class AutoTaskDebugViewModel(
    private val repository: AutoTaskDebugRepository,
    private val saved: SavedStateHandle,
    private val taskId: String? = saved["autoTaskId"],
) : ViewModel() {
    // Compatibility with the existing SavedState factory until the host installs its explicit
    // factory.
    constructor(
        saved: SavedStateHandle
    ) : this(AppAutoTaskDebugRepository(splitties.init.appCtx), saved)

    private val session =
        saved.get<String>(PREFIX + "session")
            ?: UUID.randomUUID().toString().also { saved[PREFIX + "session"] = it }
    private val mutable =
        MutableStateFlow(AutoTaskDebugUiState(closed = saved[PREFIX + "closed"] ?: false))
    val uiState = mutable.asStateFlow()
    private val guard = Any()
    private val generation = AtomicLong()
    private var revision = 0L
    @Volatile private var stopped = false
    @Volatile private var owner: AutoTaskDebugLease? = null
    private var task: AutoTaskDebugSnapshot? = null
    private var loadJob: Job? = null
    private var debugJob: Job? = null
    private var hasRun = saved.get<Boolean>(PREFIX + "hasRun") ?: false
    private val records = Channel<AutoTaskDebugRecord>(Channel.CONFLATED)
    private val writer = viewModelScope.launch {
        for (record in records) try {
            repository.write(session, record)
        } catch (error: Exception) {
            failed(error)
        }
    }

    init {
        load()
    }

    private fun load() {
        loadJob = viewModelScope.launch {
            try {
                val loaded = taskId?.let { repository.load(it) }
                currentCoroutineContext().ensureActive()
                task = loaded
                val record = repository.read(session)
                currentCoroutineContext().ensureActive()
                check(record == null || record.taskId == taskId) { "Invalid debug session" }
                task = loaded
                synchronized(guard) {
                    revision = record?.revision ?: 0
                    hasRun = hasRun || record?.hasRun == true
                    mutable.value =
                        uiState.value.copy(
                            output = record?.output.orEmpty().takeLast(20_000),
                            isLoading = false,
                            taskMissing = loaded == null,
                            issue =
                                if (record?.running == true) AutoTaskDebugIssue.Interrupted
                                else null,
                        )
                    if (record?.running == true) checkpoint()
                }
                if (loaded != null && !hasRun && !uiState.value.closed) runDebug()
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                failed(error)
                mutable.value = uiState.value.copy(isLoading = false)
            }
        }
    }

    fun retryLoad() {
        if (!stopped && !uiState.value.isLoading) {
            mutable.value = uiState.value.copy(isLoading = true, error = null)
            load()
        }
    }

    private fun checkpoint() {
        val id = taskId ?: return
        revision++
        records.trySend(
            AutoTaskDebugRecord(id, uiState.value.output, hasRun, uiState.value.isRunning, revision)
        )
    }

    private fun change(token: Long, transform: (AutoTaskDebugUiState) -> AutoTaskDebugUiState) =
        synchronized(guard) {
            if (!stopped && generation.get() == token) {
                mutable.value = transform(uiState.value)
                checkpoint()
            }
        }

    fun runDebug() {
        val current = task ?: return
        if (stopped || uiState.value.closed || uiState.value.isLoading) return
        val token = generation.incrementAndGet()
        val previousJob = debugJob
        previousJob?.cancel()
        owner?.close()
        owner = null
        synchronized(guard) {
            hasRun = true
            saved[PREFIX + "hasRun"] = true
            mutable.value =
                uiState.value.copy(output = "", isRunning = true, error = null, issue = null)
            checkpoint()
        }
        debugJob = viewModelScope.launch {
            var lease: AutoTaskDebugLease? = null
            try {
                // Debug.log routes through the global owner: the prior runner must finish before
                // replacing it.
                previousJob?.join()
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    lease =
                        repository.acquire(current) { line ->
                            change(token) { it.copy(output = appendDebugOutput(it.output, line)) }
                        }
                }
                currentCoroutineContext().ensureActive()
                if (lease == null) {
                    change(token) { it.copy(issue = AutoTaskDebugIssue.Busy) }
                    return@launch
                }
                owner = lease
                val result = lease.run()
                currentCoroutineContext().ensureActive()
                change(token) { it.copy(output = appendDebugOutput(it.output, result.log)) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                change(token) { it.copy(error = error.localizedMessage ?: "Error") }
            } finally {
                lease?.close()
                if (owner === lease) owner = null
                change(token) { it.copy(isRunning = false) }
            }
        }
    }

    fun close() {
        if (uiState.value.closed) return
        synchronized(guard) {
            saved[PREFIX + "closed"] = true
            mutable.value = uiState.value.copy(closed = true, isRunning = false)
            checkpoint()
        }
        stop()
    }

    suspend fun flush() {
        val record =
            synchronized(guard) {
                taskId?.let {
                    AutoTaskDebugRecord(
                        it,
                        uiState.value.output,
                        hasRun,
                        uiState.value.isRunning,
                        revision,
                    )
                }
            }
        record?.let { repository.write(session, it) }
    }

    private fun failed(error: Exception) {
        if (error is CancellationException) throw error
        synchronized(guard) {
            if (!stopped)
                mutable.value = uiState.value.copy(error = error.localizedMessage ?: "Error")
        }
    }

    internal fun stop() {
        synchronized(guard) {
            stopped = true
            generation.incrementAndGet()
        }
        loadJob?.cancel()
        debugJob?.cancel()
        owner?.close()
        owner = null
        writer.cancel()
        records.close()
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}

/** Bound the displayed log while preserving the newest output. */
internal fun appendDebugOutput(output: String, line: String, maxLength: Int = 20_000): String =
    (if (output.isEmpty()) line else "$output\n$line").takeLast(maxLength)
