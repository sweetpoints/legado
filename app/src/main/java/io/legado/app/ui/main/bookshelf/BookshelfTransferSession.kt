package io.legado.app.ui.main.bookshelf

import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.parcelize.Parcelize

@Parcelize data class BookshelfFileImport(val id: Long, val uri: String, val groupId: Long) : Parcelable

/** Request identity isolates stale worker progress and ActivityResult delivery from current UI. */
class BookshelfTransferSession(private val saved: SavedStateHandle) {
    private val progress = MutableStateFlow(-1)
    val addProgress = progress.asStateFlow()
    val pendingExport = saved.getStateFlow<String?>("shelf.export.path", null)
    val pendingFileImport = saved.getStateFlow<BookshelfFileImport?>("shelf.import.file", null)
    private var generation = 0
    @Synchronized fun beginAdd(): Int { generation++; progress.value = 0; return generation }
    @Synchronized fun progress(token: Int, count: Int) { if (token == generation) progress.value = count.coerceAtLeast(0) }
    @Synchronized fun finish(token: Int) { if (token == generation) { generation++; progress.value = -1 } }
    @Synchronized fun cancelAdd() { generation++; progress.value = -1 }
    fun exportReady(path: String) { saved["shelf.export.path"] = path }
    fun exportLaunched(path: String) { if (pendingExport.value == path) saved["shelf.export.path"] = null }
    fun importRequested(groupId: Long) { saved["shelf.import.group"] = groupId }
    fun importReturned(): Long? = saved.remove<Long>("shelf.import.group")
    fun fileReady(uri: String, groupId: Long): BookshelfFileImport {
        val id = (saved.get<Long>("shelf.import.nextId") ?: 0L) + 1
        saved["shelf.import.nextId"] = id
        return BookshelfFileImport(id, uri, groupId).also { saved["shelf.import.file"] = it }
    }
    fun fileFinished(id: Long) { if (pendingFileImport.value?.id == id) saved["shelf.import.file"] = null }
}
