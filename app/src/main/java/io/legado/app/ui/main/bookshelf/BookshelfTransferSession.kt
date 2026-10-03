package io.legado.app.ui.main.bookshelf

import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.parcelize.Parcelize

@Parcelize
data class BookshelfFileImport(val id: Long, val uri: String, val groupId: Long) : Parcelable

/** Request identity isolates stale worker progress and ActivityResult delivery from current UI. */
class BookshelfTransferSession(private val saved: SavedStateHandle) {
    private val progress = MutableStateFlow(-1)
    val addProgress = progress.asStateFlow()
    val pendingExport = saved.getStateFlow<String?>("shelf.export.path", null)
    val pendingFileImport = saved.getStateFlow<BookshelfFileImport?>("shelf.import.file", null)
    val pendingExportRequestId: String?
        get() = requestId("shelf.export.requestId", pendingExport.value != null)

    val pendingImportRequestId: String?
        get() = requestId("shelf.import.requestId", saved.get<Long>("shelf.import.group") != null)

    val launchedImportRequestId: String?
        get() = saved.get("shelf.import.launched")

    val launchedExportRequestId: String?
        get() = saved.get("shelf.export.launched")

    val launchedExportPath: String?
        get() = saved.get("shelf.export.launchedPath")

    val exportPickerInFlight: Boolean
        get() = launchedExportRequestId != null

    private var generation = 0

    @Synchronized
    fun beginAdd(): Int {
        generation++
        progress.value = 0
        return generation
    }

    @Synchronized
    fun progress(token: Int, count: Int) {
        if (token == generation) progress.value = count.coerceAtLeast(0)
    }

    @Synchronized
    fun finish(token: Int) {
        if (token == generation) {
            generation++
            progress.value = -1
        }
    }

    @Synchronized
    fun cancelAdd() {
        generation++
        progress.value = -1
    }

    fun exportReady(path: String): String =
        UUID.randomUUID().toString().also { requestId ->
            saved["shelf.export.requestId"] = requestId
            saved["shelf.export.path"] = path
        }

    fun exportLaunched(path: String, requestId: String) {
        if (pendingExport.value == path && pendingExportRequestId == requestId) {
            saved["shelf.export.launched"] = requestId
            saved["shelf.export.launchedPath"] = path
        }
    }

    fun exportReturned(path: String, requestId: String) {
        if (launchedExportRequestId != requestId || launchedExportPath != path) return
        saved.remove<String>("shelf.export.launched")
        saved.remove<String>("shelf.export.launchedPath")
        if (pendingExport.value == path && pendingExportRequestId == requestId) {
            saved.remove<String>("shelf.export.requestId")
            saved["shelf.export.path"] = null
        }
    }

    fun importRequested(groupId: Long): String =
        UUID.randomUUID().toString().also { requestId ->
            saved["shelf.import.requestId"] = requestId
            saved["shelf.import.group"] = groupId
        }

    fun importLaunched(requestId: String) {
        if (pendingImportRequestId == requestId) saved["shelf.import.launched"] = requestId
    }

    fun importReturned(requestId: String): Long? {
        if (launchedImportRequestId != requestId) return null
        saved.remove<String>("shelf.import.launched")
        if (pendingImportRequestId != requestId) return null
        saved.remove<String>("shelf.import.requestId")
        return saved.remove<Long>("shelf.import.group")
    }

    fun fileReady(uri: String, groupId: Long): BookshelfFileImport {
        val id = (saved.get<Long>("shelf.import.nextId") ?: 0L) + 1
        saved["shelf.import.nextId"] = id
        return BookshelfFileImport(id, uri, groupId).also { saved["shelf.import.file"] = it }
    }

    fun fileFinished(id: Long) {
        if (pendingFileImport.value?.id == id) saved["shelf.import.file"] = null
    }

    private fun requestId(key: String, required: Boolean): String? {
        if (!required) return null
        return saved.get<String>(key) ?: UUID.randomUUID().toString().also { saved[key] = it }
    }
}
