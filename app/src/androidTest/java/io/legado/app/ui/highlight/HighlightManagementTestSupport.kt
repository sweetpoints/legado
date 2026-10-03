package io.legado.app.ui.highlight

import io.legado.app.data.repository.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun managedHighlight(id: String, index: Int = 0, group: String? = null) =
    HighlightManagedRule(
        index + 1L,
        id,
        "Name $id",
        "Pattern $id",
        false,
        null,
        true,
        "style",
        index,
        3000,
        group,
        false,
        true,
    )

internal class ManagedHighlights : HighlightManagementRepository {
    val flow =
        MutableStateFlow(
            listOf(
                managedHighlight("a", 0, "Characters"),
                managedHighlight("b", 1, "Quotes"),
                managedHighlight("c", 2, null),
            )
        )
    val reordered = mutableListOf<List<String>>()
    val enabled = mutableListOf<Pair<Set<String>, Boolean>>()
    val deleted = mutableListOf<Set<String>>()
    val moved = mutableListOf<Pair<Set<String>, Boolean>>()

    override fun rows(): Flow<List<HighlightManagedRule>> = flow

    override fun groups(): Flow<List<String>> = flow.map { rows ->
        rows.mapNotNull { it.group?.takeIf(String::isNotBlank) }.distinct()
    }

    override suspend fun enable(uuids: Set<String>, enabled: Boolean) {
        this.enabled += uuids to enabled
        flow.value = flow.value.map { if (it.uuid in uuids) it.copy(isEnabled = enabled) else it }
    }

    override suspend fun delete(uuids: Set<String>) {
        deleted += uuids
        flow.value = flow.value.filter { it.uuid !in uuids }
    }

    override suspend fun move(uuids: Set<String>, toTop: Boolean) {
        moved += uuids to toTop
    }

    override suspend fun reorder(visibleOrder: List<String>) {
        reordered += visibleOrder
    }
}

internal class ManagedHighlightSessions : HighlightManagementSessionRepository {
    val records = mutableMapOf<String, HighlightManagementDraft>()
    val released = mutableSetOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var claimStarted = false

    override suspend fun read(ticket: String) = records[ticket]

    override suspend fun write(ticket: String, draft: HighlightManagementDraft) {
        if (records[ticket]?.effects?.isNotEmpty() == true && draft.effects.isEmpty()) {
            val current = gate
            gate = null
            claimStarted = current != null
            current?.let { withContext(NonCancellable) { it.await() } }
        }
        check(ticket !in released)
        if ((records[ticket]?.revision ?: -1) <= draft.revision) records[ticket] = draft
    }

    override suspend fun release(ticket: String) {
        released += ticket
        records.remove(ticket)
    }
}

internal class ManagedHighlightTransfers : HighlightManagementTransferRepository {
    var gate: CompletableDeferred<Unit>? = null
    var started = false
    var fail = false

    override suspend fun export(effect: HighlightManagementEffect): ByteArray {
        started = true
        gate?.let { withContext(NonCancellable) { it.await() } }
        if (fail) error("invalid payload")
        return highlightManagementJson(effect.rules).toByteArray()
    }

    override suspend fun share(effect: HighlightManagementEffect): File =
        error("Share must be tested through the real file repository")
}
