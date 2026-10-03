package io.legado.app.ui.main.explore

import android.content.Context
import android.util.AtomicFile
import androidx.annotation.Keep
import io.legado.app.utils.GSON
import java.io.File
import java.util.UUID

@Keep
internal data class ExploreHomeSession(
    val revision: Long = 0,
    val query: String = "",
    val expandedUrl: String? = null,
    val values: Map<String, Map<String, String>> = emptyMap(),
    val deleteUrl: String? = null,
    val errorText: String? = null,
    val effect: ExploreHomeEffect? = null,
    val receipts: Set<String> = emptySet(),
    val pendingOperation: Boolean = false,
)

internal interface ExploreHomeSessionStorage {
    fun read(): ExploreHomeSession

    fun write(snapshot: ExploreHomeSession): Boolean

    fun delete()
}

internal class FileExploreHomeSessionStorage(
    context: Context,
    token: String,
    private val beforeWrite: (() -> Unit)? = null,
) : ExploreHomeSessionStorage {
    private val directory = File(context.filesDir, "explore-home-sessions")
    private val identity = UUID.fromString(token).toString()
    private val file = AtomicFile(File(directory, "$identity.json"))
    private val ownerFile = AtomicFile(File(directory, "$identity.owner"))
    private val closedFile = AtomicFile(File(directory, "$identity.closed"))
    private val owner = UUID.randomUUID().toString()
    private var claimed = false

    private fun exists(file: AtomicFile): Boolean =
        file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()

    private fun gate(): Any =
        gates[(file.baseFile.canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size]

    private fun closed(): Boolean = exists(closedFile)

    private fun owns(): Boolean =
        exists(ownerFile) && ownerFile.openRead().bufferedReader().use { it.readText() } == owner

    private fun readBody(): ExploreHomeSession? {
        if (!exists(file)) return null
        return file.openRead().bufferedReader().use {
            checkNotNull(GSON.fromJson(it, ExploreHomeSession::class.java)) { "发现会话内容为空" }
        }
    }

    private fun writeBytes(target: AtomicFile, bytes: ByteArray) {
        check(directory.isDirectory || directory.mkdirs())
        val output = target.startWrite()
        try {
            output.write(bytes)
            target.finishWrite(output)
        } catch (failure: Throwable) {
            target.failWrite(output)
            throw failure
        }
    }

    private fun claim() {
        writeBytes(ownerFile, owner.toByteArray(Charsets.UTF_8))
        claimed = true
    }

    override fun read(): ExploreHomeSession =
        synchronized(gate()) {
            check(!closed()) { "发现会话已结束" }
            if (claimed) check(owns()) { "发现会话已由新的页面恢复" }
            // Parse first: a corrupt/unreadable body must remain untouched, including ownership.
            val body = readBody() ?: ExploreHomeSession()
            if (!claimed) claim()
            body
        }

    override fun write(snapshot: ExploreHomeSession): Boolean =
        synchronized(gate()) {
            if (closed() || (claimed && !owns())) return@synchronized false
            val latest = readBody()
            if ((latest?.revision ?: -1) >= snapshot.revision) return@synchronized false
            if (!claimed) claim()
            beforeWrite?.invoke()
            writeBytes(file, GSON.toJson(snapshot).toByteArray(Charsets.UTF_8))
            true
        }

    override fun delete() =
        synchronized(gate()) {
            // A recreated store owns the same UUID now. Old owner cleanup must not delete it.
            if (!claimed || !owns() || closed()) return@synchronized
            writeBytes(closedFile, byteArrayOf(1))
            file.delete()
            ownerFile.delete()
        }

    private companion object {
        val gates = Array(64) { Any() }
    }
}
