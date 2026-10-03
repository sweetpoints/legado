package io.legado.app.ui.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

enum class BottomBarSkinCatalogEffectType { Import, Assign, Export, Share, Changed, Exported }
data class BottomBarSkinCatalogEffect(val id: String = UUID.randomUUID().toString(), val type: BottomBarSkinCatalogEffectType,
    val name: String = "", val session: String? = null, val editName: String? = null, val path: String? = null)
data class BottomBarSkinCatalogState(val loaded: Boolean = false, val busy: Boolean = false,
    val names: List<String> = emptyList(), val active: String = "", val menu: String? = null, val delete: String? = null,
    val issue: BottomBarSkinCatalogIssue? = null, val effect: BottomBarSkinCatalogEffect? = null,
    val scroll: Int = 0, val offset: Int = 0, val previewRevision: Long = 0, val closeBlocked: Boolean = false)
class BottomBarSkinCatalogViewModel(private val repository: BottomBarSkinCatalogRepository,
    private val saved: SavedStateHandle, private val sizePx: Int
) : ViewModel() {
    private val mutable = MutableStateFlow(BottomBarSkinCatalogState(closeBlocked = saved.get<Boolean>("skinCatalog.closeBlocked") == true, menu = saved.get<String>("skinCatalog.menu"),
        delete = saved.get<String>("skinCatalog.delete"), scroll = saved.get<Int>("skinCatalog.scroll") ?: 0,
        offset = saved.get<Int>("skinCatalog.offset") ?: 0,
        effect = saved.get<String>("skinCatalog.effect")?.let { GSON.fromJsonObject<BottomBarSkinCatalogEffect>(it).getOrNull() }))
    val state: StateFlow<BottomBarSkinCatalogState> = mutable
    private var loadJob: Job? = null
    init {
        load()
        state.value.effect?.takeIf { it.type == BottomBarSkinCatalogEffectType.Changed }?.let {
            blockClose(); saved["skinCatalog.changeId"] = it.id
        }
        if (state.value.closeBlocked && state.value.effect == null) effect(BottomBarSkinCatalogEffect(type = BottomBarSkinCatalogEffectType.Changed))
        saved.get<String>("skinCatalog.work")?.let { resumeWork(it, saved.get<String>("skinCatalog.input").orEmpty()) }
    }
    fun load() {
        if (state.value.busy) return
        loadJob?.cancel(); loadJob = viewModelScope.launch {
            try {
                val data = repository.load(); currentCoroutineContext().ensureActive()
                mutable.value = state.value.copy(loaded = true, names = data.names, active = data.active, previewRevision = state.value.previewRevision + 1,
                    menu = state.value.menu?.takeIf { it in data.names }, delete = state.value.delete?.takeIf { it in data.names })
                if (state.value.menu == null) saved.remove<String>("skinCatalog.menu")
                if (state.value.delete == null) saved.remove<String>("skinCatalog.delete")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); mutable.value = state.value.copy(issue = BottomBarSkinCatalogIssue.Invalid) }
        }
    }
    private fun idle() = !state.value.busy && state.value.effect == null && !state.value.closeBlocked
    fun menu(name: String) {
        if (!idle() || name !in state.value.names) return
        saved["skinCatalog.menu"] = name; mutable.value = state.value.copy(menu = name)
    }
    fun cancelMenu() { saved.remove<String>("skinCatalog.menu"); mutable.value = state.value.copy(menu = null) }
    fun requestDelete(name: String) {
        if (!idle() || name !in state.value.names) return
        cancelMenu(); saved["skinCatalog.delete"] = name; mutable.value = state.value.copy(delete = name)
    }
    fun cancelDelete() { saved.remove<String>("skinCatalog.delete"); mutable.value = state.value.copy(delete = null) }
    fun activate(name: String) {
        if (!idle() || !state.value.loaded || name.isNotEmpty() && name !in state.value.names) return
        blockClose()
        operation { val active = repository.activate(name); currentCoroutineContext().ensureActive()
            mutable.value = state.value.copy(active = active); effect(BottomBarSkinCatalogEffect(type = BottomBarSkinCatalogEffectType.Changed)) }
    }
    fun confirmDelete() {
        val name = state.value.delete ?: return
        if (!idle()) return
        blockClose()
        operation { repository.delete(name); currentCoroutineContext().ensureActive(); cancelDelete()
            val data = repository.load(); currentCoroutineContext().ensureActive()
            mutable.value = state.value.copy(names = data.names, active = data.active, previewRevision = state.value.previewRevision + 1)
            effect(BottomBarSkinCatalogEffect(type = BottomBarSkinCatalogEffectType.Changed)) }
    }
    fun importPicker() {
        if (!idle()) return
        effect(BottomBarSkinCatalogEffect(type = BottomBarSkinCatalogEffectType.Import))
    }
    fun importResult(uri: String?) {
        if (saved.get<String>("skinCatalog.importTicket") == null) return
        saved.remove<String>("skinCatalog.importTicket")
        if (uri != null) resumeWork("import", uri)
    }
    fun edit(name: String) { if (idle() && name in state.value.names) { cancelMenu(); resumeWork("edit", name) } }
    fun export(name: String) { if (idle() && name in state.value.names) { cancelMenu(); resumeWork("export", name) } }
    fun share(name: String) { if (idle() && name in state.value.names) { cancelMenu(); resumeWork("share", name) } }
    private fun resumeWork(work: String, input: String) {
        if (state.value.busy) return
        saved["skinCatalog.work"] = work; saved["skinCatalog.input"] = input
        operation {
            var staged: BottomBarSkinStaged? = null
            try {
                val result = when (work) {
                    "import", "edit" -> {
                        staged = withContext(NonCancellable) { if (work == "import") repository.importZip(input) else repository.edit(input) }
                        currentCoroutineContext().ensureActive(); val value = checkNotNull(staged)
                        BottomBarSkinCatalogEffect(type = BottomBarSkinCatalogEffectType.Assign, name = value.name, session = value.session, editName = value.editName)
                    }
                    "export", "share" -> {
                        val path = repository.zip(input); currentCoroutineContext().ensureActive()
                        BottomBarSkinCatalogEffect(type = if (work == "export") BottomBarSkinCatalogEffectType.Export else BottomBarSkinCatalogEffectType.Share, name = input, path = path)
                    }
                    else -> error("Invalid operation")
                }
                effect(result); staged = null
                saved.remove<String>("skinCatalog.work"); saved.remove<String>("skinCatalog.input")
            } finally { staged?.let { value -> withContext(NonCancellable) { runCatching { repository.discard(value.session) } } } }
        }
    }
    private fun operation(block: suspend () -> Unit) {
        loadJob?.cancel(); mutable.value = state.value.copy(busy = true, issue = null)
        viewModelScope.launch {
            try { block(); currentCoroutineContext().ensureActive(); mutable.value = state.value.copy(busy = false) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive()
                saved.remove<String>("skinCatalog.work"); saved.remove<String>("skinCatalog.input")
                if (state.value.effect?.type != BottomBarSkinCatalogEffectType.Changed) unblockClose()
                mutable.value = state.value.copy(busy = false, issue = (error as? BottomBarSkinCatalogException)?.issue ?: BottomBarSkinCatalogIssue.Invalid) }
        }
    }
    private fun effect(value: BottomBarSkinCatalogEffect) {
        if (value.type == BottomBarSkinCatalogEffectType.Changed) saved["skinCatalog.changeId"] = value.id
        saved["skinCatalog.effect"] = GSON.toJson(value); mutable.value = state.value.copy(effect = value)
    }
    private fun blockClose() { saved["skinCatalog.closeBlocked"] = true; mutable.value = state.value.copy(closeBlocked = true) }
    private fun unblockClose() { saved.remove<Boolean>("skinCatalog.closeBlocked"); saved.remove<String>("skinCatalog.changeId"); mutable.value = state.value.copy(closeBlocked = false) }
    fun changedDelivered(id: String) { if (saved.get<String>("skinCatalog.changeId") == id) unblockClose() }
    fun delivered(id: String): BottomBarSkinCatalogEffect? {
        val value = state.value.effect?.takeIf { it.id == id } ?: return null
        if (value.type == BottomBarSkinCatalogEffectType.Import) saved["skinCatalog.importTicket"] = id
        if (value.type == BottomBarSkinCatalogEffectType.Export) saved["skinCatalog.exportTicket"] = id
        saved.remove<String>("skinCatalog.effect"); mutable.value = state.value.copy(effect = null); return value
    }
    fun exportResult(success: Boolean) {
        if (saved.get<String>("skinCatalog.exportTicket") == null) return
        saved.remove<String>("skinCatalog.exportTicket")
        if (success && state.value.effect == null) effect(BottomBarSkinCatalogEffect(type = BottomBarSkinCatalogEffectType.Exported))
    }
    fun deliveryFailed(value: BottomBarSkinCatalogEffect) {
        saved.remove<String>("skinCatalog.importTicket"); saved.remove<String>("skinCatalog.exportTicket")
        operation { value.session?.let { repository.discard(it) }; currentCoroutineContext().ensureActive(); mutable.value = state.value.copy(issue = BottomBarSkinCatalogIssue.Invalid) }
    }
    fun clearIssue() { mutable.value = state.value.copy(issue = null) }
    fun scroll(index: Int, offset: Int) { saved["skinCatalog.scroll"] = index.coerceAtLeast(0); saved["skinCatalog.offset"] = offset.coerceAtLeast(0) }
    suspend fun preview(name: String) = repository.preview(name, sizePx)
    suspend fun releasePending() {
        state.value.effect?.session?.let { repository.discard(it) }
    }
    fun stop() { viewModelScope.cancel() }
}
