package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Only this UUID is kept in SavedState; titles, author sets and search text can be large. */
data class ReadingHistoryConfirmation(
    val identity: ReadingHistoryIdentity? = null,
    val author: String? = null,
    val chooseAuthor: Boolean = false,
)

data class ReadingHistoryNavigation(val id: String, val destination: ReadingHistoryDestination)

data class ReadingHistoryDraft(
    val revision: Long = 0,
    val query: String = "",
    val confirmation: ReadingHistoryConfirmation? = null,
    val navigation: ReadingHistoryNavigation? = null,
)

interface ReadingHistoryDraftRepository {
    suspend fun create(): String

    suspend fun read(ticket: String): ReadingHistoryDraft

    suspend fun write(ticket: String, draft: ReadingHistoryDraft)

    suspend fun release(ticket: String)
}

class FileReadingHistoryDraftRepository(context: Context) : ReadingHistoryDraftRepository {
    private val directory = File(context.applicationContext.cacheDir, "reading-history-drafts")

    private fun file(ticket: String): File {
        require(UUID.fromString(ticket).toString() == ticket)
        return File(directory, "$ticket.json")
    }

    private suspend fun <T> locked(ticket: String, action: (AtomicFile) -> T): T =
        withContext(IO) {
            val file = file(ticket)
            locks[(file.absolutePath.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
                action(AtomicFile(file))
            }
        }

    override suspend fun create(): String =
        withContext(IO) {
            val ticket = UUID.randomUUID().toString()
            directory.mkdirs()
            locked(ticket) { writeFile(it, ReadingHistoryDraft()) }
            ticket
        }

    override suspend fun read(ticket: String) =
        locked(ticket) { decode(JSONObject(String(it.readFully(), Charsets.UTF_8))) }

    override suspend fun write(ticket: String, draft: ReadingHistoryDraft) =
        locked(ticket) { file ->
            // Shared locking and existence fencing also protect against an old owner's final flush.
            check(file.baseFile.exists()) { "History draft is no longer available" }
            val current = decode(JSONObject(String(file.readFully(), Charsets.UTF_8)))
            if (draft.revision > current.revision) writeFile(file, draft)
        }

    override suspend fun release(ticket: String) = locked(ticket) { it.delete() }

    private fun writeFile(file: AtomicFile, draft: ReadingHistoryDraft) {
        val json = JSONObject().put("revision", draft.revision).put("query", draft.query)
        draft.confirmation?.let { confirmation ->
            json.put(
                "confirmation",
                JSONObject().put("choose", confirmation.chooseAuthor).apply {
                    confirmation.identity?.let {
                        put("name", it.name)
                        put("author", it.author)
                    }
                    confirmation.author?.let { put("remove", it) }
                },
            )
        }
        draft.navigation?.let { navigation ->
            json.put(
                "navigation",
                JSONObject()
                    .put("id", navigation.id)
                    .put("kind", navigation.destination.kind.name)
                    .put("key", navigation.destination.key)
                    .put("name", navigation.destination.name),
            )
        }
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun decode(json: JSONObject): ReadingHistoryDraft =
        ReadingHistoryDraft(
            json.getLong("revision"),
            json.getString("query"),
            json.optJSONObject("confirmation")?.let { c ->
                ReadingHistoryConfirmation(
                    if (c.has("name"))
                        ReadingHistoryIdentity(c.getString("name"), c.getString("author"))
                    else null,
                    if (c.has("remove")) c.getString("remove") else null,
                    c.optBoolean("choose"),
                )
            },
            json.optJSONObject("navigation")?.let { n ->
                ReadingHistoryNavigation(
                    n.getString("id"),
                    ReadingHistoryDestination(
                        ReadingHistoryReader.valueOf(n.getString("kind")),
                        n.getString("key"),
                        n.getString("name"),
                    ),
                )
            },
        )

    companion object {
        private val locks = Array(64) { Mutex() }
    }
}
