package io.legado.app.data.repository

import android.content.Context
import androidx.core.net.toUri
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Captured by the settings owner before I/O; workers never read the active mutable preset. */
data class ReaderBackgroundExportSnapshot(
    val configJson: String,
    val name: String,
    val textFont: String,
    val titleFont: String,
    val backgrounds: List<String>,
)

interface ReaderBackgroundFilesRepository {
    suspend fun export(snapshot: ReaderBackgroundExportSnapshot, directory: String): String
    /** Returns the imported preset as JSON; applying it belongs to the resumed UI owner. */
    suspend fun importFile(uri: String): String
    suspend fun importUrl(url: String): String
    /** Returns a stored background filename without modifying the active preset. */
    suspend fun storeBackground(uri: String): String
}

class AppReaderBackgroundFilesRepository(context: Context) : ReaderBackgroundFilesRepository {
    private val context = context.applicationContext

    override suspend fun export(snapshot: ReaderBackgroundExportSnapshot, directory: String): String =
        withContext(Dispatchers.IO) {
            val staging = File(context.cacheDir, "read-config-${java.util.UUID.randomUUID()}")
            check(staging.mkdirs()) { "无法创建导出目录" }
            try {
                val zip = packReaderBackgroundArchive(snapshot, staging) { path ->
                    val source = FileDoc.fromFile(path)
                    source.openInputStream().getOrNull()?.let { source.name to it }
                }
                currentCoroutineContext().ensureActive()
                val fileName = if (snapshot.name.isBlank()) "readConfig.zip" else "${snapshot.name}.zip"
                val targetDirectory = FileDoc.fromDir(directory)
                targetDirectory.find(fileName)?.delete()
                val target = targetDirectory.createFileIfNotExist(fileName)
                target.openOutputStream().getOrThrow().use { output -> zip.inputStream().use { it.copyTo(output) } }
                fileName
            } finally {
                staging.deleteRecursively()
            }
        }

    override suspend fun importFile(uri: String): String = withContext(Dispatchers.IO) {
        decode(uri.toUri().readBytes(context))
    }

    override suspend fun importUrl(url: String): String = withContext(Dispatchers.IO) {
        val bytes = okHttpClient.newCallResponseBody { url(url) }.bytes()
        decode(bytes)
    }

    private suspend fun decode(bytes: ByteArray): String = importLock.withLock {
        File(context.externalFiles, "font").mkdirs()
        File(context.externalFiles, "bg").mkdirs()
        // The existing archive reader uses a shared staging directory. Serialize its use.
        GSON.toJson(ReadBookConfig.import(bytes))
    }

    override suspend fun storeBackground(uri: String): String = withContext(Dispatchers.IO) {
        val parsed = uri.toUri()
        val directory = File(context.externalFiles, "bg").apply { check(exists() || mkdirs()) }
        val temporary = File.createTempFile("background-", ".part", directory)
        try {
            val filename = if (parsed.scheme?.lowercase() in listOf("http", "https")) {
                val analyzed = AnalyzeUrl(uri)
                val url = analyzed.urlNoQuery
                val response = okHttpClient.newCallResponse(0) { addHeaders(analyzed.headerMap); url(url) }
                val type = response.header("Content-Type") ?: "image/jpeg"
                val suffix = when {
                    url.contains(".9.png", true) -> ".9.png"
                    type.contains("png", true) -> ".png"
                    type.contains("gif", true) -> ".gif"
                    type.contains("webp", true) -> ".webp"
                    else -> ".jpg"
                }
                response.body.byteStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                MD5Utils.md5Encode(url) + suffix
            } else {
                val source = FileDoc.fromFile(uri)
                val suffix = if (source.name.contains(".9.png", true)) ".9.png" else "." + source.name.substringAfterLast('.')
                val hash = source.openInputStream().getOrThrow().use(MD5Utils::md5Encode)
                source.openInputStream().getOrThrow().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                hash + suffix
            }
            currentCoroutineContext().ensureActive()
            val destination = File(directory, filename)
            check(temporary.renameTo(destination)) { "保存背景图片失败" }
            filename
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        val importLock = Mutex()
    }
}
