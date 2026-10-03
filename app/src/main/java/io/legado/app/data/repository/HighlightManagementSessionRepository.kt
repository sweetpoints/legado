package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

enum class HighlightManagementAction { Add, Edit, Import, Groups, Export, Share, Refresh, Copy }
data class HighlightManagementEffect(val token: String, val action: HighlightManagementAction,
    val id: Long? = null, val rules: List<HighlightManagedRule> = emptyList(), val text: String? = null)
data class HighlightManagementDraft(val filter: String? = null, val selection: Set<String> = emptySet(),
    val deletion: Set<String> = emptySet(), val deletionName: String? = null,
    val effects: List<HighlightManagementEffect> = emptyList(), val exporting: String? = null,
    val exportResult: String? = null, val revision: Long = 0)
interface HighlightManagementSessionRepository {
    suspend fun read(ticket: String): HighlightManagementDraft?
    suspend fun write(ticket: String, draft: HighlightManagementDraft)
    suspend fun release(ticket: String)
}

/** Full group names, selection, confirmations and export payloads never enter SavedState. */
class FileHighlightManagementSessionRepository(context: Context,
    private val directory: File = File(context.applicationContext.filesDir,"highlight-management-sessions")) : HighlightManagementSessionRepository {
    private fun body(ticket: String): AtomicFile {
        require(runCatching { UUID.fromString(ticket) }.isSuccess)
        return AtomicFile(File(directory,"$ticket.json"))
    }
    private fun gate(ticket: String) = gates[(body(ticket).baseFile.canonicalPath.hashCode() and Int.MAX_VALUE)%gates.size]
    private fun fence(ticket: String) = AtomicFile(File(directory,"$ticket.closed"))
    private fun closed(ticket: String) = fence(ticket).baseFile.let { it.exists() || File(it.path+".bak").exists() }
    private fun readBody(ticket: String): HighlightManagementDraft? {
        if (closed(ticket)) return null
        val file=body(ticket)
        if (!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) return null
        return file.openRead().bufferedReader().use { GSON.fromJsonObject<HighlightManagementDraft>(it.readText()).getOrThrow() }
    }
    override suspend fun read(ticket: String)=withContext(Dispatchers.IO){gate(ticket).withLock{readBody(ticket)}}
    override suspend fun write(ticket: String,draft: HighlightManagementDraft)=withContext(Dispatchers.IO+NonCancellable){gate(ticket).withLock {
        check(!closed(ticket)) { "Highlight session is closed" }
        if ((readBody(ticket)?.revision ?: -1)>draft.revision) return@withLock
        directory.mkdirs();val file=body(ticket);val stream=file.startWrite()
        try { stream.write(GSON.toJson(draft).toByteArray());file.finishWrite(stream) }
        catch(error:Throwable){file.failWrite(stream);throw error}
    }}
    override suspend fun release(ticket: String)=withContext(Dispatchers.IO+NonCancellable){gate(ticket).withLock {
        directory.mkdirs()
        if (!closed(ticket)) {
            val file=fence(ticket);val stream=file.startWrite()
            try {stream.write(1);file.finishWrite(stream)} catch(error:Throwable){file.failWrite(stream);throw error}
        }
        val file=body(ticket);file.delete()
        check(listOf("", ".bak", ".new").none { File(file.baseFile.path+it).exists() })
    }}
    companion object { private val gates=Array(64){Mutex()} }
}

interface HighlightManagementTransferRepository {
    suspend fun export(effect: HighlightManagementEffect): ByteArray
    suspend fun share(effect: HighlightManagementEffect): File
}
class FileHighlightManagementTransferRepository(context: Context) : HighlightManagementTransferRepository {
    private val cache = context.applicationContext.cacheDir
    override suspend fun export(effect: HighlightManagementEffect)=withContext(Dispatchers.IO) {
        require(effect.action==HighlightManagementAction.Export)
        highlightManagementJson(effect.rules).toByteArray()
    }
    override suspend fun share(effect: HighlightManagementEffect)=withContext(Dispatchers.IO) {
        require(effect.action==HighlightManagementAction.Share)
        File.createTempFile("highlightRules_", ".json", cache).also { file ->
            try { file.writeText(highlightManagementJson(effect.rules)) }
            catch(error:Throwable) { file.delete();throw error }
        }
        // The platform recipient reads this grant after dispatch. Keep the legacy cache-file lifetime.
    }
}
