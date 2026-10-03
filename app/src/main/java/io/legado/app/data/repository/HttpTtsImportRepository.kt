package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Immutable transport boundary: mutable Room entities never escape into UI state. */
data class HttpTtsImportItem(
    val key: String,
    val name: String,
    val json: String,
    val updatedAt: Long,
    val localUpdatedAt: Long?,
) {
    val selectedByDefault: Boolean
        get() = localUpdatedAt == null || updatedAt > localUpdatedAt
}

data class HttpTtsImportSession(val items: List<HttpTtsImportItem>, val committed: Boolean = false)

interface HttpTtsImportRepository {
    suspend fun read(source: String): List<HttpTtsImportItem>

    suspend fun edit(key: String, code: String): HttpTtsImportItem

    suspend fun restore(session: String): HttpTtsImportSession?

    suspend fun stage(session: String, items: List<HttpTtsImportItem>)

    suspend fun insert(session: String, items: List<HttpTtsImportItem>, selected: Set<String>)
}

class AppHttpTtsImportRepository(context: Context) : HttpTtsImportRepository {
    private val context = context.applicationContext

    private fun file(session: String): AtomicFile {
        require(session.matches(Regex("[a-zA-Z0-9-]+")))
        return AtomicFile(File(context.cacheDir, "http-tts-import/$session.json"))
    }

    private fun candidate(key: String, entity: HttpTTS) =
        HttpTtsImportItem(
            key,
            entity.name,
            GSON.toJson(entity),
            entity.lastUpdateTime,
            appDb.httpTTSDao.get(entity.id)?.lastUpdateTime,
        )

    override suspend fun read(source: String): List<HttpTtsImportItem> =
        withContext(Dispatchers.IO) {
            parse(source.trim(), 0).mapIndexed { index, item -> candidate(index.toString(), item) }
        }

    private suspend fun parse(text: String, depth: Int): List<HttpTTS> {
        require(depth < 32) { context.getString(R.string.wrong_format) }
        return when {
            text.isJsonObject() -> listOf(HttpTTS.fromJson(text).getOrThrow())
            text.isJsonArray() -> HttpTTS.fromJsonArray(text).getOrThrow()
            text.isAbsUrl() -> {
                val body =
                    okHttpClient
                        .newCallResponseBody {
                            if (text.endsWith("#requestWithoutUA")) {
                                url(text.substringBeforeLast("#requestWithoutUA"))
                                header(AppConst.UA_NAME, "null")
                            } else url(text)
                        }
                        .decompressed()
                        .text()
                parse(body.trim(), depth + 1)
            }
            text.isUri() -> parse(text.toUri().readText(context).trim(), depth + 1)
            else -> error(context.getString(R.string.wrong_format))
        }
    }

    override suspend fun edit(key: String, code: String): HttpTtsImportItem =
        withContext(Dispatchers.IO) {
            candidate(key, HttpTTS.fromJson(code).getOrThrow())
        }

    override suspend fun restore(session: String): HttpTtsImportSession? =
        withContext(Dispatchers.IO) {
            val target = file(session)
            if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists())
                return@withContext null
            val data = JSONObject(target.openRead().bufferedReader().use { it.readText() })
            val array = data.getJSONArray("items")
            HttpTtsImportSession(
                List(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    HttpTtsImportItem(
                        item.getString("key"),
                        item.getString("name"),
                        item.getString("json"),
                        item.getLong("updated"),
                        if (item.isNull("local")) null else item.getLong("local"),
                    )
                },
                data.optBoolean("committed"),
            )
        }

    private fun write(session: String, value: HttpTtsImportSession) {
        val array = JSONArray()
        value.items.forEach { item ->
            array.put(
                JSONObject()
                    .put("key", item.key)
                    .put("name", item.name)
                    .put("json", item.json)
                    .put("updated", item.updatedAt)
                    .put("local", item.localUpdatedAt ?: JSONObject.NULL)
            )
        }
        val target = file(session)
        val stream = target.startWrite()
        try {
            stream.write(
                JSONObject()
                    .put("items", array)
                    .put("committed", value.committed)
                    .toString()
                    .toByteArray()
            )
            target.finishWrite(stream)
        } catch (error: Throwable) {
            target.failWrite(stream)
            throw error
        }
    }

    override suspend fun stage(session: String, items: List<HttpTtsImportItem>) =
        withContext(Dispatchers.IO) {
            write(session, HttpTtsImportSession(items))
        }

    override suspend fun insert(
        session: String,
        items: List<HttpTtsImportItem>,
        selected: Set<String>,
    ) =
        withContext(Dispatchers.IO + NonCancellable) {
            // A stopped/recreated host cannot cancel the commit between Room and the completion
            // marker.
            val entities =
                items.filter { it.key in selected }.map { HttpTTS.fromJson(it.json).getOrThrow() }
            appDb.httpTTSDao.insert(*entities.toTypedArray())
            write(session, HttpTtsImportSession(items, committed = true))
        }
}
