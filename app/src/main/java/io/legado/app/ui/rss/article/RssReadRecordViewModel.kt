package io.legado.app.ui.rss.article

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.repository.RssReadRecordItem
import io.legado.app.data.repository.RssReadRecordRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RssReadRecordAction {
    Read,
    Browser,
}

data class RssReadRecordEffect(val id: Long, val key: String, val action: RssReadRecordAction)

data class RssReadRecordState(
    val items: List<RssReadRecordItem> = emptyList(),
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val clearCount: Int? = null,
    val error: String? = null,
    val effect: RssReadRecordEffect? = null,
    val finished: Boolean = false,
) {
    val canAct
        get() = loaded && !loading && !busy && clearCount == null && effect == null && !finished
}

class RssReadRecordViewModel(
    private val repository: RssReadRecordRepository,
    private val saved: SavedStateHandle,
    origin: String?,
) : ViewModel() {
    private val origin: String? =
        if (saved.contains("rssHistory.origin")) saved.get<String>("rssHistory.origin")
        else origin.also { saved["rssHistory.origin"] = it }
    private val mutable =
        MutableStateFlow(
            RssReadRecordState(
                finished = saved.get<Boolean>("rssHistory.finished") == true,
                loading = saved.get<Boolean>("rssHistory.finished") != true,
            )
        )
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var countRevision = 0L

    init {
        load()
    }

    fun load() {
        if (state.value.finished || work?.isActive == true) return
        mutable.value = state.value.copy(loading = true, error = null)
        val revision = countRevision
        work = viewModelScope.launch {
            try {
                val items = repository.load(origin).toList()
                val count =
                    if (saved.get<Boolean>("rssHistory.confirmClear") == true)
                        repository.count(origin)
                    else null
                if (state.value.finished) return@launch
                val currentCount =
                    if (
                        revision == countRevision &&
                            saved.get<Boolean>("rssHistory.confirmClear") == true
                    )
                        count
                    else null
                mutable.value =
                    state.value.copy(
                        items = items,
                        clearCount = currentCount,
                        loading = false,
                        loaded = true,
                        effect = restoreEffect(items),
                    )
            } catch (error: Throwable) {
                failure(error)
            }
        }
    }

    fun requestClear() {
        if (!state.value.canAct) return
        val revision = ++countRevision
        saved["rssHistory.confirmClear"] = true
        mutable.value = state.value.copy(busy = true, error = null)
        work = viewModelScope.launch {
            try {
                val count = repository.count(origin)
                if (revision == countRevision && !state.value.finished)
                    mutable.value = state.value.copy(clearCount = count, busy = false)
            } catch (error: Throwable) {
                if (revision == countRevision) {
                    saved["rssHistory.confirmClear"] = false
                    failure(error)
                } else if (error is CancellationException) throw error
            }
        }
    }

    fun cancelClear() {
        // A confirmed database deletion cannot be canceled halfway through.
        if (state.value.busy && state.value.clearCount != null) return
        ++countRevision
        saved["rssHistory.confirmClear"] = false
        work?.cancel()
        mutable.value = state.value.copy(busy = false, clearCount = null)
    }

    fun confirmClear() {
        if (
            state.value.loading ||
                state.value.busy ||
                state.value.clearCount == null ||
                state.value.finished
        )
            return
        mutable.value = state.value.copy(busy = true, error = null)
        work = viewModelScope.launch {
            try {
                repository.clear(origin)
                val items = repository.load(origin).toList()
                saved["rssHistory.confirmClear"] = false
                mutable.value = state.value.copy(items = items, clearCount = null, busy = false)
            } catch (error: Throwable) {
                failure(error)
            }
        }
    }

    fun read(key: String) = navigate(key, RssReadRecordAction.Read)

    fun browser(key: String) = navigate(key, RssReadRecordAction.Browser)

    private fun navigate(key: String, action: RssReadRecordAction) {
        if (!state.value.canAct || state.value.items.none { it.key == key }) return
        val token = (saved.get<Long>("rssHistory.sequence") ?: 0) + 1
        saved["rssHistory.sequence"] = token
        saved["rssHistory.effectId"] = token
        saved["rssHistory.effectKey"] = key
        saved["rssHistory.effectAction"] = action.name
        mutable.value =
            state.value.copy(effect = RssReadRecordEffect(token, key, action), error = null)
    }

    private fun restoreEffect(items: List<RssReadRecordItem>): RssReadRecordEffect? {
        val key = saved.get<String>("rssHistory.effectKey") ?: return null
        if (items.none { it.key == key }) {
            clearEffect()
            return null
        }
        val token = saved.get<Long>("rssHistory.effectId") ?: return null
        val action =
            saved.get<String>("rssHistory.effectAction")?.let {
                runCatching { RssReadRecordAction.valueOf(it) }.getOrNull()
            } ?: return null
        return RssReadRecordEffect(token, key, action)
    }

    suspend fun resolve(token: Long): RssReadRecord? {
        val effect = state.value.effect ?: return null
        if (effect.id != token || state.value.finished) return null
        val record = repository.resolve(effect.key, origin)
        if (state.value.effect?.id != token || state.value.finished) return null
        if (record == null) {
            clearEffect()
            load()
        }
        return record
    }

    fun delivered(token: Long) {
        val effect = state.value.effect ?: return
        if (effect.id != token) return
        clearEffect()
        if (effect.action == RssReadRecordAction.Read) finish()
    }

    fun failed(token: Long, message: String) {
        if (state.value.effect?.id != token) return
        clearEffect()
        mutable.value = state.value.copy(error = message)
    }

    private fun clearEffect() {
        saved.remove<String>("rssHistory.effectKey")
        saved.remove<Long>("rssHistory.effectId")
        saved.remove<String>("rssHistory.effectAction")
        mutable.value = state.value.copy(effect = null)
    }

    fun cancel() {
        if (state.value.busy && state.value.clearCount != null || state.value.finished) return
        ++countRevision
        work?.cancel()
        clearEffect()
        saved["rssHistory.confirmClear"] = false
        finish()
    }

    private fun finish() {
        saved["rssHistory.finished"] = true
        mutable.value = state.value.copy(finished = true, loading = false, busy = false)
    }

    private fun failure(error: Throwable) {
        if (error is CancellationException) throw error
        if (!state.value.finished)
            mutable.value =
                state.value.copy(
                    loading = false,
                    busy = false,
                    error = error.localizedMessage ?: error.toString(),
                )
    }

    fun stop() {
        work?.cancel()
    }

    override fun onCleared() {
        stop()
    }
}
