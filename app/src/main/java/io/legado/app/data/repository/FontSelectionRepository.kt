package io.legado.app.data.repository

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.font.installFontFile
import io.legado.app.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** path retains the callback's historical file-path/content-URI representation. */
data class FontEntry(val path: String, val uri: String, val name: String, val privateFolder: Boolean)
data class FontLoadResult(val entries: List<FontEntry>, val unavailableFolder: Boolean = false, val error: String? = null)

internal fun mergeFontEntries(external: List<FontEntry>, local: List<FontEntry>): List<FontEntry> =
    (external + local).distinctBy { it.path }.sortedWith { first, second ->
        first.name.cnCompare(second.name).takeIf { it != 0 }
            ?: first.privateFolder.compareTo(second.privateFolder)
    }

interface FontSelectionRepository {
    fun storedFolder(): String?
    fun storeFolder(folder: String)
    suspend fun load(folder: String?): FontLoadResult
    suspend fun importFont(uri: String)
    suspend fun preview(entry: FontEntry): Typeface?
    fun selectSystemTypeface(index: Int)
}

class AppFontSelectionRepository(context: Context) : FontSelectionRepository {
    private val context = context.applicationContext
    private val regex = Regex("(?i).*\\.[ot]tf")
    private val previews = object : LinkedHashMap<String, Typeface>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Typeface>?) = size > 32
    }
    override fun storedFolder() = context.getPrefString(PreferKey.fontFolder)
    override fun storeFolder(folder: String) = context.putPrefString(PreferKey.fontFolder, folder)
    private fun localDirectory() = File(FileUtils.getPath(context.externalFiles, "font"))
    private fun FileDoc.entry(privateFolder: Boolean) = FontEntry(toString(), uri.toString(), name, privateFolder)

    override suspend fun load(folder: String?): FontLoadResult = withContext(Dispatchers.IO) {
        val local = localDirectory().listFileDocs { !it.isDir && regex.matches(it.name) }.map { it.entry(true) }
        if (folder.isNullOrEmpty()) return@withContext FontLoadResult(mergeFontEntries(emptyList(), local))
        try {
            val directory = if (folder.isContentScheme()) {
                val uri = Uri.parse(folder)
                val doc = DocumentFile.fromTreeUri(context, uri)
                if (doc?.canRead() == true) FileDoc.fromDocumentFile(doc)
                else RealPathUtil.getPath(context, uri)?.let { FileDoc.fromFile(File(it)) }
                    ?: return@withContext FontLoadResult(mergeFontEntries(emptyList(), local), unavailableFolder = true)
            } else {
                val file = File(folder)
                if (!file.isDirectory || !file.canRead()) return@withContext FontLoadResult(mergeFontEntries(emptyList(), local), unavailableFolder = true)
                FileDoc.fromFile(file)
            }
            val external = directory.list { !it.isDir && regex.matches(it.name) }
                ?.map { it.entry(false) }.orEmpty()
            FontLoadResult(mergeFontEntries(external, local))
        } catch (error: Exception) {
            AppLog.put("加载字体文件失败\n${error.localizedMessage}", error)
            FontLoadResult(mergeFontEntries(emptyList(), local), true, error.localizedMessage ?: error.toString())
        }
    }

    override suspend fun importFont(uri: String) = withContext(Dispatchers.IO) {
        try {
            val source = FileDoc.fromUri(Uri.parse(uri), false)
            source.openInputStream().getOrThrow().use { input ->
                installFontFile(input, source.name, localDirectory()) { file ->
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Typeface.Builder(file).build() != null
                        else Typeface.createFromFile(file) != Typeface.DEFAULT
                    }.getOrDefault(false)
                }
            }
            Unit
        } catch (error: Exception) {
            AppLog.put("导入字体失败\n${error.localizedMessage}", error)
            throw error
        }
    }
    override suspend fun preview(entry: FontEntry): Typeface? = withContext(Dispatchers.IO) {
        synchronized(previews) { previews[entry.path] }?.let { return@withContext it }
        val typeface = runCatching {
            if (entry.path.isContentScheme()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.contentResolver
                    .openFileDescriptor(Uri.parse(entry.uri), "r")?.use { Typeface.Builder(it.fileDescriptor).build() }
                else Typeface.createFromFile(RealPathUtil.getPath(context, Uri.parse(entry.uri)))
            } else Typeface.createFromFile(entry.path)
        }.onFailure { AppLog.put("读取字体 ${entry.name} 出错\n${it.localizedMessage}", it, true) }
            .getOrNull() ?: Typeface.DEFAULT
        synchronized(previews) { previews[entry.path] = typeface }
        typeface
    }
    override fun selectSystemTypeface(index: Int) { AppConfig.systemTypefaces = index }
}
