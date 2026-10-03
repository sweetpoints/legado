package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Full immutable projection: UI selection and drag never mutate Room entities. */
data class HighlightManagedRule(
    val id: Long,
    val uuid: String,
    val name: String,
    val pattern: String,
    val isRegex: Boolean,
    val scope: String?,
    val isEnabled: Boolean,
    val style: String,
    val order: Int,
    val timeoutMillisecond: Long,
    val group: String?,
    val applyToTitle: Boolean,
    val applyToBody: Boolean,
) {
    val displayName: String
        get() = name.ifBlank { pattern }

    val label: String
        get() = group?.takeIf { it.isNotBlank() }?.let { "[$it] $displayName" } ?: displayName

    internal fun entity() =
        HighlightRule(
            id,
            uuid,
            name,
            pattern,
            isRegex,
            scope,
            isEnabled,
            style,
            order,
            timeoutMillisecond,
            group,
            applyToTitle,
            applyToBody,
        )

    companion object {
        fun from(rule: HighlightRule) =
            HighlightManagedRule(
                rule.id,
                rule.uuid,
                rule.name,
                rule.pattern,
                rule.isRegex,
                rule.scope,
                rule.isEnabled,
                rule.style,
                rule.order,
                rule.timeoutMillisecond,
                rule.group,
                rule.applyToTitle,
                rule.applyToBody,
            )
    }
}

interface HighlightManagementRepository {
    fun rows(): Flow<List<HighlightManagedRule>>

    fun groups(): Flow<List<String>>

    suspend fun enable(uuids: Set<String>, enabled: Boolean)

    suspend fun delete(uuids: Set<String>)

    suspend fun move(uuids: Set<String>, toTop: Boolean)

    /** Only these visible slots move; rules hidden by a group filter stay in place. */
    suspend fun reorder(visibleOrder: List<String>)
}

class RoomHighlightManagementRepository(private val database: AppDatabase = appDb) :
    HighlightManagementRepository {
    override fun rows() =
        database.highlightRuleDao
            .flowAll()
            .map { rows -> rows.map(HighlightManagedRule::from) }
            .flowOn(IO)

    override fun groups() = database.highlightRuleDao.flowGroups().flowOn(IO)

    override suspend fun enable(uuids: Set<String>, enabled: Boolean) =
        withContext(IO) {
            database.runInTransaction {
                val latest = database.highlightRuleDao.all.filter { it.uuid in uuids }
                val changed =
                    latest.filter { it.isEnabled != enabled }.map { it.copy(isEnabled = enabled) }
                if (changed.isNotEmpty()) database.highlightRuleDao.update(*changed.toTypedArray())
            }
        }

    override suspend fun delete(uuids: Set<String>) =
        withContext(IO) {
            database.runInTransaction {
                val latest = database.highlightRuleDao.all.filter { it.uuid in uuids }
                if (latest.isNotEmpty()) database.highlightRuleDao.delete(*latest.toTypedArray())
            }
        }

    override suspend fun move(uuids: Set<String>, toTop: Boolean) =
        withContext(IO) {
            database.highlightRuleDao.move(uuids, toTop)
        }

    override suspend fun reorder(visibleOrder: List<String>) =
        withContext(IO) {
            require(visibleOrder.distinct().size == visibleOrder.size)
            database.runInTransaction {
                val current = database.highlightRuleDao.all
                val byUuid = current.associateBy { it.uuid }
                val desired = visibleOrder.mapNotNull(byUuid::get)
                val moved = desired.mapTo(hashSetOf()) { it.uuid }
                val remaining = desired.iterator()
                val ordered = current.map { if (it.uuid in moved) remaining.next() else it }
                val orders =
                    if (current.map { it.order }.distinct().size == current.size)
                        current.map { it.order }
                    else current.indices.toList()
                // Read fresh rows within this transaction; drag cannot overwrite edits or resurrect
                // deletions.
                val updates = ordered.mapIndexedNotNull { index, row ->
                    if (row.order == orders[index]) null else row.copy(order = orders[index])
                }
                if (updates.isNotEmpty()) database.highlightRuleDao.update(*updates.toTypedArray())
            }
        }
}

/** The import envelope and every rule field are identical to the existing export/share format. */
fun highlightManagementJson(rules: List<HighlightManagedRule>): String =
    GSON.toJson(HighlightRuleFile(HighlightRuleFile.TYPE, rules.map { it.entity() }))
