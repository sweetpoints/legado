package io.legado.app.data.file

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import io.legado.app.help.http.*
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.*
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * IO-only image preparation for settings; no preference is published before complete bytes exist.
 */
internal class SettingsImageInstaller(context: Context, private val directory: String) {
    private val application = context.applicationContext

    init {
        require(directory.matches(Regex("[A-Za-z0-9_-]{1,64}")))
    }

    suspend fun stage(uri: String): String {
        val value = Uri.parse(uri)
        if (value.scheme?.lowercase() in listOf("http", "https")) {
            val analyze = AnalyzeUrl(uri, coroutineContext = currentCoroutineContext())
            val url = analyze.urlNoQuery
            okHttpClient
                .newCallResponse(0) {
                    addHeaders(analyze.headerMap)
                    url(url)
                }
                .use { response ->
                    val type = response.header("Content-Type") ?: "image/jpeg"
                    val suffix =
                        if (url.contains(".9.png", true)) ".9.png"
                        else
                            when {
                                type.contains("png", true) -> ".png"
                                type.contains("gif", true) -> ".gif"
                                type.contains("webp", true) -> ".webp"
                                else -> ".jpg"
                            }
                    return response.body.byteStream().use {
                        install(MD5Utils.md5Encode(url) + suffix, it)
                    }
                }
        }
        val source = FileDoc.fromUri(value, false)
        val suffix =
            if (source.name.contains(".9.png", true)) ".9.png"
            else "." + source.name.substringAfterLast('.')
        val name = source.openInputStream().getOrThrow().use { MD5Utils.md5Encode(it) } + suffix
        return source.openInputStream().getOrThrow().use { install(name, it) }
    }

    private suspend fun install(name: String, input: InputStream): String {
        val folder = File(application.externalFiles, directory)
        check(folder.isDirectory || folder.mkdirs())
        val file = File(folder, name)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            val bytes = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(bytes)
                if (count < 0) break
                output.write(bytes, 0, count)
            }
            currentCoroutineContext().ensureActive()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
        return file.absolutePath
    }
}

/** Same canonical direct-parent ownership rule as the original welcome settings. */
internal fun isStoredSettingsImage(root: File, directory: String, path: String?): Boolean =
    !path.isNullOrEmpty() &&
        runCatching { File(path).canonicalFile.parentFile == File(root, directory).canonicalFile }
            .getOrDefault(false)
