package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SpeakEngineAction {
    Login,
    Edit,
    ImportLocal,
    Import,
    Export,
    Applied,
    ClearCache,
    CacheCleared,
    SystemExport,
}

data class SpeakEngineEffect(
    val id: Long,
    val action: SpeakEngineAction,
    val argument: String = "",
    val export: SpeakEngineExport? = null,
)

data class SpeakEngineUiState(
    val selection: String? = null,
    val systems: List<SpeakSystemEngine> = emptyList(),
    val engines: List<SpeakHttpEngine> = emptyList(),
    val histories: List<String> = emptyList(),
    val online: Boolean = false,
    val input: String = "",
    val deleteId: Long? = null,
    val share: SpeakEngineShare? = null,
    val pending: List<SpeakEngineEffect> = emptyList(),
    val busy: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
) {
    val systemName: String?
        get() =
            if (selection.isNullOrBlank()) ""
            else GSON.fromJsonObject<SelectItem<String>>(selection).getOrNull()?.value
}

class SpeakEngineViewModel(
    private val repository: SpeakEngineRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val historyMutex = Mutex()
    private var nextId = saved.get<Long>("speak.next") ?: 0L
    private val mutable =
        MutableStateFlow(
            SpeakEngineUiState(
                selection =
                    if (saved.get<Boolean>("speak.loaded") == true) saved["speak.selection"]
                    else repository.initialSelection(),
                online = saved["speak.online"] ?: false,
                input = saved["speak.input"] ?: "",
                deleteId = saved["speak.delete"],
                finished = saved["speak.finished"] ?: false,
                pending =
                    (saved.get<ArrayList<String>>("speak.effects") ?: arrayListOf()).mapNotNull {
                        val parts = it.split('\n', limit = 3)
                        if (parts.size == 3)
                            SpeakEngineEffect(
                                parts[0].toLong(),
                                SpeakEngineAction.valueOf(parts[1]),
                                parts[2],
                            )
                        else null
                    },
                share =
                    saved.get<String>("speak.share")?.let {
                        SpeakEngineShare(it, passphrase = saved["speak.passphrase"])
                    },
            )
        )
    val state = mutable.asStateFlow()

    init {
        persist()
        run {
            val systems = repository.systemEngines()
            mutable.update { it.copy(systems = systems) }
        }
        run {
            historyMutex.withLock {
                val histories = repository.histories()
                mutable.update { it.copy(histories = histories) }
            }
        }
        run { repository.engines.collect { list -> mutable.update { it.copy(engines = list) } } }
        state.value.share?.let { share ->
            run {
                val details = repository.share(share.url)
                if (state.value.share?.url == share.url)
                    mutable.update {
                        it.copy(share = details.copy(passphrase = it.share?.passphrase))
                    }
            }
        }
    }

    private fun persist() {
        val value = state.value
        saved["speak.loaded"] = true
        saved["speak.selection"] = value.selection
        saved["speak.online"] = value.online
        saved["speak.input"] = value.input
        saved["speak.delete"] = value.deleteId
        saved["speak.finished"] = value.finished
        saved["speak.share"] = value.share?.url
        saved["speak.passphrase"] = value.share?.passphrase
        saved["speak.next"] = nextId
        saved["speak.effects"] =
            ArrayList(value.pending.map { "${it.id}\n${it.action.name}\n${it.argument}" })
    }

    private fun run(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutable.update {
                it.copy(error = error.localizedMessage ?: error.toString(), busy = false)
            }
        }
    }

    private fun enqueue(action: SpeakEngineAction, argument: String = "") {
        if (state.value.finished) return
        mutable.update {
            it.copy(pending = it.pending + SpeakEngineEffect(++nextId, action, argument))
        }
        persist()
    }

    fun fail(error: Exception) {
        mutable.update { it.copy(error = error.localizedMessage ?: error.toString()) }
    }

    fun consume(id: Long) {
        mutable.update { it.copy(pending = it.pending.filterNot { event -> event.id == id }) }
        persist()
    }

    fun selectSystem(engine: SpeakSystemEngine) {
        if (state.value.finished || state.value.busy) return
        mutable.update { it.copy(selection = GSON.toJson(SelectItem(engine.label, engine.name))) }
        persist()
    }

    fun selectHttp(id: Long) {
        if (state.value.finished || state.value.busy) return
        val engine = state.value.engines.find { it.id == id } ?: return
        mutable.update { it.copy(selection = id.toString()) }
        persist()
        if (engine.canLogin) enqueue(SpeakEngineAction.Login, id.toString())
    }

    fun login(id: Long) {
        if (state.value.engines.any { it.id == id && it.canLogin })
            enqueue(SpeakEngineAction.Login, id.toString())
    }

    fun edit(id: Long? = null) = enqueue(SpeakEngineAction.Edit, id?.toString() ?: "")

    fun local() = enqueue(SpeakEngineAction.ImportLocal)

    fun importResult(value: String) = enqueue(SpeakEngineAction.Import, value)

    fun export(all: Boolean) {
        val id = state.value.selection?.toLongOrNull()
        if (!all && (id == null || state.value.engines.none { it.id == id }))
            enqueue(SpeakEngineAction.SystemExport)
        else enqueue(SpeakEngineAction.Export, if (all) "" else id.toString())
    }

    suspend fun exportData(argument: String) = repository.export(argument.toLongOrNull())

    fun requestDelete(id: Long?) {
        mutable.update { it.copy(deleteId = id) }
        persist()
    }

    fun confirmDelete() {
        val id = state.value.deleteId ?: return
        requestDelete(null)
        run { repository.delete(id) }
    }

    fun importDefault() {
        run { repository.importDefault() }
    }

    fun clearCache() = enqueue(SpeakEngineAction.ClearCache)

    fun clearCacheData() {
        run {
            repository.clearCache()
            enqueue(SpeakEngineAction.CacheCleared)
        }
    }

    fun openOnline(value: Boolean) {
        mutable.update { it.copy(online = value) }
        persist()
    }

    fun input(value: String) {
        mutable.update { it.copy(input = value) }
        persist()
    }

    fun removeHistory(value: String) {
        run {
            historyMutex.withLock {
                val next = state.value.histories - value
                repository.saveHistories(next)
                mutable.update { it.copy(histories = next) }
            }
        }
    }

    fun confirmOnline() {
        val input = state.value.input
        openOnline(false)
        run {
            historyMutex.withLock {
                if (input.isAbsUrl() && input !in state.value.histories) {
                    val list = listOf(input) + state.value.histories
                    repository.saveHistories(list)
                    mutable.update { it.copy(histories = list) }
                }
            }
            enqueue(SpeakEngineAction.Import, input)
        }
    }

    fun exported(url: String) {
        mutable.update { it.copy(share = SpeakEngineShare(url)) }
        persist()
        run {
            val share = repository.share(url)
            if (state.value.share?.url == url) {
                mutable.update { it.copy(share = share.copy(passphrase = it.share?.passphrase)) }
                persist()
            }
        }
    }

    fun passphrase() {
        val url = state.value.share?.url ?: return
        run {
            val passphrase = repository.passphrase(url)
            if (state.value.share?.url == url) {
                mutable.update { it.copy(share = it.share?.copy(passphrase = passphrase)) }
                persist()
            }
        }
    }

    fun closeShare() {
        mutable.update { it.copy(share = null) }
        persist()
    }

    fun apply(general: Boolean) {
        if (state.value.busy || state.value.finished) return
        val selection = state.value.selection
        mutable.update { it.copy(busy = true) }
        run {
            repository.apply(selection, general)
            enqueue(SpeakEngineAction.Applied)
            mutable.update { it.copy(busy = false, finished = true) }
            persist()
        }
    }
}
