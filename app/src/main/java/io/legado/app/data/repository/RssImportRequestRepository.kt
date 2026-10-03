package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Returned editor text stays out of SavedState/Bundle; only this durable request's ID is saved. */
data class RssImportRefreshRequest(
    val key: String? = null,
    val code: String? = null,
    val ids: List<Long>? = null,
    val openManual: Boolean? = null,
    val automatic: Boolean? = null,
)

interface RssImportRequestRepository {
    suspend fun write(request: RssImportRefreshRequest): String

    suspend fun read(id: String): RssImportRefreshRequest?

    suspend fun remove(id: String)
}

class AppRssImportRequestRepository(context: Context) : RssImportRequestRepository {
    private val context = context.applicationContext

    private fun file(id: String): AtomicFile {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return AtomicFile(File(context.cacheDir, "rss-import-requests/$id.json"))
    }

    override suspend fun write(request: RssImportRefreshRequest): String =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val target = file(id)
            val stream = target.startWrite()
            try {
                stream.write(GSON.toJson(request).toByteArray())
                target.finishWrite(stream)
            } catch (error: Throwable) {
                target.failWrite(stream)
                throw error
            }
            id
        }

    override suspend fun read(id: String): RssImportRefreshRequest? =
        withContext(Dispatchers.IO) {
            val target = file(id)
            if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists())
                return@withContext null
            GSON.fromJsonObject<RssImportRefreshRequest>(
                    target.openRead().bufferedReader().use { it.readText() }
                )
                .getOrThrow()
        }

    override suspend fun remove(id: String) = withContext(Dispatchers.IO) { file(id).delete() }
}
