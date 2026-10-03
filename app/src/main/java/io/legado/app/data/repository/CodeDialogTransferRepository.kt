package io.legado.app.data.repository

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Compatible with the native code editor transfer protocol, without UI dependencies. */
interface CodeDialogTransferRepository {
    suspend fun write(text: String): String

    suspend fun read(path: String): String

    suspend fun delete(vararg paths: String?)
}

class FileCodeDialogTransferRepository(context: Context) : CodeDialogTransferRepository {
    private val directory = context.applicationContext.cacheDir

    private fun checked(path: String): File {
        val file = File(path).canonicalFile
        require(file.parentFile == directory.canonicalFile && file.name.startsWith("code-text-")) {
            "无效的代码文件"
        }
        return file
    }

    override suspend fun write(text: String): String =
        withContext(Dispatchers.IO + NonCancellable) {
            val file = File(directory, "code-text-${UUID.randomUUID()}.txt")
            try {
                file.writeText(text, Charsets.UTF_8)
                file.absolutePath
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }

    override suspend fun read(path: String): String =
        withContext(Dispatchers.IO) {
            checked(path).readText(Charsets.UTF_8)
        }

    override suspend fun delete(vararg paths: String?) =
        withContext(Dispatchers.IO + NonCancellable) {
            paths.filterNotNull().distinct().forEach { runCatching { checked(it).delete() } }
            Unit
        }
}
