package io.legado.app.data.repository

import android.content.Context
import android.speech.tts.TextToSpeech
import io.legado.app.data.appDb
import io.legado.app.help.DefaultData
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.clearSharedGlobalState
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.ACache
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

data class SpeakSystemEngine(val name: String, val label: String)
data class SpeakHttpEngine(val id: Long, val name: String, val canLogin: Boolean)
data class SpeakEngineExport(val name: String, val bytes: ByteArray)
data class SpeakEngineShare(val url: String, val summary: String = "", val passphrase: String? = null)
interface SpeakEngineRepository {
    val engines: Flow<List<SpeakHttpEngine>>
    fun initialSelection(): String?
    suspend fun systemEngines(): List<SpeakSystemEngine>
    suspend fun apply(selection: String?, general: Boolean)
    suspend fun delete(id: Long)
    suspend fun importDefault()
    suspend fun clearCache()
    suspend fun histories(): List<String>
    suspend fun saveHistories(values: List<String>)
    suspend fun export(id: Long?): SpeakEngineExport?
    suspend fun share(url: String): SpeakEngineShare
    suspend fun passphrase(url: String): String
}
class AppSpeakEngineRepository(context: Context) : SpeakEngineRepository {
    private val context = context.applicationContext
    private val book = ReadBook.book
    override val engines = appDb.httpTTSDao.flowAll().map { list -> list.map {
        SpeakHttpEngine(it.id, it.name, !it.loginUrl.isNullOrBlank() || !it.loginUi.isNullOrBlank())
    } }.flowOn(Dispatchers.IO)
    override fun initialSelection() = ReadAloud.ttsEngine
    override suspend fun systemEngines() = withContext(Dispatchers.IO) {
        val tts = TextToSpeech(context, null)
        try { tts.engines.map { SpeakSystemEngine(it.name, it.label) } } finally { tts.shutdown() }
    }
    override suspend fun apply(selection: String?, general: Boolean) = withContext(Dispatchers.IO) {
        if (general) { book?.setTtsEngine(null); AppConfig.ttsEngine = selection }
        else book?.setTtsEngine(selection)
        Unit
    }
    override suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        appDb.httpTTSDao.get(id)?.let { it.clearSharedGlobalState(); appDb.httpTTSDao.delete(it) }
        Unit
    }
    override suspend fun importDefault() = withContext(Dispatchers.IO) { DefaultData.importDefaultHttpTTS(); Unit }
    override suspend fun clearCache() = withContext(Dispatchers.IO) {
        FileUtils.listDirsAndFiles(File(context.cacheDir, "httpTTS").absolutePath)?.forEach { FileUtils.delete(it.absolutePath) }
        Unit
    }
    override suspend fun histories(): List<String> = withContext(Dispatchers.IO) {
        ACache.get(cacheDir = false).getAsString("ttsUrlKey")?.splitNotBlank(",")?.toList() ?: emptyList()
    }
    override suspend fun saveHistories(values: List<String>) = withContext(Dispatchers.IO) {
        ACache.get(cacheDir = false).put("ttsUrlKey", values.joinToString(",")); Unit
    }
    override suspend fun export(id: Long?) = withContext(Dispatchers.IO) {
        if (id == null) SpeakEngineExport("httpTts.json", GSON.toJson(appDb.httpTTSDao.all).toByteArray())
        else appDb.httpTTSDao.get(id)?.let { SpeakEngineExport("httpTts_${it.name}.json", GSON.toJson(it).toByteArray()) }
    }
    override suspend fun share(url: String) = withContext(Dispatchers.IO) {
        SpeakEngineShare(url, if (url.isAbsUrl()) DirectLinkUpload.getSummary() else "")
    }
    override suspend fun passphrase(url: String) = withContext(Dispatchers.IO) {
        SourceSharePassphrase.encode(url, SourceSharePassphrase.Type.TTS_RULE, expiryDays = DirectLinkUpload.getExpiryDate())
    }
}
