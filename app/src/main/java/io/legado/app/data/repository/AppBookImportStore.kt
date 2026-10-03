package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import androidx.core.net.toUri
import io.legado.app.constant.AppConst
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.RuleUpdate
import io.legado.app.model.jsSource.JsSourceConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.readText
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** All methods are called by DefaultBookImportRepository on its I/O dispatcher. */
class AppBookImportStore(
    context: Context,
    private val database: AppDatabase = appDb,
    private val publish: (List<BookSource>) -> Unit = {
        SourceHelp.insertBookSource(*it.toTypedArray())
        ContentProcessor.upReplaceRules()
    },
    private val sessionsDirectory: File = File(context.filesDir, "book-source-import"),
) : BookImportStore {
    private val context = context.applicationContext

    companion object {
        private val sessionLocks = ConcurrentHashMap<String, Mutex>()
    }

    private fun lock(session: String) =
        sessionLocks.getOrPut(File(sessionsDirectory, "$session.json").absolutePath) { Mutex() }

    override suspend fun uriText(uri: String) = uri.toUri().readText(context)

    override suspend fun urlSources(url: String): List<BookSource> {
        RuleUpdate.cacheBookSourceMap.remove(url)?.let {
            return it
        }
        return okHttpClient
            .newCallResponseBody {
                if (url.endsWith("#requestWithoutUA")) {
                    url(url.substringBeforeLast("#requestWithoutUA"))
                    header(AppConst.UA_NAME, "null")
                } else url(url)
            }
            .decompressed()
            .byteStream()
            .use { body ->
                val text = body.bufferedReader().readText().trim()
                when {
                    text.isJsonArray() -> GSON.fromJsonArray<BookSource>(text).getOrThrow()
                    text.isJsonObject() ->
                        listOf(GSON.fromJsonObject<BookSource>(text).getOrThrow())
                    else -> listOf(javascriptSource(text))
                }.onEach { require(it.bookSourceUrl.isNotBlank()) { "不是书源" } }
            }
    }

    override suspend fun javascriptSource(text: String) =
        JsSourceConfig.extract(text, coroutineContext)

    override suspend fun sourceRules(): List<ReplaceRule> =
        database.replaceRuleDao.findEnabledBySourceScope()

    override suspend fun existing(urls: List<String>): List<BookSource> {
        val result = mutableListOf<BookSource>()
        database.runInTransaction {
            urls.distinct().chunked(900).forEach {
                result += database.bookSourceDao.getBookSources(it)
            }
        }
        return result
    }

    override suspend fun groups(): List<String> = database.bookSourceDao.allGroups()

    override suspend fun preferences() =
        BookImportPreferences(
            AppConfig.importKeepName,
            AppConfig.importKeepGroup,
            AppConfig.importKeepEnable,
            AppConfig.importShowComment,
            AppConfig.importRememberGroup,
            AppConfig.importLastGroup,
            AppConfig.importLastGroupAdd,
            AppConfig.importReplaceSource,
        )

    override suspend fun preferences(value: BookImportPreferences) {
        context.putPrefBoolean(PreferKey.importKeepName, value.keepName)
        context.putPrefBoolean(PreferKey.importKeepGroup, value.keepGroup)
        AppConfig.importKeepEnable = value.keepEnable
        AppConfig.importShowComment = value.showComment
        AppConfig.importRememberGroup = value.rememberGroup
        if (value.rememberGroup) {
            AppConfig.importLastGroup = value.lastGroup
            AppConfig.importLastGroupAdd = value.lastGroupAdd
        }
        AppConfig.importReplaceSource = value.automaticReplacement
    }

    override suspend fun insert(sources: List<BookSource>) {
        publish(sources)
    }

    private fun file(session: String): AtomicFile {
        require(session.matches(Regex("[a-zA-Z0-9-]+")))
        check(sessionsDirectory.isDirectory || sessionsDirectory.mkdirs()) { "无法保存导入草稿" }
        return AtomicFile(File(sessionsDirectory, "$session.json"))
    }

    override suspend fun readSession(session: String): String? =
        lock(session).withLock {
            val target = file(session)
            val input =
                try {
                    target.openRead()
                } catch (_: java.io.FileNotFoundException) {
                    return@withLock null
                }
            input.bufferedReader().use { it.readText() }
        }

    override suspend fun writeSession(session: String, json: String) =
        withContext(NonCancellable) {
            lock(session).withLock {
                val target = file(session)
                val stream = target.startWrite()
                try {
                    stream.write(json.toByteArray())
                    target.finishWrite(stream)
                } catch (error: Throwable) {
                    target.failWrite(stream)
                    throw error
                }
            }
        }
}
