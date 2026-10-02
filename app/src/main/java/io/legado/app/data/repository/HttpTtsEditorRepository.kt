package io.legado.app.data.repository

import io.legado.app.data.appDb
import io.legado.app.data.dao.HttpTTSDao
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.ConcurrentRateLimiter.Companion.concurrentRecordMap
import io.legado.app.help.source.clearSharedGlobalState
import io.legado.app.model.ReadAloud
import io.legado.app.model.SharedJsScope
import io.legado.app.utils.GSON
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class HttpTtsEditorField { Name, Pause, Url, ContentType, ConcurrentRate, LoginUrl, LoginUi, LoginCheckJs, Header, JsLib }
data class HttpTtsEditorDraft(
    val id: Long, val name: String = "", val pause: String = "", val url: String = "",
    val contentType: String = "", val concurrentRate: String = "", val loginUrl: String = "",
    val loginUi: String = "", val loginCheckJs: String = "", val header: String = "", val jsLib: String = "",
    val cookie: Boolean = false,
) {
    fun text(field: HttpTtsEditorField): String = when (field) {
        HttpTtsEditorField.Name -> name; HttpTtsEditorField.Pause -> pause; HttpTtsEditorField.Url -> url
        HttpTtsEditorField.ContentType -> contentType; HttpTtsEditorField.ConcurrentRate -> concurrentRate
        HttpTtsEditorField.LoginUrl -> loginUrl; HttpTtsEditorField.LoginUi -> loginUi
        HttpTtsEditorField.LoginCheckJs -> loginCheckJs; HttpTtsEditorField.Header -> header; HttpTtsEditorField.JsLib -> jsLib
    }
    fun edit(field: HttpTtsEditorField, value: String): HttpTtsEditorDraft = when (field) {
        HttpTtsEditorField.Name -> copy(name = value); HttpTtsEditorField.Pause -> copy(pause = value)
        HttpTtsEditorField.Url -> copy(url = value); HttpTtsEditorField.ContentType -> copy(contentType = value)
        HttpTtsEditorField.ConcurrentRate -> copy(concurrentRate = value); HttpTtsEditorField.LoginUrl -> copy(loginUrl = value)
        HttpTtsEditorField.LoginUi -> copy(loginUi = value); HttpTtsEditorField.LoginCheckJs -> copy(loginCheckJs = value)
        HttpTtsEditorField.Header -> copy(header = value); HttpTtsEditorField.JsLib -> copy(jsLib = value)
    }
    fun entity() = HttpTTS(id = id, name = name, url = url, contentType = contentType,
        pauseDuration = pause.toIntOrNull()?.coerceIn(0, 10_000) ?: 0,
        concurrentRate = concurrentRate, loginUrl = loginUrl, loginUi = loginUi, loginCheckJs = loginCheckJs,
        header = header, jsLib = jsLib, enabledCookieJar = cookie)
    companion object {
        fun from(value: HttpTTS, id: Long = value.id) = HttpTtsEditorDraft(id, value.name, value.pauseDuration.toString(), value.url,
            value.contentType.orEmpty(), value.concurrentRate.orEmpty(), value.loginUrl.orEmpty(), value.loginUi.orEmpty(),
            value.loginCheckJs.orEmpty(), value.header.orEmpty(), value.jsLib.orEmpty(), value.enabledCookieJar == true)
    }
}
interface HttpTtsEditorRepository {
    suspend fun load(id: Long): HttpTtsEditorDraft?
    /** Returns whether the currently selected engine needs rebuilding on the host thread. */
    suspend fun save(original: HttpTtsEditorDraft?, draft: HttpTtsEditorDraft): Boolean
    suspend fun parse(text: String, id: Long): HttpTtsEditorDraft
    suspend fun copy(draft: HttpTtsEditorDraft): String
    suspend fun loginHeader(draft: HttpTtsEditorDraft): String?
    suspend fun deleteLoginHeader(draft: HttpTtsEditorDraft)
}
class AppHttpTtsEditorRepository(
    private val dao: HttpTTSDao = appDb.httpTTSDao,
    private val removeScope: (String?) -> Unit = SharedJsScope::remove,
    private val clearGlobal: (HttpTTS) -> Unit = { it.clearSharedGlobalState() },
    private val clearConcurrent: (String) -> Unit = { concurrentRecordMap.remove(it) },
    private val selectedEngine: () -> String? = { ReadAloud.ttsEngine },
) : HttpTtsEditorRepository {
    override suspend fun load(id: Long) = withContext(Dispatchers.IO) { dao.get(id)?.let { HttpTtsEditorDraft.from(it) } }
    override suspend fun save(original: HttpTtsEditorDraft?, draft: HttpTtsEditorDraft) = withContext(Dispatchers.IO) {
        val value = draft.entity()
        // Read the original row to preserve null JS library values in the cache key.
        val old = original?.let { dao.get(it.id) ?: it.entity() }
        old?.let {
            if (it.jsLib != value.jsLib) removeScope(it.jsLib)
            else if (it.getKey() != value.getKey()) clearGlobal(it)
        }
        dao.insert(value)
        clearConcurrent(value.getKey())
        selectedEngine() == value.id.toString()
    }
    override suspend fun parse(text: String, id: Long) = withContext(Dispatchers.IO) {
        val input = text.trim()
        val entity = when {
            input.isJsonObject() -> HttpTTS.fromJson(input).getOrThrow()
            input.isJsonArray() -> HttpTTS.fromJsonArray(input).getOrThrow().first()
            else -> error("格式不对")
        }
        HttpTtsEditorDraft.from(entity, id)
    }
    override suspend fun copy(draft: HttpTtsEditorDraft) = withContext(Dispatchers.IO) { GSON.toJson(draft.entity()) }
    override suspend fun loginHeader(draft: HttpTtsEditorDraft) = withContext(Dispatchers.IO) { draft.entity().getLoginHeader() }
    override suspend fun deleteLoginHeader(draft: HttpTtsEditorDraft) = withContext(Dispatchers.IO) { draft.entity().removeLoginHeader() }
}
