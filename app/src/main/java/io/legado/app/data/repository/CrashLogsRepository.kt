package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.delete
import io.legado.app.utils.find
import io.legado.app.utils.getFile
import io.legado.app.utils.list
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CrashLogEntry(val id: String, val name: String)
data class CrashLogContent(val entry: CrashLogEntry, val text: String)

interface CrashLogsRepository {
    suspend fun loadLogs(): List<CrashLogEntry>
    suspend fun readLog(id: String): CrashLogContent
    suspend fun clearLogs()
}

class FileCrashLogsRepository(context: Context) : CrashLogsRepository {
    private val context = context.applicationContext
    private val mutex = Mutex()
    private var documents = emptyMap<String, FileDoc>()

    override suspend fun loadLogs(): List<CrashLogEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val local = context.externalCacheDir?.getFile("crash")?.listFiles()
                .orEmpty().filter { it.isFile }.map(FileDoc::fromFile)
            val backup = backupDirectory()?.list { !it.isDir }.orEmpty()
            // Keep the local copy when a backup has the same name.
            val logs = (local + backup).sortedByDescending { it.name }.distinctBy { it.name }
            documents = logs.associateBy { it.uri.toString() }
            logs.map { CrashLogEntry(it.uri.toString(), it.name) }
        }
    }

    override suspend fun readLog(id: String): CrashLogContent = withContext(Dispatchers.IO) {
        mutex.withLock {
            val document = checkNotNull(documents[id]) { "Crash log is no longer available" }
            CrashLogContent(CrashLogEntry(id, document.name), String(document.readBytes()))
        }
    }

    override suspend fun clearLogs() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                context.externalCacheDir?.getFile("crash")?.let { FileUtils.delete(it, false) }
                backupDirectory()?.delete()
                Unit
            } finally {
                documents = emptyMap()
            }
        }
    }

    private fun backupDirectory(): FileDoc? = AppConfig.backupPath
        ?.takeIf { it.isNotEmpty() }
        ?.let { FileDoc.fromUri(Uri.parse(it), true).find("crash") }
}
