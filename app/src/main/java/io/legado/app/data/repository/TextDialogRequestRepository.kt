package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class TextDialogRequest(val title: String, val content: String, val mode: String = "TEXT", val time: Long = 0,
    val autoClose: Boolean = false, val showToc: Boolean = false)
interface TextDialogRequestRepository { suspend fun load(id: String): TextDialogRequest }
class FileTextDialogRequestRepository(context: Context) : TextDialogRequestRepository {
    private val directory = File(context.applicationContext.filesDir, "text-dialog-requests")
    override suspend fun load(id: String): TextDialogRequest = withContext(Dispatchers.IO) {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        pending[id]?.await()
        GSON.fromJsonObject<TextDialogRequest>(AtomicFile(File(directory, "$id.json")).openRead().bufferedReader().use { it.readText() }).getOrThrow()
    }
    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val pending = ConcurrentHashMap<String, Deferred<Unit>>()
        /** Compatibility constructors only enqueue IO; the VM waits before exposing content. */
        fun stage(context: Context, request: TextDialogRequest): String {
            val id = UUID.randomUUID().toString(); val directory = File(context.applicationContext.filesDir, "text-dialog-requests")
            val job = scope.async(start = CoroutineStart.LAZY) {
                try {
                    val file = AtomicFile(File(directory, "$id.json")); val output = file.startWrite()
                    try { output.write(GSON.toJson(request).toByteArray()); file.finishWrite(output) }
                    catch (error: Throwable) { file.failWrite(output); throw error }
                } finally { pending.remove(id) }
            }
            pending[id] = job; job.start(); return id
        }
    }
}
