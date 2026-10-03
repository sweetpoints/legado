package io.legado.app.ui.book.bookmark

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AllBookmarksEffectType {
    Open,
    Directory,
    Exported,
}

data class AllBookmarksEffect(
    val nonce: String = UUID.randomUUID().toString(),
    val type: AllBookmarksEffectType,
    val bookmark: Long? = null,
    val edit: Boolean = false,
    val markdown: Boolean = false,
)

data class AllBookmarksState(
    val loaded: Boolean = false,
    val rows: List<AllBookmarksRow> = emptyList(),
    val exporting: Boolean = false,
    val effect: AllBookmarksEffect? = null,
    val error: String? = null,
    val scroll: Int = 0,
    val offset: Int = 0,
)

class AllBookmarksViewModel(
    private val repository: AllBookmarksRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val mutable =
        MutableStateFlow(
            AllBookmarksState(
                effect =
                    saved.get<String>("allBookmarks.effect")?.let {
                        GSON.fromJsonObject<AllBookmarksEffect>(it).getOrNull()
                    },
                scroll = saved.get<Int>("allBookmarks.scroll") ?: 0,
                offset = saved.get<Int>("allBookmarks.offset") ?: 0,
            )
        )
    val state: StateFlow<AllBookmarksState> = mutable
    private var observing: Job? = null

    init {
        observe()
    }

    fun observe() {
        observing?.cancel()
        observing = viewModelScope.launch {
            try {
                repository.observe().collect { rows ->
                    currentCoroutineContext().ensureActive()
                    mutable.value = state.value.copy(loaded = true, rows = rows, error = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.value = state.value.copy(error = error.localizedMessage ?: "Error")
            }
        }
    }

    fun open(id: Long, edit: Boolean) {
        if (
            state.value.exporting ||
                state.value.effect != null ||
                state.value.rows.none { it.id == id }
        )
            return
        effect(AllBookmarksEffect(type = AllBookmarksEffectType.Open, bookmark = id, edit = edit))
    }

    suspend fun resolveOpen(value: AllBookmarksEffect): AllBookmarksDestination? {
        if (state.value.effect?.nonce != value.nonce || value.type != AllBookmarksEffectType.Open)
            return null
        val result = repository.resolve(checkNotNull(value.bookmark), value.edit)
        currentCoroutineContext().ensureActive()
        return result
    }

    fun requestExport(markdown: Boolean) {
        if (state.value.exporting || state.value.effect != null) return
        effect(AllBookmarksEffect(type = AllBookmarksEffectType.Directory, markdown = markdown))
    }

    private fun effect(value: AllBookmarksEffect) {
        saved["allBookmarks.effect"] = GSON.toJson(value)
        mutable.value = state.value.copy(effect = value)
    }

    fun delivered(nonce: String): AllBookmarksEffect? {
        val value = state.value.effect?.takeIf { it.nonce == nonce } ?: return null
        if (value.type == AllBookmarksEffectType.Directory) {
            saved["allBookmarks.directory"] = nonce
            saved["allBookmarks.markdown"] = value.markdown
        }
        saved.remove<String>("allBookmarks.effect")
        mutable.value = state.value.copy(effect = null)
        return value
    }

    fun directoryResult(directory: String?) {
        if (saved.get<String>("allBookmarks.directory") == null) return
        saved.remove<String>("allBookmarks.directory")
        val markdown = saved.get<Boolean>("allBookmarks.markdown") == true
        saved.remove<Boolean>("allBookmarks.markdown")
        if (directory == null || state.value.exporting) return
        mutable.value = state.value.copy(exporting = true, error = null)
        viewModelScope.launch {
            try {
                repository.export(directory, markdown)
                currentCoroutineContext().ensureActive()
                mutable.value = state.value.copy(exporting = false)
                if (state.value.effect == null)
                    effect(AllBookmarksEffect(type = AllBookmarksEffectType.Exported))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.value =
                    state.value.copy(exporting = false, error = error.localizedMessage ?: "Error")
            }
        }
    }

    fun deliveryFailed(value: AllBookmarksEffect, error: String) {
        if (value.type == AllBookmarksEffectType.Directory) {
            saved.remove<String>("allBookmarks.directory")
            saved.remove<Boolean>("allBookmarks.markdown")
        }
        mutable.value = state.value.copy(error = error)
    }

    fun clearError() {
        mutable.value = state.value.copy(error = null)
    }

    fun scroll(index: Int, offset: Int) {
        saved["allBookmarks.scroll"] = index.coerceAtLeast(0)
        saved["allBookmarks.offset"] = offset.coerceAtLeast(0)
    }

    fun stop() {
        viewModelScope.cancel()
    }
}
