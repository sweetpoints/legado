package io.legado.app.data.repository

import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.webdav.Authorization
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.remote.RemoteBook
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.model.remote.RemoteLibraryConnection
import io.legado.app.model.remote.RemoteLibraryEntry
import java.util.UUID
import kotlinx.coroutines.*

internal interface RemoteLibraryStore {
    suspend fun connect(): RemoteLibraryConnection

    suspend fun list(connection: RemoteLibraryConnection, path: String): List<RemoteBook>

    suspend fun import(connection: RemoteLibraryConnection, entry: RemoteLibraryEntry)

    suspend fun importWithReceipt(
        connection: RemoteLibraryConnection,
        entry: RemoteLibraryEntry,
        accepted: suspend () -> Unit,
    ) {
        import(connection, entry)
        accepted()
    }

    fun close()
}

internal interface RemoteLibraryRepository {
    suspend fun connect(): RemoteLibraryConnection

    suspend fun list(
        connection: RemoteLibraryConnection,
        path: String? = null,
    ): List<RemoteLibraryEntry>

    suspend fun import(connection: RemoteLibraryConnection, entry: RemoteLibraryEntry)

    suspend fun importWithReceipt(
        connection: RemoteLibraryConnection,
        entry: RemoteLibraryEntry,
        accepted: suspend () -> Unit,
    ) {
        import(connection, entry)
        accepted()
    }

    suspend fun close()
}

/**
 * IO includes engine construction: RemoteBookWebDav performs its original mkdir during
 * initialization.
 */
internal class DefaultRemoteLibraryRepository(
    private val store: RemoteLibraryStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RemoteLibraryRepository {
    override suspend fun connect() = withContext(io) { store.connect() }

    override suspend fun list(connection: RemoteLibraryConnection, path: String?) =
        withContext(io) {
            store.list(connection, path ?: connection.root).map { entry ->
                RemoteLibraryEntry(
                    entry.path,
                    entry.filename,
                    entry.path,
                    entry.size,
                    entry.lastModify,
                    entry.contentType,
                    entry.isOnBookShelf,
                )
            }
        }

    override suspend fun import(connection: RemoteLibraryConnection, entry: RemoteLibraryEntry) =
        withContext(io) {
            currentCoroutineContext().ensureActive()
            require(!entry.directory) { "Cannot import a directory" }
            store.import(connection, entry)
        }

    override suspend fun importWithReceipt(
        connection: RemoteLibraryConnection,
        entry: RemoteLibraryEntry,
        accepted: suspend () -> Unit,
    ) =
        withContext(io) {
            currentCoroutineContext().ensureActive()
            require(!entry.directory) { "Cannot import a directory" }
            store.importWithReceipt(connection, entry, accepted)
        }

    override suspend fun close() = withContext(io + NonCancellable) { store.close() }
}

internal class AppRemoteLibraryStore : RemoteLibraryStore {
    private data class Owned(val id: String, val manager: RemoteBookWebDav)

    @Volatile private var owned: Owned? = null
    private val lock = Any()
    private var closed = false

    override suspend fun connect(): RemoteLibraryConnection {
        synchronized(lock) { check(!closed) { "Remote library session is closed" } }
        val serverId = AppConfig.remoteServerId
        val configuration = appDb.serverDao.get(serverId)?.getWebDavConfig()
        val default = configuration == null
        val manager =
            if (configuration != null)
                RemoteBookWebDav(configuration.url, Authorization(configuration), serverId)
            else AppWebDav.defaultBookWebDav ?: throw NoStackTraceException("webDav没有配置")
        currentCoroutineContext().ensureActive()
        val id = UUID.randomUUID().toString()
        synchronized(lock) {
            check(!closed) { "Remote library session is closed" }
            owned = Owned(id, manager)
        }
        return RemoteLibraryConnection(id, manager.rootBookUrl, default, manager.serverID)
    }

    private fun manager(connection: RemoteLibraryConnection) =
        owned?.takeIf { it.id == connection.id }?.manager
            ?: throw NoStackTraceException("远程书籍连接已失效，请刷新")

    override suspend fun list(connection: RemoteLibraryConnection, path: String) =
        manager(connection).getRemoteBookList(path)

    override suspend fun import(connection: RemoteLibraryConnection, entry: RemoteLibraryEntry) =
        importWithReceipt(connection, entry) {}

    override suspend fun importWithReceipt(
        connection: RemoteLibraryConnection,
        entry: RemoteLibraryEntry,
        accepted: suspend () -> Unit,
    ) {
        val manager = manager(connection)
        val uri =
            manager.downloadRemoteBook(
                RemoteBook(
                    entry.name,
                    entry.path,
                    entry.size,
                    entry.modified,
                    entry.type,
                    entry.onShelf,
                )
            )
        currentCoroutineContext().ensureActive()
        // Once local import is accepted, its metadata save stays paired with the original LocalBook
        // writes.
        withContext(NonCancellable) {
            LocalBook.importFiles(uri).forEach { book ->
                book.origin =
                    BookType.webDavTag +
                        CustomUrl(entry.path).putAttribute("serverID", manager.serverID).toString()
                book.save()
            }
            accepted()
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            owned = null
        }
    }
}
