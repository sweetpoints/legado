package io.legado.app.ui.book.source.manage

import android.content.Context
import android.util.AtomicFile
import io.legado.app.utils.GSON
import java.io.File
import java.util.UUID

/** Android saved state contains an opaque UUID. Large drafts and checked IDs stay private. */
internal data class SourceManagerSession(
    val query: String = "",
    val selected: Set<String> = emptySet(),
    val sort: BookSourceSort = BookSourceSort.Default,
    val ascending: Boolean = true,
    val domain: Boolean = false,
    val status: String = "",
    val dialog: SourceManagerDialog? = null,
    val draft: String = "",
    val dialogKeys: List<String> = emptyList(),
    val pendingOperation: Boolean = false,
    val effectId: String? = null,
    val effectAction: String? = null,
    val effectKey: String = "",
    val effectKeys: List<String> = emptyList(),
    val exportPath: String? = null,
    val exportName: String? = null,
    val exportMime: String? = null,
    val receipts: Set<String> = emptySet(),
)

internal interface SourceManagerSessionStorage {
    fun read(): SourceManagerSession

    fun write(session: SourceManagerSession)
}

internal class SourceManagerSessionStore(context: Context, token: String) :
    SourceManagerSessionStorage {
    private val directory = File(context.filesDir, "source-manager-sessions").apply { mkdirs() }
    private val file = AtomicFile(File(directory, "${UUID.fromString(token)}.json"))

    override fun read(): SourceManagerSession {
        if (!file.baseFile.exists()) return SourceManagerSession()
        return file.openRead().bufferedReader().use { reader ->
            GSON.fromJson(reader, SourceManagerSession::class.java)
        }
    }

    override fun write(session: SourceManagerSession) {
        val output = file.startWrite()
        try {
            output.write(GSON.toJson(session).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }
}
