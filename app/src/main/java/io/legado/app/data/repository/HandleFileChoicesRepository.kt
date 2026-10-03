package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import io.legado.app.help.DirectLinkUpload
import io.legado.app.utils.FileUtils
import io.legado.app.utils.checkWrite
import io.legado.app.utils.externalFiles
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isSameOrDescendantOf
import io.legado.app.utils.writeBytes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/** Immutable choice data. Labels remain in the Compose resource layer. */
data class HandleFileChoice(
    val title: String,
    val value: Int,
)

data class HandleFileInput(
    val mode: Int = 0,
    val title: String? = null,
    val extensions: List<String> = emptyList(),
    val otherActions: List<HandleFileChoice> = emptyList(),
    val fileName: String? = null,
    val contentType: String? = null,
    val value: String? = null,
    val sourceFileName: String? = null,
)

data class HandleFileSeed(
    val input: HandleFileInput,
    val payload: Any? = null,
    val otherActionsJson: String? = null,
)

enum class HandleFileIssue {
    EmptyDirectory,
    InvalidDirectory,
    EmptyImage,
    InvalidImage,
    PayloadMissing,
}

class HandleFileIssueException(val issue: HandleFileIssue) : IllegalArgumentException(issue.name)

internal fun handleFileChoiceValues(mode: Int): List<Int> =
    when (mode) {
        2 -> listOf(0, 112)
        0 -> listOf(0, 10, 112)
        1 -> listOf(1, 11)
        3 -> listOf(111, 0, 10, 112)
        4 -> listOf(4, 1, 11, 113)
        else -> emptyList()
    }

internal fun handleFileMimeTypes(
    extensions: List<String>,
    resolve: (String) -> String?,
): List<String> {
    if (extensions.isEmpty()) return listOf("*/*")
    val types = linkedSetOf<String>()
    extensions.forEach { extension ->
        when (extension) {
            "*" -> types += "*/*"
            "txt",
            "xml" -> types += "text/*"
            "js" -> {
                types += "application/javascript"
                types += "text/javascript"
            }
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

    suspend fun saveRecorded(
        directory: String,
        name: String,
        bytes: ByteArray,
        receipt: suspend (String) -> Unit,
    ): String {
        val result = save(directory, name, bytes)
        withContext(NonCancellable) { receipt(result) }
        return result
    }

    suspend fun uploadRecorded(
        name: String,
        bytes: ByteArray,
        contentType: String,
        receipt: suspend (String) -> Unit,
    ): String {
        val result = upload(name, bytes, contentType)
        withContext(NonCancellable) { receipt(result) }
        return result
    }

    suspend fun uploadFileRecorded(
        name: String,
        sourceFileName: String,
        bytes: ByteArray,
        contentType: String,
        receipt: suspend (String) -> Unit,
    ): String = uploadRecorded(name, bytes, contentType, receipt)
}

/** Retains the established document-tree/file and DirectLinkUpload algorithms on IO. */
class AppHandleFileChoicesRepository(
    context: Context,
    private val external: (File) -> Boolean = ::handleFileExternalStorage,
    private val uploadFile: suspend (String, Any, String) -> String = { name, data, type ->
        DirectLinkUpload.upLoad(name, data, type)
    },
) : HandleFileChoicesRepository {
    private val context = context.applicationContext

    override suspend fun mimeTypes(extensions: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            handleFileMimeTypes(extensions) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
            }
        }

    override suspend fun manual(text: String, image: Boolean): String =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) {
                throw HandleFileIssueException(
                    if (image) HandleFileIssue.EmptyImage else HandleFileIssue.EmptyDirectory
                )
            }
            if (image && text.startsWith("http", ignoreCase = true)) {
                return@withContext Uri.parse(text).toString()
            }
            val file = File(text)
            val valid =
                file.exists() &&
                    external(file) &&
                    if (image) {
                        file.isFile && file.canRead()
                    } else {
                        file.isDirectory && file.checkWrite()
                    }
            if (!valid) {
                throw HandleFileIssueException(
                    if (image) HandleFileIssue.InvalidImage else HandleFileIssue.InvalidDirectory
                )
            }
            Uri.fromFile(file).toString()
        }

    override suspend fun save(directory: String, name: String, bytes: ByteArray): String =
        saveRecorded(directory, name, bytes) {}

    override suspend fun saveRecorded(
        directory: String,
        name: String,
        bytes: ByteArray,
        receipt: suspend (String) -> Unit,
    ): String =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val uri = Uri.parse(directory)
            val result =
                if (uri.isContentScheme()) {
                    val documentTree = checkNotNull(DocumentFile.fromTreeUri(context, uri))
                    documentTree.findFile(name)?.delete()
                    val document = checkNotNull(documentTree.createFile("", name))
                    document.writeBytes(context, bytes)
                    document.uri.toString()
                } else {
                    val root = File(uri.path ?: directory)
                    val target = FileUtils.createFileIfNotExist(root, name)
                    target.writeBytes(bytes)
                    Uri.fromFile(target).toString()
                }
            // Record success inside IO before cancellation can interrupt the return to Main.
            withContext(NonCancellable) { receipt(result) }
            result
        }

    override suspend fun upload(name: String, bytes: ByteArray, contentType: String): String =
        uploadRecorded(name, bytes, contentType) {}

    override suspend fun uploadRecorded(
        name: String,
        bytes: ByteArray,
        contentType: String,
        receipt: suspend (String) -> Unit,
    ): String =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val result = uploadFile(name, bytes, contentType)
            // The network remains cancellable; only its accepted result receipt is protected.
            withContext(NonCancellable) { receipt(result) }
            result
        }
}

private fun handleFileExternalStorage(path: File): Boolean {
    if (path.isSameOrDescendantOf(appCtx.externalFiles.parentFile!!)) return false
    try {
        if (Environment.isExternalStorageEmulated(path)) return true
    } catch (_: IllegalArgumentException) {
        // Unsupported paths are checked against removable storage below.
    }
    try {
        if (Environment.isExternalStorageRemovable(path)) return true
    } catch (_: IllegalArgumentException) {
        // Neither platform storage category accepted this path.
    }
    return false
}
