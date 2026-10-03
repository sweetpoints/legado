package io.legado.app.ui.rss.subscription

import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

internal class SubscriptionRules : RuleSubscriptionRepository {
    val flow = MutableStateFlow(listOf(subscriptionRow(1), subscriptionRow(2), subscriptionRow(3)))
    val orders = mutableListOf<List<Long>>()
    var saves = 0

    override fun rows() = flow

    override suspend fun load(id: Long) = flow.value.find { it.id == id }

    override suspend fun save(input: RuleSubscriptionInput) = saveJournaled(input, 123, {})

    override suspend fun saveJournaled(
        input: RuleSubscriptionInput,
        newId: Long,
        journal: (RuleSubscriptionSave) -> Unit,
    ): RuleSubscription {
        if (input.url.isBlank()) throw RuleSubscriptionEmptyUrl()
        val before = input.id?.let { load(it) ?: throw RuleSubscriptionMissing() }
        flow.value
            .find { it.url == input.url && it.id != input.id }
            ?.let { throw RuleSubscriptionDuplicateUrl(it.name) }
        val target =
            (before ?: subscriptionRow(newId)).copy(
                name = input.name,
                url = input.url,
                type = input.type,
                automatic = input.automatic,
                interval = input.interval,
                silent = input.silent,
            )
        journal(RuleSubscriptionSave(before, target))
        saves++
        flow.value = flow.value.filter { it.id != target.id } + target
        return target
    }

    override suspend fun recoverSave(plan: RuleSubscriptionSave): RuleSubscription {
        if (load(plan.target.id) != plan.target) throw RuleSubscriptionConflict()
        return plan.target
    }

    override suspend fun delete(id: Long) {
        flow.value = flow.value.filter { it.id != id }
    }

    override suspend fun reorder(ids: List<Long>) {
        orders += ids
        flow.value = ids.mapNotNull { id -> flow.value.find { it.id == id } }
    }
}

internal fun subscriptionRow(id: Long) =
    RuleSubscription(
        id,
        "Subscription $id",
        "https://$id",
        (id - 1).toInt(),
        id.toInt(),
        false,
        42,
        0,
        false,
        null,
        null,
        null,
    )

internal class SubscriptionDrafts(val rules: SubscriptionRules) : RuleSubscriptionDraftRepository {
    val records = mutableMapOf<String, RuleSubscriptionDraft>()
    val released = mutableSetOf<String>()
    var claimGate: CompletableDeferred<Unit>? = null
    var failClaim = false

    override suspend fun read(ticket: String) = records[ticket]

    override suspend fun write(ticket: String, draft: RuleSubscriptionDraft) {
        check(ticket !in released)
        if (draft.navigation == null && records[ticket]?.navigation != null) {
            if (failClaim) error("claim failed")
            val gate = claimGate
            claimGate = null
            gate?.let { withContext(NonCancellable) { it.await() } }
        }
        check(ticket !in released)
        if ((records[ticket]?.revision ?: -1) <= draft.revision) records[ticket] = draft
    }

    override suspend fun save(ticket: String, draft: RuleSubscriptionDraft): RuleSubscriptionDraft {
        val editor = draft.editor!!
        rules.saveJournaled(editor.input(), editor.newId) {}
        return draft.copy(editor = null, pendingSave = null, revision = draft.revision + 1).also {
            records[ticket] = it
        }
    }

    override suspend fun release(ticket: String) {
        released += ticket
        records.remove(ticket)
    }
}
