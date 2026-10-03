package io.legado.app.data.repository

import io.legado.app.help.config.parseReadConfigObject
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The caller owns and cleans the staging directory; every opened font stream is closed here. */
internal suspend fun packReaderBackgroundArchive(
    snapshot: ReaderBackgroundExportSnapshot,
    staging: File,
    openFont: (String) -> Pair<String, InputStream>?,
): File {
    val config = parseReadConfigObject(snapshot.configJson).getOrThrow()
    val files = linkedSetOf<File>()
    suspend fun font(path: String, archiveName: String? = null): String? {
        if (path.isEmpty()) return null
        val source = openFont(path) ?: return null
        val input = source.second
        val name = archiveName ?: source.first
        val output = File(staging, name)
        input.use { inputStream -> output.outputStream().use { inputStream.copyTo(it) } }
        currentCoroutineContext().ensureActive()
        files += output
        return name
    }
    val textFont = font(snapshot.textFont)
    config.textFont = textFont.orEmpty()
    config.titleFont =
        when {
            snapshot.titleFont == snapshot.textFont && textFont != null -> textFont
            snapshot.titleFont.isEmpty() -> ""
            else -> {
                val source = openFont(snapshot.titleFont)
                if (source == null) ""
                else {
                    val original = source.first
                    val name = if (original == textFont) "title_$original" else original
                    val output = File(staging, name)
                    source.second.use { input -> output.outputStream().use { input.copyTo(it) } }
                    currentCoroutineContext().ensureActive()
                    files += output
                    name
                }
            }
        }
    val configFile = File(staging, "readConfig.json").apply { writeText(GSON.toJson(config)) }
    files += configFile
    snapshot.backgrounds.forEach { path ->
        val original = File(path)
        val output = File(staging, original.name)
        if (original.exists() && !output.exists()) {
            original.copyTo(output)
            files += output
        }
    }
    val zip = File(staging, "readConfig.zip")
    check(ZipUtils.zipFiles(files.toList(), zip)) { "打包失败" }
    return zip
}
