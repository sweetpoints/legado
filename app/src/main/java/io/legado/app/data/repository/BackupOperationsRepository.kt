package io.legado.app.data.repository

import android.content.Context
import androidx.core.net.toUri
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.ImportOldData
import io.legado.app.help.storage.Restore
import io.legado.app.model.backup.BackupRestoreFiles
import io.legado.app.utils.FileDoc
import io.legado.app.utils.checkWrite
import kotlinx.coroutines.*

internal interface BackupOperationsStore {
    suspend fun writableTree(path: String): Boolean
    suspend fun backup(path: String?, uploadWebDav: Boolean)
    suspend fun restoreFiles(): BackupRestoreFiles
    suspend fun restoreWebDav(name: String)
    suspend fun restoreLocal(uri: String)
    suspend fun importOld(uri: String)
}
internal interface BackupOperationsRepository : BackupOperationsStore
internal class DefaultBackupOperationsRepository(private val store: BackupOperationsStore,
    private val io: CoroutineDispatcher = Dispatchers.IO) : BackupOperationsRepository {
    override suspend fun writableTree(path: String) = withContext(io) { store.writableTree(path) }
    override suspend fun backup(path: String?, uploadWebDav: Boolean) = withContext(io) {
        store.backup(path, uploadWebDav); currentCoroutineContext().ensureActive()
    }
    override suspend fun restoreFiles() = withContext(io) {
        val result = store.restoreFiles(); currentCoroutineContext().ensureActive()
        if (result.names.isEmpty()) throw NoStackTraceException("Web dav no back up file")
        result.copy(names = result.names.toList())
    }
    override suspend fun restoreWebDav(name: String) = withContext(io) { store.restoreWebDav(name); currentCoroutineContext().ensureActive() }
    override suspend fun restoreLocal(uri: String) = withContext(io) { store.restoreLocal(uri); currentCoroutineContext().ensureActive() }
    override suspend fun importOld(uri: String) = withContext(io) { store.importOld(uri); currentCoroutineContext().ensureActive() }
}
internal class AppBackupOperationsStore(context: Context) : BackupOperationsStore {
    private val application = context.applicationContext
    override suspend fun writableTree(path: String) = FileDoc.fromDir(path).checkWrite()
    override suspend fun backup(path: String?, uploadWebDav: Boolean) { Backup.backupLocked(application, path, uploadWebDav) }
    override suspend fun restoreFiles(): BackupRestoreFiles {
        val names = AppWebDav.getBackupNames()
        return BackupRestoreFiles(names.toList(), AppWebDav.isJianGuoYun && names.size > 700)
    }
    override suspend fun restoreWebDav(name: String) { AppWebDav.restoreWebDav(name) }
    override suspend fun restoreLocal(uri: String) { Restore.restore(application, uri.toUri()) }
    override suspend fun importOld(uri: String) { ImportOldData.importUri(application, uri.toUri()) }
}
