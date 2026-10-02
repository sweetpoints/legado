package io.legado.app.data.repository

import io.legado.app.constant.AppLog
import io.legado.app.help.http.HttpLogRecord
import io.legado.app.help.http.HttpLogStore
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppLogRow(val id: Long, val time: String, val message: String, val hasDetails: Boolean)
data class AppLogDetail(val id: Long, val title: String, val text: String)

sealed interface AppLogExport {
    data class Text(val text: String) : AppLogExport
    data class Document(val file: File) : AppLogExport
}

interface AppLogsRepository {
    val logs: Flow<List<AppLogRow>>
    suspend fun readDetail(id: Long): AppLogDetail?
    suspend fun clearLogs()
    suspend fun prepareExport(): AppLogExport?
}

class DefaultAppLogsRepository(private val cacheDirectory: File) : AppLogsRepository {
    override val logs: Flow<List<AppLogRow>> = AppLog.entries.map { entries ->
        val formatter = SimpleDateFormat(LogUtils.TIME_PATTERN, Locale.getDefault())
        entries.map {
            AppLogRow(it.id, formatter.format(Date(it.time)), it.message,
                it.throwable != null || HttpLogRecord.parseId(it.message) != null)
        }
    }.flowOn(Dispatchers.Default)

    override suspend fun readDetail(id: Long): AppLogDetail? = withContext(Dispatchers.IO) {
        val entry = AppLog.entries.value.firstOrNull { it.id == id } ?: return@withContext null
        val httpId = HttpLogRecord.parseId(entry.message)
        when {
            httpId != null -> AppLogDetail(id, "HTTP", HttpLogStore.get(httpId)?.detail ?: entry.message)
            entry.throwable != null -> AppLogDetail(id, "Log", entry.throwable.stackTraceToString())
            else -> null
        }
    }

    override suspend fun clearLogs() = withContext(Dispatchers.IO) {
        AppLog.clear()
        HttpLogStore.clear()
    }

    override suspend fun prepareExport(): AppLogExport? = withContext(Dispatchers.IO) {
        val text = AppLog.exportText(AppLog.logs)
        when {
            text.isBlank() -> null
            text.length <= MAX_SHARE_TEXT -> AppLogExport.Text(text)
            else -> {
                // Each share keeps its own snapshot instead of overwriting an already shared file.
                val file = File.createTempFile("applog-", ".txt", cacheDirectory)
                try {
                    file.writeText(text)
                    AppLogExport.Document(file)
                } catch (error: Exception) {
                    file.delete()
                    throw error
                }
            }
        }
    }

    companion object {
        internal const val MAX_SHARE_TEXT = 64_000
    }
}
