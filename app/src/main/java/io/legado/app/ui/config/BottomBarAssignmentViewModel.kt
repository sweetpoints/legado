package io.legado.app.ui.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Only filename choices, the name and selection, and a small completion ticket enter SavedState.
 */
data class BottomBarAssignmentState(
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val name: String = "",
    val start: Int = 0,
    val end: Int = 0,
    val images: List<String> = emptyList(),
    val slots: List<BottomBarAssignmentSlot> = emptyList(),
    val palette: String? = null,
    val normal: Boolean = false,
    val issue: BottomBarAssignmentIssue? = null,
    val pendingClose: BottomBarAssignmentIssue? = null,
    val finished: Boolean = false,
    val saved: Boolean = false,
)

enum class BottomBarAssignmentIssue {
    Invalid,
    NoImages,
    NeedSelected,
    Closed,
}

class BottomBarAssignmentViewModel(
    private val repository: BottomBarAssignmentRepository,
    private val savedState: SavedStateHandle,
    val session: String,
    val editName: String?,
    initialName: String,
    private val sizePx: Int,
) : ViewModel() {
    private val mutable =
        MutableStateFlow(
            BottomBarAssignmentState(
                name = initialInput(savedState.get<String>("barAssign.name") ?: initialName),
                start = savedState.get<Int>("barAssign.start") ?: 0,
                end = savedState.get<Int>("barAssign.end") ?: 0,
                palette = savedState.get<String>("barAssign.palette"),
                normal = savedState.get<Boolean>("barAssign.normal") == true,
                pendingClose =
                    savedState.get<String>("barAssign.close")?.let {
                        BottomBarAssignmentIssue.valueOf(it)
                    },
                finished = savedState.get<Boolean>("barAssign.finished") == true,
                saved = savedState.get<Boolean>("barAssign.saved") == true,
            )
        )
    val state: StateFlow<BottomBarAssignmentState> = mutable
    private var loading: Job? = null

    init {
        if (!state.value.finished) load()
    }

    fun load() {
        if (state.value.busy || state.value.finished || loading?.isActive == true) return
        loading = viewModelScope.launch {
            try {
                if (session.isEmpty()) {
                    finish(BottomBarAssignmentIssue.Invalid)
                    return@launch
                }
                val data = repository.load(session, sizePx)
                currentCoroutineContext().ensureActive()
                if (state.value.finished) return@launch
                if (data.images.isEmpty()) {
                    repository.discard(session)
                    currentCoroutineContext().ensureActive()
                    finish(BottomBarAssignmentIssue.NoImages)
                    return@launch
                }
                val selected = savedState.get<ArrayList<String>>("barAssign.selected")
                val normal = savedState.get<ArrayList<String>>("barAssign.unselected")
                val rows =
                    if (savedState.get<Boolean>("barAssign.rows") == true)
                        data.slots.mapIndexed { index, row ->
                            row.copy(
                                selected = selected?.getOrNull(index)?.takeIf { it in data.images },
                                normal = normal?.getOrNull(index)?.takeIf { it in data.images },
                            )
                        }
                    else data.slots
                mutable.value =
                    state.value.copy(
                        loaded = true,
                        images = data.images,
                        slots = rows,
                        issue = null,
                        palette =
                            state.value.palette?.takeIf { id ->
                                rows.any { it.slot == id } &&
                                    (!state.value.normal ||
                                        rows.first { it.slot == id }.selected != null)
                            },
                    )
                rememberRows()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.value = state.value.copy(issue = BottomBarAssignmentIssue.Invalid)
            }
        }
    }

    fun name(text: String, start: Int, end: Int) {
        if (state.value.busy || state.value.finished) return
        // Valid manager names are at most 80 code points/240 UTF-8 bytes. Keep room
        // for invalid edits, but reject oversized paste before it enters the Bundle.
        if (text.codePointCount(0, text.length) > MAX_INPUT_CODE_POINTS) {
            mutable.value = state.value.copy(issue = BottomBarAssignmentIssue.Invalid)
            return
        }
        mutable.value =
            state.value.copy(
                name = text,
                start = start.coerceIn(0, text.length),
                end = end.coerceIn(0, text.length),
            )
        savedState["barAssign.name"] = text
        savedState["barAssign.start"] = state.value.start
        savedState["barAssign.end"] = state.value.end
    }

    fun palette(slot: String, normal: Boolean) {
        val row = state.value.slots.find { it.slot == slot } ?: return
        if (state.value.busy || state.value.finished || normal && row.selected == null) return
        mutable.value = state.value.copy(palette = slot, normal = normal)
        savedState["barAssign.palette"] = slot
        savedState["barAssign.normal"] = normal
    }

    fun cancelPalette() {
        mutable.value = state.value.copy(palette = null)
        savedState.remove<String>("barAssign.palette")
    }

    fun choose(image: String?) {
        if (
            state.value.busy ||
                state.value.finished ||
                image != null && image !in state.value.images
        )
            return
        val id = state.value.palette ?: return
        val rows =
            state.value.slots.map { row ->
                if (row.slot != id) row
                else if (state.value.normal) {
                    if (row.selected == null) row else row.copy(normal = image)
                } else row.copy(selected = image, normal = if (image == null) null else row.normal)
            }
        mutable.value = state.value.copy(slots = rows, issue = null)
        rememberRows()
        cancelPalette()
    }

    private fun rememberRows() {
        savedState["barAssign.rows"] = true
        savedState["barAssign.selected"] =
            ArrayList(state.value.slots.map { it.selected.orEmpty() })
        savedState["barAssign.unselected"] =
            ArrayList(state.value.slots.map { it.normal.orEmpty() })
    }

    fun save() {
        val before = state.value
        if (!before.loaded || before.busy || before.finished) return
        if (before.slots.none { it.selected != null }) {
            mutable.value = before.copy(issue = BottomBarAssignmentIssue.NeedSelected)
            return
        }
        mutable.value = before.copy(busy = true, issue = null, palette = null)
        savedState.remove<String>("barAssign.palette")
        viewModelScope.launch {
            try {
                // Manager's synchronous transaction must finish before this owner may discard the
                // session.
                withContext(NonCancellable) {
                    repository.save(session, before.name.trim(), editName, before.slots)
                }
                currentCoroutineContext().ensureActive()
                savedState["barAssign.saved"] = true
                mutable.value = state.value.copy(saved = true)
                finish(BottomBarAssignmentIssue.Closed)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.value =
                    state.value.copy(busy = false, issue = BottomBarAssignmentIssue.Invalid)
            }
        }
    }

    fun close() {
        if (state.value.busy || state.value.finished) return
        loading?.cancel()
        mutable.value = state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { repository.discard(session) }
                currentCoroutineContext().ensureActive()
                finish(BottomBarAssignmentIssue.Closed)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.value =
                    state.value.copy(busy = false, issue = BottomBarAssignmentIssue.Invalid)
            }
        }
    }

    private fun finish(issue: BottomBarAssignmentIssue) {
        savedState["barAssign.finished"] = true
        savedState["barAssign.close"] = issue.name
        mutable.value = state.value.copy(finished = true, busy = false, pendingClose = issue)
    }

    fun delivered(issue: BottomBarAssignmentIssue) {
        if (state.value.pendingClose != issue) return
        savedState.remove<String>("barAssign.close")
        mutable.value = state.value.copy(pendingClose = null)
    }

    suspend fun releaseIfNeeded() {
        if (!state.value.saved && !state.value.busy && session.isNotEmpty())
            repository.discard(session)
    }

    suspend fun preview(image: String) = repository.preview(session, image, sizePx)

    fun stop() {
        viewModelScope.cancel()
    }

    private companion object {
        const val MAX_INPUT_CODE_POINTS = 512

        fun initialInput(text: String) =
            if (text.codePointCount(0, text.length) <= MAX_INPUT_CODE_POINTS) text
            else io.legado.app.help.BottomBarSkinFormat.sanitize(text)
    }
}
