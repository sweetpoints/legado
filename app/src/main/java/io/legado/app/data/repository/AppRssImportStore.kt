package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import androidx.core.net.toUri
import io.legado.app.constant.AppConst
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.requireSourceUrl
import io.legado.app.model.RuleUpdate
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.readText
import java.io.File

/** All methods are called by DefaultRssImportRepository on its I/O dispatcher. */
class AppRssImportStore(context: Context) : RssImportStore {
    private val context = context.applicationContext

    override suspend fun uriText(uri: String) = uri.toUri().readText(context)

    override suspend fun urlSources(url: String): List<RssSource> {
        RuleUpdate.cacheRssSourceMap.remove(url)?.let {
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
                GSON.fromJsonArray<RssSource>(body).getOrThrow().onEach { it.requireSourceUrl() }
            }
    }

    override suspend fun sourceRules(): List<ReplaceRule> =
        appDb.replaceRuleDao.findEnabledBySourceScope()

    override suspend fun existing(urls: List<String>): List<RssSource> {
        val result = mutableListOf<RssSource>()
        appDb.runInTransaction {
            urls.distinct().chunked(900).forEach {
                result += appDb.rssSourceDao.getRssSources(*it.toTypedArray())
            }
        }
        return result
    }

    override suspend fun groups(): List<String> = appDb.rssSourceDao.allGroups()

    override suspend fun preferences() =
        RssImportPreferences(
            AppConfig.importKeepName,
            AppConfig.importKeepGroup,
            AppConfig.importKeepEnable,
            AppConfig.importShowComment,
            AppConfig.importRememberGroup,
            AppConfig.importLastGroup,
            AppConfig.importLastGroupAdd,
            AppConfig.importReplaceSource,
        )

    override suspend fun preferences(value: RssImportPreferences) {
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

    override suspend fun insert(sources: List<RssSource>) {
        SourceHelp.insertRssSource(*sources.toTypedArray())
    }

    private fun file(session: String): AtomicFile {
        require(session.matches(Regex("[a-zA-Z0-9-]+")))
        return AtomicFile(File(context.cacheDir, "rss-source-import/$session.json"))
    }

    override suspend fun readSession(session: String): String? {
        val target = file(session)
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return null
        return target.openRead().bufferedReader().use { it.readText() }
    }

    override suspend fun writeSession(session: String, json: String) {
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
