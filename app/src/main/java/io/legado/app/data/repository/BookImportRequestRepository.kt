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
data class BookImportRefreshRequest(
    val key: String? = null,
    val code: String? = null,
    val ids: List<Long>? = null,
    val openManual: Boolean? = null,
    val automatic: Boolean? = null,
)

interface BookImportRequestRepository {
    suspend fun write(request: BookImportRefreshRequest): String

    suspend fun read(id: String): BookImportRefreshRequest?

    suspend fun remove(id: String)
}

class AppBookImportRequestRepository(context: Context) : BookImportRequestRepository {
    private val context = context.applicationContext

    private fun file(id: String): AtomicFile {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        val directory = File(context.filesDir, "book-import-requests")
        check(directory.isDirectory || directory.mkdirs()) { "无法保存导入请求" }
        return AtomicFile(File(directory, "$id.json"))
    }

    override suspend fun write(request: BookImportRefreshRequest): String =
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

    override suspend fun read(id: String): BookImportRefreshRequest? =
        withContext(Dispatchers.IO) {
            val target = file(id)
            if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists())
                return@withContext null
            GSON.fromJsonObject<BookImportRefreshRequest>(
                    target.openRead().bufferedReader().use { it.readText() }
                )
                .getOrThrow()
        }

    override suspend fun remove(id: String) = withContext(Dispatchers.IO) { file(id).delete() }
}
