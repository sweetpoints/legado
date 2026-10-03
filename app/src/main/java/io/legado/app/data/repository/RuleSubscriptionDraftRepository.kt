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

data class RuleSubscriptionEditor(val id: Long? = null, val newId: Long,
    val name: String = "", val url: String = "", val type: Int = 0, val automatic: Boolean = false,
    val interval: String = "0", val silent: Boolean = false, val silentEnabled: Boolean = false,
    val originalInterval: Int = 0) {
    fun input() = RuleSubscriptionInput(id,name,url,type,automatic,interval.toIntOrNull() ?: 0,silent)
}
data class RuleSubscriptionOpen(val token: String, val type: Int, val url: String)
data class RuleSubscriptionDraft(val editor: RuleSubscriptionEditor? = null,
    val pendingSave: RuleSubscriptionSave? = null, val navigation: RuleSubscriptionOpen? = null, val revision: Long = 0)
interface RuleSubscriptionDraftRepository {
    suspend fun read(ticket: String): RuleSubscriptionDraft?
    suspend fun write(ticket: String, draft: RuleSubscriptionDraft)
    suspend fun save(ticket: String, draft: RuleSubscriptionDraft): RuleSubscriptionDraft
    suspend fun release(ticket: String)
}

/** Large input/URL and commit receipts stay outside the saved-state Bundle. */
class FileRuleSubscriptionDraftRepository(context: Context, private val rules: RuleSubscriptionRepository,
    private val directory: File = File(context.applicationContext.filesDir,"rule-subscription-drafts"),
    private val beforeWrite: (RuleSubscriptionDraft) -> Unit = {}) : RuleSubscriptionDraftRepository {
    private fun body(ticket: String): AtomicFile {
        require(runCatching { UUID.fromString(ticket) }.isSuccess)
        return AtomicFile(File(directory,"$ticket.json"))
    }
    private fun fence(ticket: String) = AtomicFile(File(body(ticket).baseFile.parentFile,"$ticket.closed"))
    private fun isClosed(ticket: String): Boolean {
        val file = fence(ticket).baseFile
        return file.exists() || File(file.path + ".bak").exists()
    }
    private fun gate(ticket: String): Mutex = gates[(body(ticket).baseFile.canonicalPath.hashCode() and Int.MAX_VALUE)%gates.size]
    private fun readBody(ticket: String): RuleSubscriptionDraft? {
        val body=body(ticket)
        if (!body.baseFile.exists() && !File(body.baseFile.path+".bak").exists()) return null
        return body.openRead().bufferedReader().use { GSON.fromJsonObject<RuleSubscriptionDraft>(it.readText()).getOrThrow() }
    }
    private fun writeBody(ticket: String,draft: RuleSubscriptionDraft) {
        check(!isClosed(ticket)) { "Subscription session is closed" }
        beforeWrite(draft);directory.mkdirs();val body=body(ticket);val stream=body.startWrite()
        try { stream.write(GSON.toJson(draft).toByteArray());body.finishWrite(stream) }
        catch(error:Throwable) { body.failWrite(stream);throw error }
    }
    override suspend fun read(ticket: String) = withContext(Dispatchers.IO) { gate(ticket).withLock { readBody(ticket) } }
    override suspend fun write(ticket: String,draft: RuleSubscriptionDraft) = withContext(Dispatchers.IO+NonCancellable) {
        gate(ticket).withLock { if ((readBody(ticket)?.revision ?: -1)<=draft.revision) writeBody(ticket,draft) }
    }
    override suspend fun save(ticket: String,draft: RuleSubscriptionDraft): RuleSubscriptionDraft = withContext(Dispatchers.IO+NonCancellable) {
        gate(ticket).withLock {
            check(!isClosed(ticket)) { "Subscription session is closed" }
            var current=readBody(ticket)?.takeIf { it.revision>draft.revision || it.pendingSave!=null } ?: draft
            val editor=current.editor ?: return@withLock current
            if (current.pendingSave!=null) rules.recoverSave(current.pendingSave!!)
            else rules.saveJournaled(editor.input(),editor.newId) { plan ->
                current=current.copy(pendingSave=plan,revision=current.revision+1)
                writeBody(ticket,current)
            }
            val completed=current.copy(editor=null,pendingSave=null,revision=current.revision+1)
            writeBody(ticket,completed);completed
        }
    }
    override suspend fun release(ticket: String) = withContext(Dispatchers.IO+NonCancellable) {
        gate(ticket).withLock {
            directory.mkdirs();val fence=fence(ticket)
            if (!isClosed(ticket)) {
                val stream=fence.startWrite();try { stream.write(1);fence.finishWrite(stream) } catch(error:Throwable) { fence.failWrite(stream);throw error }
            }
            val body=body(ticket);body.delete()
            check(listOf("", ".bak", ".new").none { File(body.baseFile.path + it).exists() }) { "Subscription draft cleanup failed" }
        }
    }
    companion object { private val gates=Array(64) { Mutex() } }
}
