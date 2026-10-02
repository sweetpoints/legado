package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.help.config.LocalConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class UpdateDialogRequest(val version: String, val body: String, val url: String,
    val fileName: String, val backupUrl: String? = null, val mirrorUrl: String? = null,
    val alternateMirrorUrl: String? = null, val size: Long = 0, val createdAt: Long = 0,
    val beta: Boolean = false)
interface UpdateDialogRepository {
    suspend fun load(id: String): UpdateDialogRequest
    suspend fun ignore(version: String)
}
class FileUpdateDialogRepository(context: Context) : UpdateDialogRepository {
    private val directory = File(context.applicationContext.filesDir, "update-dialog-requests")
    override suspend fun load(id: String): UpdateDialogRequest = withContext(Dispatchers.IO) {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        pending[id]?.await()
        GSON.fromJsonObject<UpdateDialogRequest>(AtomicFile(File(directory, "$id.json")).openRead()
            .bufferedReader().use { it.readText() }).getOrThrow()
    }
    override suspend fun ignore(version: String) = withContext(Dispatchers.IO) { LocalConfig.ignoreUpdateVersion = version }
    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val pending = ConcurrentHashMap<String, Deferred<Unit>>()
        /** Constructors enqueue writes; loading waits before enabling any update action. */
        fun stage(context: Context, request: UpdateDialogRequest): String {
            val id = UUID.randomUUID().toString()
            val directory = File(context.applicationContext.filesDir, "update-dialog-requests")
            val job = scope.async(start = CoroutineStart.LAZY) {
                try {
                    directory.mkdirs()
                    val file = AtomicFile(File(directory, "$id.json")); val output = file.startWrite()
                    try { output.write(GSON.toJson(request).toByteArray()); file.finishWrite(output) }
                    catch (error: Throwable) { file.failWrite(output); throw error }
                } finally { pending.remove(id) }
            }
            pending[id] = job; job.start(); return id
        }
    }
}
