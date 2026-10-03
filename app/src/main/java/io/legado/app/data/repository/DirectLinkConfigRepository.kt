package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DirectLinkDraft(
    val uploadUrl: String = "",
    val downloadRule: String = "",
    val summary: String = "",
    val compress: Boolean = false,
    val expiry: String = "0",
) {
    fun issue(): DirectLinkIssue? =
        when {
            uploadUrl.isBlank() -> DirectLinkIssue.Upload
            downloadRule.isBlank() -> DirectLinkIssue.Download
            summary.isBlank() -> DirectLinkIssue.Summary
            expiry.toIntOrNull()?.let { it in 0..SourceSharePassphrase.MAX_EXPIRY_DAYS } != true ->
                DirectLinkIssue.Expiry
            else -> null
        }

    fun rule(): DirectLinkUpload.Rule {
        check(issue() == null)
        return DirectLinkUpload.Rule(uploadUrl, downloadRule, summary, compress, expiry.toInt())
    }

    companion object {
        fun from(rule: DirectLinkUpload.Rule) =
            DirectLinkDraft(
                rule.uploadUrl,
                rule.downloadUrlRule,
                rule.summary,
                rule.compress,
                rule.expiryDate.toString(),
            )
    }
}

enum class DirectLinkIssue {
    Upload,
    Download,
    Summary,
    Expiry,
    Clipboard,
}

data class DirectLinkSession(
    val id: String,
    val draft: DirectLinkDraft,
    val revision: Long = 1,
    val result: String? = null,
    val finished: Boolean = false,
)

interface DirectLinkConfigRepository {
    suspend fun open(id: String?): DirectLinkSession

    suspend fun defaults(): List<DirectLinkDraft>

    suspend fun write(value: DirectLinkSession)

    suspend fun save(draft: DirectLinkDraft)

    suspend fun test(draft: DirectLinkDraft): String

    suspend fun release(id: String)
}

/** Complete scripts/results live in a private file; only its UUID belongs in SavedState. */
class AppDirectLinkConfigRepository(
    context: Context,
    private val directory: File = File(context.filesDir, "compose-direct-link"),
    private val load: () -> DirectLinkUpload.Rule = DirectLinkUpload::getRule,
    private val store: (DirectLinkUpload.Rule) -> Unit = DirectLinkUpload::putConfig,
    private val presets: () -> List<DirectLinkUpload.Rule> = { DirectLinkUpload.defaultRules },
    private val upload: suspend (DirectLinkUpload.Rule) -> String = {
        DirectLinkUpload.upLoad("test.json", "{}", "application/json", it)
    },
) : DirectLinkConfigRepository {
    private fun file(id: String): AtomicFile {
        require(UUID.fromString(id).toString() == id)
        return AtomicFile(File(directory, "$id.json"))
    }

    private fun gate(id: String) =
        gates[(file(id).baseFile.canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size]

    private fun read(id: String): DirectLinkSession =
        file(id).openRead().use {
            GSON.fromJsonObject<DirectLinkSession>(it.readBytes().toString(Charsets.UTF_8))
                .getOrThrow()
                .also { row -> check(row.id == id) }
        }

    private fun put(value: DirectLinkSession) {
        check(directory.isDirectory || directory.mkdirs())
        val atomic = file(value.id)
        val stream = atomic.startWrite()
        try {
            stream.write(GSON.toJson(value).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    override suspend fun open(id: String?): DirectLinkSession =
        withContext(Dispatchers.IO) {
            if (id != null) gate(id).withLock { read(id) }
            else {
                val session =
                    DirectLinkSession(UUID.randomUUID().toString(), DirectLinkDraft.from(load()))
                gate(session.id).withLock { put(session) }
                session
            }
        }

    override suspend fun defaults() =
        withContext(Dispatchers.IO) { presets().map { DirectLinkDraft.from(it) } }

    override suspend fun write(value: DirectLinkSession) =
        withContext(Dispatchers.IO) {
            gate(value.id).withLock {
                val current =
                    read(value.id) // Released sessions cannot be recreated by a late writer.
                if (value.revision > current.revision) put(value)
            }
        }

    override suspend fun save(draft: DirectLinkDraft) =
        withContext(Dispatchers.IO) { configGate.withLock { store(draft.rule()) } }

    override suspend fun test(draft: DirectLinkDraft) =
        withContext(Dispatchers.IO) { upload(draft.rule()) }

    override suspend fun release(id: String) =
        withContext(Dispatchers.IO) { gate(id).withLock { file(id).delete() } }

    companion object {
        private val gates = Array(64) { Mutex() }
        private val configGate = Mutex()
    }
}
