package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import io.legado.app.help.DirectLinkUpload
import io.legado.app.utils.*
import kotlinx.coroutines.*
import java.io.File

/** Immutable choice data. Labels remain in the Compose resource layer. */
data class HandleFileChoice(val title: String, val value: Int)
data class HandleFileInput(val mode: Int = 0, val title: String? = null,
    val extensions: List<String> = emptyList(), val otherActions: List<HandleFileChoice> = emptyList(),
    val fileName: String? = null, val contentType: String? = null, val value: String? = null)
data class HandleFileSeed(
    val input: HandleFileInput,
    val payload: Any? = null,
    val otherActionsJson: String? = null,
)
enum class HandleFileIssue { EmptyDirectory, InvalidDirectory, EmptyImage, InvalidImage, PayloadMissing }
class HandleFileIssueException(val issue: HandleFileIssue) : IllegalArgumentException(issue.name)

internal fun handleFileChoiceValues(mode: Int): List<Int> = when (mode) {
    2 -> listOf(0, 112)
    0 -> listOf(0, 10, 112)
    1 -> listOf(1, 11)
    3 -> listOf(111, 0, 10, 112)
    4 -> listOf(4, 1, 11, 113)
    else -> emptyList()
}
internal fun handleFileMimeTypes(extensions: List<String>, resolve: (String) -> String?): List<String> {
    val types = linkedSetOf<String>()
    if (extensions.isEmpty()) types += "*/*" else extensions.forEach { extension ->
        when (extension) {
            "*" -> types += "*/*"
            "txt", "xml" -> types += "text/*"
            "js" -> { types += "application/javascript"; types += "text/javascript" }
            else -> types += resolve(extension) ?: "application/octet-stream"
        }
    }
    return types.toList()
}
interface HandleFileChoicesRepository {
    suspend fun mimeTypes(extensions: List<String>): List<String>
    suspend fun manual(text: String, image: Boolean): String
    suspend fun save(directory: String, name: String, bytes: ByteArray): String
    suspend fun upload(name: String, bytes: ByteArray, contentType: String): String
}
/** Retains the established document-tree/file and DirectLinkUpload algorithms on IO. */
class AppHandleFileChoicesRepository(context: Context,
    private val external: (File) -> Boolean = ::handleFileExternalStorage,
    private val uploadFile: suspend (String, Any, String) -> String = { name, data, type -> DirectLinkUpload.upLoad(name, data, type) }
) : HandleFileChoicesRepository {
    private val context = context.applicationContext
    override suspend fun mimeTypes(extensions: List<String>) = withContext(Dispatchers.IO) {
        handleFileMimeTypes(extensions) { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
    }
    override suspend fun manual(text: String, image: Boolean): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) throw HandleFileIssueException(if (image) HandleFileIssue.EmptyImage else HandleFileIssue.EmptyDirectory)
        if (image && text.startsWith("http", true)) return@withContext Uri.parse(text).toString()
        val file = File(text)
        val valid = file.exists() && external(file) && if (image) file.isFile && file.canRead() else file.isDirectory && file.checkWrite()
        if (!valid) throw HandleFileIssueException(if (image) HandleFileIssue.InvalidImage else HandleFileIssue.InvalidDirectory)
        Uri.fromFile(file).toString()
    }
    override suspend fun save(directory: String, name: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(directory)
        if (uri.isContentScheme()) {
            val doc = checkNotNull(DocumentFile.fromTreeUri(context, uri))
            doc.findFile(name)?.delete()
            checkNotNull(doc.createFile("", name)).also { it.writeBytes(context, bytes) }.uri.toString()
        } else {
            val root = File(uri.path ?: directory)
            val target = FileUtils.createFileIfNotExist(root, name)
            target.writeBytes(bytes); Uri.fromFile(target).toString()
        }
    }
    override suspend fun upload(name: String, bytes: ByteArray, contentType: String): String = withContext(Dispatchers.IO) {
        uploadFile(name, bytes, contentType)
    }
}
private fun handleFileExternalStorage(path: File): Boolean {
    if (path.isSameOrDescendantOf(splitties.init.appCtx.externalFiles.parentFile!!)) return false
    try { if (Environment.isExternalStorageEmulated(path)) return true } catch (_: IllegalArgumentException) {}
    try { if (Environment.isExternalStorageRemovable(path)) return true } catch (_: IllegalArgumentException) {}
    return false
}
