package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import androidx.sqlite.db.SimpleSQLiteQuery
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.help.IntentData
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AppHighlightRuleEditorStore(
    context: Context,
    private val database: AppDatabase = appDb,
) : HighlightRuleEditorStore {
    private val directory =
        File(context.applicationContext.filesDir, "highlight-rule-editor-drafts")

    override suspend fun load(id: Long) =
        database.highlightRuleDao.findById(id)?.let { rule ->
            HighlightRuleDraft(
                rule.id,
                rule.uuid,
                rule.name,
                rule.pattern,
                rule.isRegex,
                rule.scope,
                rule.isEnabled,
                rule.styleObj(),
                rule.order,
                rule.timeoutMillisecond,
                rule.group,
                rule.applyToTitle,
                rule.applyToBody,
            )
        }

    override suspend fun seed(key: String?): HighlightRuleDraft {
        if (key == null) return HighlightRuleDraft()
        return seeds.getOrPut(key) {
            IntentData.get<HighlightRuleDraft>(key)
                ?: error("Initial highlight rule draft is unavailable")
        }
    }

    override fun releaseSeed(key: String?) {
        key?.let { seeds.remove(it) }
    }

    override suspend fun insert(rule: HighlightRuleDraft): Long {
        var id = 0L
        database.runInTransaction {
            val existing =
                database
                    .query(
                        SimpleSQLiteQuery(
                            "SELECT id FROM highlightRules WHERE uuid = ?",
                            arrayOf(rule.uuid),
                        )
                    )
                    .use {
                        if (it.moveToFirst()) it.getLong(0) else null
                    }
            val entity =
                HighlightRule(
                    rule.id.takeIf { it > 0 } ?: existing ?: 0L,
                    rule.uuid,
                    rule.name,
                    rule.pattern,
                    rule.isRegex,
                    rule.scope,
                    rule.isEnabled,
                    "",
                    rule.order,
                    rule.timeoutMillisecond,
                    rule.group,
                    rule.applyToTitle,
                    rule.applyToBody,
                )
            entity.applyStyle(rule.style)
            if (entity.order == Int.MIN_VALUE)
                entity.order =
                    existing?.let { database.highlightRuleDao.findById(it)?.order }
                        ?: (database.highlightRuleDao.maxOrder + 1)
            id = database.highlightRuleDao.insert(entity).single()
        }
        return id
    }

    private fun file(session: String): AtomicFile {
        require(session.matches(Regex("[A-Za-z0-9-]+")))
        return AtomicFile(File(directory, "$session.json"))
    }

    private fun lock(session: String) =
        locks.getOrPut(file(session).baseFile.absolutePath) { Mutex() }

    private fun readFile(session: String): HighlightRuleEditorDraft? {
        val input =
            try {
                file(session).openRead()
            } catch (_: java.io.FileNotFoundException) {
                return null
            }
        return GSON.fromJsonObject<HighlightRuleEditorDraft>(
                input.bufferedReader().use { it.readText() }
            )
            .getOrThrow()
    }

    override suspend fun read(session: String) =
        withContext(Dispatchers.IO) { lock(session).withLock { readFile(session) } }

    override suspend fun write(session: String, draft: HighlightRuleEditorDraft) =
        withContext(Dispatchers.IO + NonCancellable) {
            lock(session).withLock {
                if ((readFile(session)?.revision ?: Long.MIN_VALUE) > draft.revision)
                    return@withLock
                check(directory.isDirectory || directory.mkdirs())
                val atomic = file(session)
                val stream = atomic.startWrite()
                try {
                    stream.write(GSON.toJson(draft).toByteArray(Charsets.UTF_8))
                    atomic.finishWrite(stream)
                } catch (error: Throwable) {
                    atomic.failWrite(stream)
                    throw error
                }
            }
        }

    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
        private val seeds = ConcurrentHashMap<String, HighlightRuleDraft>()
    }
}
