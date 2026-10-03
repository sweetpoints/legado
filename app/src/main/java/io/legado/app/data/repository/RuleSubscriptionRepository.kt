package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.RuleSub
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RuleSubscription(
    val id: Long,
    val name: String,
    val url: String,
    val type: Int,
    val order: Int,
    val automatic: Boolean,
    val lastUpdate: Long,
    val interval: Int,
    val silent: Boolean,
    val js: String?,
    val showRule: String?,
    val sourceUrl: String?,
)

data class RuleSubscriptionInput(
    val id: Long? = null,
    val name: String = "",
    val url: String = "",
    val type: Int = 0,
    val automatic: Boolean = false,
    val interval: Int = 0,
    val silent: Boolean = false,
)

class RuleSubscriptionEmptyUrl : IllegalArgumentException()

class RuleSubscriptionDuplicateUrl(val name: String) : IllegalArgumentException()

class RuleSubscriptionMissing : IllegalStateException()

class RuleSubscriptionConflict : IllegalStateException()

data class RuleSubscriptionSave(val before: RuleSubscription?, val target: RuleSubscription)

interface RuleSubscriptionRepository {
    fun rows(): Flow<List<RuleSubscription>>

    suspend fun load(id: Long): RuleSubscription?

    suspend fun save(input: RuleSubscriptionInput): RuleSubscription

    suspend fun saveJournaled(
        input: RuleSubscriptionInput,
        newId: Long,
        journal: (RuleSubscriptionSave) -> Unit,
    ): RuleSubscription

    suspend fun recoverSave(plan: RuleSubscriptionSave): RuleSubscription

    suspend fun delete(id: Long)

    suspend fun reorder(ids: List<Long>)
}

/** Room remains the single subscription store consumed by the existing update scheduler. */
class RoomRuleSubscriptionRepository(private val database: AppDatabase = appDb) :
    RuleSubscriptionRepository {
    private val writes = Mutex()

    private fun RuleSub.snapshot() =
        RuleSubscription(
            id,
            name,
            url,
            type,
            customOrder,
            autoUpdate,
            update,
            updateInterval,
            silentUpdate,
            js,
            showRule,
            sourceUrl,
        )

    override fun rows(): Flow<List<RuleSubscription>> =
        database.ruleSubDao.flowAll().map { rows -> rows.map { it.snapshot() } }.flowOn(IO)

    override suspend fun load(id: Long): RuleSubscription? =
        withContext(IO) { database.ruleSubDao.all.find { it.id == id }?.snapshot() }

    override suspend fun save(input: RuleSubscriptionInput): RuleSubscription =
        withContext(IO) {
            writes.withLock {
                if (input.url.isBlank()) throw RuleSubscriptionEmptyUrl()
                require(input.type in 0..2)
                var result: RuleSubscription? = null
                database.runInTransaction {
                    val current =
                        input.id?.let { id ->
                            database.ruleSubDao.all.find { it.id == id }
                                ?: throw RuleSubscriptionMissing()
                        }
                    val duplicate = database.ruleSubDao.findByUrl(input.url)
                    if (duplicate != null && duplicate.id != input.id)
                        throw RuleSubscriptionDuplicateUrl(duplicate.name)
                    // Merge editable fields into the latest row: background update metadata and JS
                    // are preserved.
                    val row =
                        (current
                                ?: RuleSub(
                                    id = 0,
                                    customOrder = Math.addExact(database.ruleSubDao.maxOrder, 1),
                                ))
                            .copy(
                                name = input.name,
                                url = input.url,
                                type = input.type,
                                autoUpdate = input.automatic,
                                updateInterval = input.interval,
                                silentUpdate = input.silent,
                            )
                    database.ruleSubDao.insert(row)
                    result = checkNotNull(database.ruleSubDao.findByUrl(input.url)).snapshot()
                }
                checkNotNull(result)
            }
        }

    private fun RuleSubscription.entity() =
        RuleSub(
            id,
            name,
            url,
            type,
            order,
            automatic,
            lastUpdate,
            interval,
            silent,
            js,
            showRule,
            sourceUrl,
        )

    override suspend fun saveJournaled(
        input: RuleSubscriptionInput,
        newId: Long,
        journal: (RuleSubscriptionSave) -> Unit,
    ): RuleSubscription =
        withContext(IO) {
            writes.withLock {
                if (input.url.isBlank()) throw RuleSubscriptionEmptyUrl()
                require(input.type in 0..2 && newId > 0)
                var result: RuleSubscription? = null
                database.runInTransaction {
                    val current =
                        input.id?.let { id ->
                            database.ruleSubDao.all.find { it.id == id }
                                ?: throw RuleSubscriptionMissing()
                        }
                    val duplicate = database.ruleSubDao.findByUrl(input.url)
                    if (duplicate != null && duplicate.id != input.id)
                        throw RuleSubscriptionDuplicateUrl(duplicate.name)
                    if (current == null && database.ruleSubDao.all.any { it.id == newId })
                        throw RuleSubscriptionConflict()
                    val target =
                        (current
                                ?: RuleSub(
                                    id = newId,
                                    customOrder = Math.addExact(database.ruleSubDao.maxOrder, 1),
                                ))
                            .copy(
                                name = input.name,
                                url = input.url,
                                type = input.type,
                                autoUpdate = input.automatic,
                                updateInterval = input.interval,
                                silentUpdate = input.silent,
                            )
                            .snapshot()
                    // Durable receipt precedes the mutation and contains the latest full
                    // baseline/target.
                    journal(RuleSubscriptionSave(current?.snapshot(), target))
                    database.ruleSubDao.insert(target.entity())
                    result = target
                }
                checkNotNull(result)
            }
        }

    override suspend fun recoverSave(plan: RuleSubscriptionSave): RuleSubscription =
        withContext(IO) {
            writes.withLock {
                var result: RuleSubscription? = null
                database.runInTransaction {
                    val current =
                        database.ruleSubDao.all.find { it.id == plan.target.id }?.snapshot()
                    if (current == plan.target) {
                        result = current
                        return@runInTransaction
                    }
                    // A missing new target is ambiguous (not committed vs externally deleted): fail
                    // safely.
                    // Never reinterpret another host's edit or deletion as our successful write.
                    if (current != plan.before || plan.before == null)
                        throw RuleSubscriptionConflict()
                    val duplicate = database.ruleSubDao.findByUrl(plan.target.url)
                    if (duplicate != null && duplicate.id != plan.target.id)
                        throw RuleSubscriptionDuplicateUrl(duplicate.name)
                    database.ruleSubDao.insert(plan.target.entity())
                    result = plan.target
                }
                checkNotNull(result)
            }
        }

    override suspend fun delete(id: Long) =
        withContext(IO) {
            writes.withLock {
                database.runInTransaction {
                    database.ruleSubDao.all
                        .find { it.id == id }
                        ?.let { database.ruleSubDao.delete(it) }
                }
            }
        }

    override suspend fun reorder(ids: List<Long>) =
        withContext(IO) {
            writes.withLock {
                require(ids.distinct().size == ids.size)
                database.runInTransaction {
                    val current = database.ruleSubDao.all
                    val byId = current.associateBy { it.id }
                    val desired = ids.mapNotNull(byId::get) + current.filter { it.id !in ids }
                    val orders =
                        if (current.map { it.customOrder }.distinct().size == current.size)
                            current.map { it.customOrder }
                        else current.indices.map { it + 1 }
                    // Keep the original ordering numbers when they are unique, normalize tied
                    // legacy orders.
                    val changes = desired.mapIndexedNotNull { index, row ->
                        if (row.customOrder == orders[index]) null
                        else row.copy(customOrder = orders[index])
                    }
                    if (changes.isNotEmpty()) database.ruleSubDao.update(*changes.toTypedArray())
                }
            }
        }
}
