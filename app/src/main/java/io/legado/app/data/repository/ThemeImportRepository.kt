package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.help.config.ThemeConfig
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

enum class ThemeImportStatus {
    New,
    Update,
    Existing,
}

/** Immutable transport boundary: mutable theme configurations never escape into UI state. */
data class ThemeImportItem(
    val key: String,
    val name: String,
    val json: String,
    val status: ThemeImportStatus,
) {
    val selectedByDefault: Boolean
        get() = status != ThemeImportStatus.Existing
}

data class ThemeImportSession(val items: List<ThemeImportItem>, val committed: Boolean = false)

interface ThemeImportRepository {
    suspend fun read(source: String): List<ThemeImportItem>

    suspend fun edit(key: String, code: String): ThemeImportItem

    suspend fun restore(session: String): ThemeImportSession?

    suspend fun stage(session: String, items: List<ThemeImportItem>)

    suspend fun insert(session: String, items: List<ThemeImportItem>, selected: Set<String>)
}

class AppThemeImportRepository(context: Context) : ThemeImportRepository {
    private val context = context.applicationContext

    private fun file(session: String): AtomicFile {
        require(session.matches(Regex("[a-zA-Z0-9-]+")))
        return AtomicFile(File(context.cacheDir, "theme-import/$session.json"))
    }

    private fun candidate(
        key: String,
        entity: ThemeConfig.Config,
        existing: List<ThemeConfig.Config>,
    ) =
        ThemeImportItem(
            key,
            entity.themeName,
            GSON.toJson(entity),
            existing
                .firstOrNull { it.themeName == entity.themeName }
                .let { local ->
                    when {
                        local == null -> ThemeImportStatus.New
                        local != entity -> ThemeImportStatus.Update
                        else -> ThemeImportStatus.Existing
                    }
                },
        )

    override suspend fun read(source: String): List<ThemeImportItem> =
        withContext(Dispatchers.IO) {
            val incoming = parse(source.trim(), 0)
            val existing = ThemeConfig.snapshotConfigs()
            incoming.mapIndexed { index, item -> candidate(index.toString(), item, existing) }
        }

    private suspend fun parse(text: String, depth: Int): List<ThemeConfig.Config> {
        require(depth < 32) { context.getString(R.string.wrong_format) }
        return when {
            text.isJsonObject() ->
                listOf(GSON.fromJsonObject<ThemeConfig.Config>(text).getOrThrow())
            text.isJsonArray() -> GSON.fromJsonArray<ThemeConfig.Config>(text).getOrThrow()
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

    override suspend fun edit(key: String, code: String): ThemeImportItem =
        withContext(Dispatchers.IO) {
            candidate(
                key,
                GSON.fromJsonObject<ThemeConfig.Config>(code).getOrThrow(),
                ThemeConfig.snapshotConfigs(),
            )
        }

    override suspend fun restore(session: String): ThemeImportSession? =
        withContext(Dispatchers.IO) {
            val target = file(session)
            if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists())
                return@withContext null
            val data = JSONObject(target.openRead().bufferedReader().use { it.readText() })
            val array = data.getJSONArray("items")
            ThemeImportSession(
                List(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    ThemeImportItem(
                        item.getString("key"),
                        item.getString("name"),
                        item.getString("json"),
                        ThemeImportStatus.valueOf(item.getString("status")),
                    )
                },
                data.optBoolean("committed"),
            )
        }

    private fun write(session: String, value: ThemeImportSession) {
        val array = JSONArray()
        value.items.forEach { item ->
            array.put(
                JSONObject()
                    .put("key", item.key)
                    .put("name", item.name)
                    .put("json", item.json)
                    .put("status", item.status.name)
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

    override suspend fun stage(session: String, items: List<ThemeImportItem>) =
        withContext(Dispatchers.IO) {
            write(session, ThemeImportSession(items))
        }

    override suspend fun insert(
        session: String,
        items: List<ThemeImportItem>,
        selected: Set<String>,
    ) =
        withContext(Dispatchers.IO + NonCancellable) {
            // A stopped/recreated host cannot cancel the commit between theme-file writes and the
            // completion marker.
            val entities =
                items
                    .filter { it.key in selected }
                    .map { GSON.fromJsonObject<ThemeConfig.Config>(it.json).getOrThrow() }
            entities.forEach(ThemeConfig::addConfig)
            write(session, ThemeImportSession(items, committed = true))
        }
}
