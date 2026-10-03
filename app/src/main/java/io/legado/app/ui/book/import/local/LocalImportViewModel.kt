package io.legado.app.ui.book.import.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.Book
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class LocalImportGroupPrompt(val name: String, val available: Boolean)

internal data class LocalImportArchivePrompt(
    val fileId: String,
    val names: List<String>,
    val selected: String? = null,
)

internal data class LocalImportUiState(
    val loading: Boolean = true,
    val importing: Boolean = false,
    val recursive: Boolean = false,
    val query: String = "",
    val sort: Int = 0,
    val path: String = "",
    val canGoBack: Boolean = false,
    val rows: List<LocalImportRow> = emptyList(),
    val selected: Set<String> = emptySet(),
    val storage: String? = null,
    val storageHelp: String = "",
    val script: String = "",
    val storagePrompt: Boolean = false,
    val showStorage: Boolean = false,
    val editScript: Boolean = false,
    val group: LocalImportGroupPrompt? = null,
    val archive: LocalImportArchivePrompt? = null,
    val pending: LocalImportNative? = null,
    val recovery: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

internal fun sortedImportRows(
    rows: List<LocalImportRow>,
    query: String,
    sort: Int,
): List<LocalImportRow> {
    val comparator =
        when (sort) {
            2 -> compareBy<LocalImportRow>({ !it.directory }, { -it.modified })
            1 -> compareBy({ !it.directory }, { -it.size })
            else -> compareBy { !it.directory }
        } then compareBy(AlphanumComparator) { it.name }
    return rows.filter { query.isBlank() || it.name.contains(query) }.sortedWith(comparator)
}

internal class LocalImportViewModel(
    private val repository: LocalImportOperations,
    private val session: LocalImportSession,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LocalImportUiState())
    val state = mutableState.asStateFlow()
    private val ownership = LocalImportOwnership()
    private var owner = 0L
    private var listing: Job? = null
    private var reading: Job? = null
    private val accepted = mutableSetOf<Job>()
    private var root: FileDoc? = null
    private var directories = emptyList<FileDoc>()
    private var files = emptyList<LocalImportFile>()
    private var groupFiles = emptyList<FileDoc>()
    private var initialized = false
    private val nativeOwners = mutableMapOf<String, Long>()

    fun initialize() {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            try {
                val settings = repository.settings()
                val restored = session.load()
                val path = restored.root ?: settings.root ?: settings.storage
                mutableState.update {
                    it.copy(
                        query = restored.query,
                        selected = restored.selected,
                        sort = settings.sort,
                        storage = settings.storage,
                        storageHelp = settings.storageHelp,
                        script = settings.fileNameScript,
                        storagePrompt = settings.storage.isNullOrBlank(),
                        recovery = restored.pending != null || restored.importNonce != null,
                    )
                }
                if (path != null) {
                    session.update { it.copy(root = path) }
                    if (path.startsWith("content:"))
                        restoreDirectory(path, restored.directories, restored.recursive)
                    else if (restored.pending == null)
                        requestNative(LocalImportNativeKind.Permission, path)
                } else if (!state.value.storagePrompt) requestFolder()
                else mutableState.update { it.copy(loading = false) }
            } catch (error: Exception) {
                failure(error)
            }
        }
    }

    private suspend fun restoreDirectory(path: String, children: List<String>, recursive: Boolean) {
        root = repository.root(path)
        directories = children.map { repository.root(it) }
        loadDirectory(recursive, clearSelection = false)
    }

    private fun acceptedWrite(block: suspend () -> Unit): Job {
        val job = viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    block()
                } catch (error: Exception) {
                    failure(error)
                }
            }
        }
        accepted += job
        job.invokeOnCompletion { accepted -= job }
        return job
    }

    private fun failure(error: Exception) {
        if (error is CancellationException) throw error
        mutableState.update {
            it.copy(
                loading = false,
                importing = false,
                error = error.localizedMessage,
                pending = null,
            )
        }
    }

    private fun publishRows() {
        mutableState.update {
            it.copy(rows = sortedImportRows(files.map { file -> file.row }, it.query, it.sort))
        }
    }

    private fun loadDirectory(recursive: Boolean, clearSelection: Boolean = true) {
        val document = directories.lastOrNull() ?: root ?: return
        owner = ownership.begin()
        val request = owner
        listing?.cancel()
        reading?.cancel()
        files = emptyList()
        mutableState.update {
            it.copy(
                loading = true,
                recursive = recursive,
                path =
                    (listOfNotNull(root) + directories).joinToString("/", postfix = "/") { doc ->
                        doc.name
                    },
                canGoBack = directories.isNotEmpty(),
                rows = emptyList(),
                selected = if (clearSelection) emptySet() else it.selected,
                archive = null,
                group = null,
                pending = null,
            )
        }
        val directorySnapshot = directories.map { it.uri.toString() }
        listing = viewModelScope.launch {
            try {
                session.update {
                    it.copy(
                        directories = directorySnapshot,
                        recursive = recursive,
                        selected = if (clearSelection) emptySet() else it.selected,
                        pending = if (clearSelection) null else it.pending,
                    )
                }
                if (recursive)
                    repository.scan(document) { additions ->
                        withContext(Dispatchers.Main.immediate) {
                            ownership.publish(request) {
                                files = files + additions
                                publishRows()
                            }
                        }
                    }
                else {
                    val result = repository.list(document)
                    ownership.publish(request) {
                        files = result
                        publishRows()
                    }
                }
                ownership.publish(request) { mutableState.update { it.copy(loading = false) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (ownership.current(request)) failure(error)
            }
        }
    }

    fun scan() = loadDirectory(recursive = true)

    fun cancelScan() {
        ownership.begin()
        listing?.cancel()
        mutableState.update { it.copy(loading = false) }
    }

    fun back(): Boolean {
        if (directories.isEmpty()) return false
        directories = directories.dropLast(1)
        loadDirectory(false)
        return true
    }

    fun search(value: String) {
        mutableState.update { it.copy(query = value) }
        publishRows()
        acceptedWrite { session.update { it.copy(query = value) } }
    }

    fun sort(value: Int) {
        mutableState.update { it.copy(sort = value) }
        publishRows()
        acceptedWrite { repository.setSort(value) }
    }

    private fun selectable() =
        state.value.rows.filter { !it.directory && !it.onShelf }.mapTo(HashSet()) { it.id }

    private fun selection(value: Set<String>) {
        mutableState.update { it.copy(selected = value) }
        acceptedWrite { session.update { it.copy(selected = value) } }
    }

    fun all(value: Boolean) =
        selection(if (value) state.value.selected + selectable() else emptySet())

    fun invert() =
        selection(
            state.value.selected.let { selected ->
                (selected - selectable()) + (selectable() - selected)
            }
        )

    fun click(id: String) {
        val file = files.firstOrNull { it.row.id == id } ?: return
        when {
            file.row.directory -> {
                directories += file.document
                loadDirectory(false)
            }
            file.row.onShelf -> read(file)
            else ->
                selection(
                    if (id in state.value.selected) state.value.selected - id
                    else state.value.selected + id
                )
        }
    }

    fun dismissMessage() {
        mutableState.update { it.copy(error = null, message = null) }
    }

    fun showStorage(value: Boolean) {
        mutableState.update { it.copy(showStorage = value) }
    }

    fun editScript(value: Boolean) {
        mutableState.update { it.copy(editScript = value) }
    }

    fun saveScript(value: String) {
        acceptedWrite {
            repository.setScript(value)
            mutableState.update { it.copy(script = value, editScript = false) }
        }
    }

    fun storagePromptAccepted() {
        mutableState.update { it.copy(storagePrompt = false) }
        requestNative(LocalImportNativeKind.Storage)
    }

    fun skipStoragePrompt() {
        mutableState.update { it.copy(storagePrompt = false) }
        if (root == null) requestFolder()
    }

    fun requestFolder() = requestNative(LocalImportNativeKind.Folder)

    fun requestStorage() {
        showStorage(false)
        requestNative(LocalImportNativeKind.Storage)
    }

    private fun requestNative(
        kind: LocalImportNativeKind,
        argument: String? = null,
        book: Book? = null,
    ) {
        val request = owner
        acceptedWrite {
            if (kind == LocalImportNativeKind.Read && !ownership.current(request))
                return@acceptedWrite
            val receipt = LocalImportNative(UUID.randomUUID().toString(), kind, argument, book)
            session.update { it.copy(pending = receipt) }
            if (kind == LocalImportNativeKind.Read && !ownership.current(request))
                return@acceptedWrite
            nativeOwners[receipt.nonce] = request
            mutableState.update { it.copy(pending = receipt, recovery = false) }
        }
    }

    suspend fun claimNative(nonce: String): LocalImportNative? {
        var claimed: LocalImportNative? = null
        session.update {
            val pending = it.pending
            if (pending == null || pending.nonce != nonce || pending.claimed) it
            else if (
                pending.kind == LocalImportNativeKind.Read &&
                    !ownership.current(nativeOwners[nonce] ?: -1)
            )
                it
            else {
                claimed = pending
                it.copy(pending = pending.copy(claimed = true))
            }
        }
        if (claimed != null) mutableState.update { it.copy(pending = null) }
        return claimed
    }

    fun nativeInterrupted() {
        mutableState.update { it.copy(pending = null, recovery = true) }
    }

    fun nativeComplete(nonce: String) {
        acceptedWrite {
            session.update { if (it.pending?.nonce == nonce) it.copy(pending = null) else it }
        }
    }

    fun permissionResult(nonce: String, granted: Boolean) {
        acceptedWrite {
            val saved = session.load()
            val pending = saved.pending ?: return@acceptedWrite
            if (
                pending.nonce != nonce ||
                    pending.kind != LocalImportNativeKind.Permission ||
                    !pending.claimed
            )
                return@acceptedWrite
            session.update { it.copy(pending = null) }
            if (granted)
                restoreDirectory(
                    requireNotNull(pending.argument),
                    saved.directories,
                    saved.recursive,
                )
            else {
                mutableState.update { it.copy(loading = false) }
                requestFolder()
            }
        }
    }

    fun pickedFolder(nonce: String?, value: String?) {
        acceptedWrite {
            val pending = session.load().pending ?: return@acceptedWrite
            if (
                nonce == null ||
                    pending.nonce != nonce ||
                    pending.kind != LocalImportNativeKind.Folder ||
                    !pending.claimed
            )
                return@acceptedWrite
            ownership.begin()
            listing?.cancel()
            reading?.cancel()
            session.update { it.copy(pending = null) }
            if (value == null) return@acceptedWrite
            repository.setRoot(value)
            session.update {
                it.copy(root = value, directories = emptyList(), selected = emptySet())
            }
            directories = emptyList()
            if (value.startsWith("content:")) {
                root = repository.root(value)
                loadDirectory(false)
            } else requestNative(LocalImportNativeKind.Permission, value)
        }
    }

    fun pickedStorage(nonce: String?, value: String?) {
        acceptedWrite {
            val pending = session.load().pending ?: return@acceptedWrite
            if (
                nonce == null ||
                    pending.nonce != nonce ||
                    pending.kind != LocalImportNativeKind.Storage ||
                    !pending.claimed
            )
                return@acceptedWrite
            session.update { it.copy(pending = null) }
            if (value != null) {
                repository.setStorage(value)
                mutableState.update { it.copy(storage = value, showStorage = false) }
            }
            if (root == null) {
                if (value == null) requestFolder()
                else {
                    repository.setRoot(value)
                    session.update { it.copy(root = value, directories = emptyList()) }
                    if (value.startsWith("content:")) restoreDirectory(value, emptyList(), false)
                    else requestNative(LocalImportNativeKind.Permission, value)
                }
            }
        }
    }

    fun privateStorage() {
        acceptedWrite {
            val value = repository.privateStorage()
            mutableState.update { it.copy(storage = value, showStorage = false) }
        }
    }

    fun retryRecovery() {
        acceptedWrite {
            val saved = session.load()
            mutableState.update { it.copy(recovery = false) }
            if (saved.importNonce != null) {
                // An accepted Room/parser request is never replayed. A new explicit import uses
                // a fresh selection and receipt after the user reviews its current shelf state.
                session.update {
                    it.copy(importNonce = null, importAccepted = false, importedIds = emptySet())
                }
                loadDirectory(state.value.recursive)
            } else
                saved.pending?.let { pending ->
                    requestNative(pending.kind, pending.argument, pending.book)
                }
        }
    }

    fun dismissRecovery() {
        acceptedWrite {
            session.update { it.copy(pending = null, importNonce = null) }
            mutableState.update { it.copy(recovery = false) }
        }
    }

    fun importSelection() {
        if (state.value.importing) return
        val selected =
            files
                .filter {
                    it.row.id in state.value.selected && !it.row.directory && !it.row.onShelf
                }
                .map { it.document }
        if (selected.isEmpty()) return
        val groupName = (directories.lastOrNull() ?: root)?.name
        groupFiles = selected
        if (selected.size < 2 || state.value.recursive || groupName.isNullOrBlank())
            importFiles(selected, null)
        else
            viewModelScope.launch {
                val request = owner
                try {
                    val available = repository.canAddGroup()
                    ownership.publish(request) {
                        mutableState.update {
                            it.copy(group = LocalImportGroupPrompt(groupName, available))
                        }
                    }
                } catch (error: Exception) {
                    failure(error)
                }
            }
    }

    fun confirmGroup(useGroup: Boolean) {
        val prompt = state.value.group ?: return
        mutableState.update { it.copy(group = null) }
        importFiles(groupFiles, prompt.name.takeIf { useGroup && prompt.available })
    }

    private fun importFiles(selected: List<FileDoc>, groupName: String?) {
        val request = owner
        val nonce = UUID.randomUUID().toString()
        mutableState.update { it.copy(importing = true) }
        acceptedWrite {
            session.update {
                it.copy(importNonce = nonce, importAccepted = true, importedIds = emptySet())
            }
            val result = repository.importFiles(selected, groupName)
            mutableState.update { it.copy(importing = false) }
            session.update { it.copy(importNonce = nonce, importedIds = result.importedIds) }
            ownership.publish(request) {
                files = files.map { file ->
                    if (file.row.id in result.importedIds)
                        file.copy(row = file.row.copy(onShelf = true))
                    else file
                }
                mutableState.update {
                    it.copy(
                        selected = emptySet(),
                        importing = false,
                        message =
                            when {
                                result.groupError != null -> "创建目录分组失败: ${result.groupError}"
                                result.importedIds.size == result.requestedFiles -> "添加书架成功"
                                else ->
                                    "成功添加 ${result.importedBooks} 本书，${result.requestedFiles - result.importedIds.size} 个文件未完整导入"
                            },
                    )
                }
                publishRows()
            }
            session.update {
                it.copy(importNonce = null, importAccepted = false, selected = emptySet())
            }
        }
    }

    fun deleteSelection() {
        val selected = files.filter { it.row.id in state.value.selected }.map { it.document }
        val request = owner
        acceptedWrite {
            repository.deleteFiles(selected)
            ownership.publish(request) { loadDirectory(state.value.recursive) }
        }
    }

    private fun read(file: LocalImportFile) {
        if (reading?.isActive == true) return
        val request = owner
        reading = viewModelScope.launch {
            try {
                if (ArchiveUtils.isArchive(file.row.name)) {
                    val names = repository.archiveEntries(file.document)
                    if (!ownership.current(request)) return@launch
                    if (names.isEmpty()) error("压缩包内没有支持的书籍")
                    mutableState.update {
                        it.copy(archive = LocalImportArchivePrompt(file.row.id, names))
                    }
                    if (names.size == 1) chooseArchive(names.single())
                } else {
                    val book = repository.readBook(file.document) ?: return@launch
                    currentCoroutineContext().ensureActive()
                    if (ownership.current(request))
                        requestNative(LocalImportNativeKind.Read, book = book)
                }
            } catch (error: Exception) {
                if (ownership.current(request)) failure(error)
            }
        }
    }

    fun chooseArchive(name: String) {
        val prompt = state.value.archive ?: return
        val request = owner
        viewModelScope.launch {
            try {
                val book = repository.archiveBook(name)
                if (!ownership.current(request)) return@launch
                if (book != null) {
                    mutableState.update { it.copy(archive = null) }
                    requestNative(LocalImportNativeKind.Read, book = book)
                } else mutableState.update { it.copy(archive = prompt.copy(selected = name)) }
            } catch (error: Exception) {
                failure(error)
            }
        }
    }

    fun dismissArchive() {
        mutableState.update { it.copy(archive = null) }
    }

    fun importArchive() {
        val prompt = state.value.archive ?: return
        val name = prompt.selected ?: return
        val document = files.firstOrNull { it.row.id == prompt.fileId }?.document ?: return
        val request = owner
        mutableState.update { it.copy(archive = null, importing = true) }
        acceptedWrite {
            val book = repository.importArchive(document, name)
            mutableState.update { it.copy(importing = false) }
            ownership.publish(request) {
                mutableState.update { it.copy(importing = false) }
                if (book != null) requestNative(LocalImportNativeKind.Read, book = book)
            }
        }
    }

    override fun onCleared() {
        ownership.begin()
        val pending = accepted.toList() + listOfNotNull(listing, reading)
        cleanupScope.launch {
            pending.forEach { it.join() }
            runCatching { session.close() }
        }
        super.onCleared()
    }

    private companion object {
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
