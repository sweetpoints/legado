package io.legado.app.ui.book.read

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.TextAction
import io.legado.app.data.repository.TextActionRepository
import io.legado.app.data.repository.TextActionSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class TextActionEventKind {
    Invoke,
    Edit,
    Toast,
}

internal data class TextActionEvent(
    val id: Long,
    val kind: TextActionEventKind,
    val action: TextAction? = null,
    val message: String = "",
)

internal data class TextActionMenuState(
    val snapshot: TextActionSnapshot = TextActionSnapshot(emptyList(), emptyList()),
    val loading: Boolean = true,
    val more: Boolean = false,
    val events: List<TextActionEvent> = emptyList(),
)

/** Popup state is ephemeral. Persistent partition/speak choices belong to the repository. */
internal class TextActionMenuViewModel(private val repository: TextActionRepository) : ViewModel() {
    private val mutable = MutableStateFlow(TextActionMenuState())
    val state = mutable.asStateFlow()
    private var loadJob: Job? = null
    private var generation = 0L
    private var eventId = 0L

    fun refresh() {
        val token = ++generation
        loadJob?.cancel()
        mutable.value = state.value.copy(loading = true, more = false, events = emptyList())
        loadJob = viewModelScope.launch {
            try {
                val snapshot = repository.load()
                if (generation != token) return@launch
                mutable.value = state.value.copy(snapshot = snapshot, loading = false)
                snapshot.discoveryError?.let {
                    event(TextActionEventKind.Toast, message = "获取文字操作菜单出错:$it")
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                if (generation == token) {
                    mutable.value = state.value.copy(loading = false)
                    event(
                        TextActionEventKind.Toast,
                        message = "获取文字操作菜单出错:${error.localizedMessage.orEmpty()}",
                    )
                }
            }
        }
    }

    fun toggleMore() {
        if (state.value.snapshot.more.isNotEmpty())
            mutable.value = state.value.copy(more = !state.value.more)
    }

    fun reset() {
        mutable.value = state.value.copy(more = false, events = emptyList())
    }

    fun edit() {
        event(TextActionEventKind.Edit)
    }

    fun invoke(id: String) {
        (state.value.snapshot.primary + state.value.snapshot.more)
            .find { it.id == id }
            ?.let { action ->
                event(TextActionEventKind.Invoke, action)
            }
    }

    fun longPress() {
        viewModelScope.launch {
            try {
                val mode = repository.toggleSpeakMode()
                event(
                    TextActionEventKind.Toast,
                    message = if (mode == 1) "切换为从选择的地方开始一直朗读" else "切换为朗读选择内容",
                )
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                event(TextActionEventKind.Toast, message = error.localizedMessage.orEmpty())
            }
        }
    }

    private fun event(kind: TextActionEventKind, action: TextAction? = null, message: String = "") {
        eventId++
        mutable.value =
            state.value.copy(
                events = state.value.events + TextActionEvent(eventId, kind, action, message)
            )
    }

    fun consume(id: Long) {
        mutable.value = state.value.copy(events = state.value.events.filterNot { it.id == id })
    }
}

internal enum class TextPopupEdge {
    Top,
    Bottom,
}

internal data class TextPopupPosition(val edge: TextPopupEdge, val x: Int, val y: Int)

internal fun textActionPopupPosition(
    windowHeight: Int,
    startX: Int,
    startTopY: Int,
    startBottomY: Int,
    endX: Int,
    endBottomY: Int,
): TextPopupPosition =
    when {
        startTopY > 500 -> TextPopupPosition(TextPopupEdge.Bottom, startX, windowHeight - startTopY)
        endBottomY - startBottomY > 500 ->
            TextPopupPosition(TextPopupEdge.Top, startX, startBottomY)
        else -> TextPopupPosition(TextPopupEdge.Top, endX, endBottomY)
    }
