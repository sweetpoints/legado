package io.legado.app.ui.code

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import io.legado.app.model.analyzeRule.CurlAnalyzeUrlConverter
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class CurlAction {
    Copy,
    Insert,
    Toast,
}

internal enum class CurlNotice {
    Conversion,
    Failed,
    NoOutput,
    InsertFailed,
}

internal data class CurlEffect(
    val id: Long,
    val action: CurlAction,
    val notice: CurlNotice? = null,
    val reason: CurlAnalyzeUrlConverter.ErrorReason? = null,
)

internal data class CurlConversionState(
    val draft: CurlConversionDraft = CurlConversionDraft(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val converting: Boolean = false,
    val saving: Boolean = false,
    val inserting: Boolean = false,
    val finished: Boolean = false,
    val effects: List<CurlEffect> = emptyList(),
)

/**
 * Commands/output stay in the repository. SavedState contains IDs, flags and selection offsets
 * only.
 */
internal class CurlConversionViewModel(
    private val repo: CurlConversionRepository,
    private val saved: SavedStateHandle,
    private val inputKey: String?,
    val canInsert: Boolean,
) : ViewModel() {
    private val session =
        saved.get<String>("session") ?: UUID.randomUUID().toString().also { saved["session"] = it }
    private val mutable =
        kotlinx.coroutines.flow.MutableStateFlow(
            CurlConversionState(
                finished = saved["finished"] ?: false,
                inserting = saved.get<Long>("insertTicket") != null,
                effects =
                    saved
                        .get<String>("effects")
                        ?.let { GSON.fromJsonArray<CurlEffect>(it).getOrNull() }
                        .orEmpty(),
            )
        )
    val state = mutable.asStateFlow()
    private var revision = 0L
    private var generation = 0L
    private var nextEffect = saved.get<Long>("effectId") ?: 0L
    private var insertTicket: Long? = saved["insertTicket"]
    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var convertJob: Job? = null

    init {
        if (state.value.finished) mutable.value = state.value.copy(loading = false) else load()
    }

    private fun load() {
        if (loadJob?.isActive == true) return
        mutable.value = state.value.copy(loading = true, loadFailed = false)
        loadJob = viewModelScope.launch {
            try {
                val draft = repo.restore(session, inputKey)
                if (state.value.finished) return@launch
                revision = draft.revision
                val start =
                    (saved.get<Int>("selectionStart") ?: draft.selectionStart).coerceIn(
                        0,
                        draft.input.length,
                    )
                val end =
                    (saved.get<Int>("selectionEnd") ?: draft.selectionEnd).coerceIn(
                        0,
                        draft.input.length,
                    )
                mutable.value =
                    state.value.copy(
                        draft = draft.copy(selectionStart = start, selectionEnd = end),
                        loading = false,
                    )
                // A retained VM receives its original asynchronous callback. A newly created VM
                // cannot safely retry a delivered external insertion.
                if (insertTicket != null && saved.get<Boolean>("insertDelivered") == true) {
                    clearInsert()
                    queue(CurlEffect(id(), CurlAction.Toast, CurlNotice.InsertFailed))
                } else
                    insertTicket?.let { ticket ->
                        if (state.value.effects.none { it.id == ticket })
                            queue(CurlEffect(ticket, CurlAction.Insert))
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!state.value.finished)
                    mutable.value = state.value.copy(loading = false, loadFailed = true)
            }
        }
    }

    fun retryLoad() {
        if (state.value.loadFailed && !state.value.finished) load()
    }

    private fun editable() =
        !state.value.loading && !state.value.loadFailed && !state.value.finished

    private fun update(draft: CurlConversionDraft) {
        mutable.value = state.value.copy(draft = draft, saving = true)
        saved["selectionStart"] = draft.selectionStart
        saved["selectionEnd"] = draft.selectionEnd
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            try {
                repo.save(session, draft)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!state.value.finished)
                    queue(CurlEffect(id(), CurlAction.Toast, CurlNotice.Failed))
            } finally {
                if (state.value.draft.revision == draft.revision)
                    mutable.value = state.value.copy(saving = false)
            }
        }
    }

    fun input(text: String, start: Int, end: Int) {
        if (!editable()) return
        val changed = text != state.value.draft.input
        if (changed) {
            generation++
            convertJob?.cancel()
            mutable.value = state.value.copy(converting = false)
        }
        update(
            state.value.draft.copy(
                input = text,
                selectionStart = start.coerceIn(0, text.length),
                selectionEnd = end.coerceIn(0, text.length),
                revision = ++revision,
            )
        )
    }

    fun direction(direction: CurlDirection) {
        if (!editable() || direction == state.value.draft.direction) return
        generation++
        convertJob?.cancel()
        mutable.value = state.value.copy(converting = false)
        update(state.value.draft.copy(direction = direction, output = "", revision = ++revision))
    }

    fun convert() {
        if (!editable() || state.value.converting) return
        val captured = state.value.draft
        val token = ++generation
        mutable.value = state.value.copy(converting = true)
        convertJob = viewModelScope.launch {
            try {
                val output = repo.convert(captured.input, captured.direction)
                if (generation != token || state.value.finished) return@launch
                update(state.value.draft.copy(output = output, revision = ++revision))
                mutable.value = state.value.copy(converting = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation != token || state.value.finished) return@launch
                mutable.value = state.value.copy(converting = false)
                update(state.value.draft.copy(output = "", revision = ++revision))
                if (error is CurlAnalyzeUrlConverter.ConversionException)
                    prepare(
                        CurlAction.Toast,
                        CurlNotice.Conversion,
                        error.reason,
                        error.detail,
                    )
                else queue(CurlEffect(id(), CurlAction.Toast, CurlNotice.Failed))
            }
        }
    }

    private fun id(): Long {
        nextEffect++
        saved["effectId"] = nextEffect
        return nextEffect
    }

    private fun queue(effect: CurlEffect) {
        if (!state.value.finished) {
            mutable.value = state.value.copy(effects = state.value.effects + effect)
            saveEffects()
        }
    }

    private fun saveEffects() {
        saved["effects"] = GSON.toJson(state.value.effects)
    }

    private fun prepare(
        action: CurlAction,
        notice: CurlNotice? = null,
        reason: CurlAnalyzeUrlConverter.ErrorReason? = null,
        text: String = state.value.draft.output,
    ) {
        val effect = CurlEffect(id(), action, notice, reason)
        val payload =
            state.value.draft.copy(input = "", output = text, selectionStart = 0, selectionEnd = 0)
        if (action == CurlAction.Insert) {
            insertTicket = effect.id
            saved["insertTicket"] = effect.id
            saved["insertDelivered"] = false
            mutable.value = state.value.copy(inserting = true)
        }
        viewModelScope.launch {
            try {
                repo.save("$session-effect-${effect.id}", payload)
                queue(effect)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (action == CurlAction.Insert) clearInsert()
                queue(CurlEffect(id(), CurlAction.Toast, CurlNotice.Failed))
            }
        }
    }

    fun copy() {
        if (!editable()) return
        if (state.value.draft.output.isEmpty())
            queue(CurlEffect(id(), CurlAction.Toast, CurlNotice.NoOutput))
        else prepare(CurlAction.Copy)
    }

    fun insert() {
        if (!editable() || !canInsert || state.value.inserting) return
        if (state.value.draft.output.isEmpty())
            queue(CurlEffect(id(), CurlAction.Toast, CurlNotice.NoOutput))
        else prepare(CurlAction.Insert)
    }

    suspend fun effectText(effect: CurlEffect): String =
        repo.restore("$session-effect-${effect.id}", null).output

    fun consume(effect: CurlEffect) {
        mutable.value =
            state.value.copy(effects = state.value.effects.filterNot { it.id == effect.id })
        saveEffects()
        if (effect.action == CurlAction.Insert) saved["insertDelivered"] = true
    }

    fun insertionResult(ticket: Long, success: Boolean) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (state.value.finished || insertTicket != ticket) return@launch
            clearInsert()
            if (success) finish()
            else queue(CurlEffect(id(), CurlAction.Toast, CurlNotice.InsertFailed))
        }
    }

    private fun clearInsert() {
        insertTicket = null
        saved.remove<Long>("insertTicket")
        saved.remove<Boolean>("insertDelivered")
        mutable.value = state.value.copy(inserting = false)
    }

    fun flush() {
        if (editable()) {
            val draft = state.value.draft
            viewModelScope.launch {
                try {
                    repo.save(session, draft)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {}
            }
        }
    }

    fun finish() {
        saved["finished"] = true
        saved["effects"] = "[]"
        generation++
        mutable.value = state.value.copy(finished = true, converting = false, effects = emptyList())
        loadJob?.cancel()
        convertJob?.cancel()
        // Pending Atomic writes own NonCancellable IO and may finish after the host closes.
    }
}
