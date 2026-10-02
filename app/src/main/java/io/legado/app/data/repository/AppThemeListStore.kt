package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.help.config.ThemeConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

internal class AppThemeListStore(context: Context) : ThemeListStore {
    private val context = context.applicationContext
    override suspend fun list() = ThemeConfig.snapshotConfigs().map { ThemeListValue(it.themeName, GSON.toJson(it)) }
    override suspend fun delete(json: String, occurrence: Int) = ThemeConfig.deleteMatchingConfig(json, occurrence)
    override suspend fun add(json: String) = ThemeConfig.addConfig(json)
    override suspend fun apply(json: String) { ThemeConfig.applyConfigAsync(context, GSON.fromJsonObject<ThemeConfig.Config>(json).getOrThrow()) }
    private fun file(session: String, receipt: String): AtomicFile {
        require(session.matches(Regex("[A-Za-z0-9-]+")) && receipt.matches(Regex("[A-Za-z0-9-]+")))
        return AtomicFile(File(context.filesDir, "theme-list-shares/$session-$receipt.json"))
    }
    override suspend fun writeShare(session: String, receipt: String, json: String) = withContext(Dispatchers.IO + NonCancellable) {
        val atomic = file(session, receipt); check(atomic.baseFile.parentFile!!.isDirectory || atomic.baseFile.parentFile!!.mkdirs())
        val stream = atomic.startWrite()
        try { stream.write(json.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
    override suspend fun readShare(session: String, receipt: String) = withContext(Dispatchers.IO) {
        file(session, receipt).openRead().bufferedReader().use { it.readText() }
    }
}
