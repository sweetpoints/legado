package io.legado.app.ui.association

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.AddBookLinkRepository
import io.legado.app.data.repository.BookLinkTarget
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class AddBookLinkState(
    val loading: Boolean = true,
    val target: BookLinkTarget? = null,
    val error: String? = null,
    val finished: Boolean = false,
)

internal class AddBookLinkViewModel(
    private val repository: AddBookLinkRepository,
    private val saved: SavedStateHandle,
    private val url: String,
) : ViewModel() {
    private val session =
        saved.get<String>("session") ?: UUID.randomUUID().toString().also { saved["session"] = it }
    private val mutable =
        MutableStateFlow(
            AddBookLinkState(
                loading = false,
                target =
                    saved.get<String>("target")?.let {
                        GSON.fromJsonObject<BookLinkTarget>(it).getOrNull()
                    },
                error = saved["error"],
                finished = saved["finished"] ?: false,
            )
        )
    val state = mutable.asStateFlow()
    private var operation: Job? = null

    init {
        if (!state.value.finished && state.value.target == null && state.value.error == null) load()
    }

    private fun load() {
        if (operation?.isActive == true || state.value.finished) return
        mutable.value = state.value.copy(loading = true)
        operation = viewModelScope.launch {
            try {
                val target = repository.resolve(session, url)
                if (state.value.finished) return@launch
                saved["target"] = GSON.toJson(target)
                mutable.value = state.value.copy(loading = false, target = target)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!state.value.finished) {
                    val message = error.localizedMessage.orEmpty()
                    saved["error"] = message
                    mutable.value = state.value.copy(loading = false, error = message)
                }
            }
        }
    }

    fun consumeResult() {
        saved.remove<String>("target")
        saved.remove<String>("error")
        saved["finished"] = true
        mutable.value =
            state.value.copy(loading = false, target = null, error = null, finished = true)
    }

    fun cancel() {
        consumeResult()
        operation?.cancel()
    }
}
