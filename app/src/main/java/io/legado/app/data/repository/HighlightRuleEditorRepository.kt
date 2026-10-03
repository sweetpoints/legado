package io.legado.app.data.repository

import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightStyles
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class HighlightRuleDraft(
    val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val name: String = "",
    val pattern: String = "",
    val isRegex: Boolean = false,
    val scope: String? = null,
    val isEnabled: Boolean = true,
    val style: HighlightStyle = HighlightStyles.presets.first(),
    val order: Int = Int.MIN_VALUE,
    val timeoutMillisecond: Long = 3000,
    val group: String? = null,
    val applyToTitle: Boolean = false,
    val applyToBody: Boolean = true,
)

internal data class HighlightRuleEditorDraft(
    val rule: HighlightRuleDraft,
    val revision: Long = 0,
    val savedRuleId: Long? = null,
)

internal class MissingHighlightRuleException : Exception("Highlight rule not found")

internal class InvalidHighlightRuleException(val pattern: String) :
    Exception("Invalid highlight rule")

internal interface HighlightRuleEditorStore {
    suspend fun load(id: Long): HighlightRuleDraft?

    suspend fun seed(key: String?): HighlightRuleDraft

    suspend fun read(session: String): HighlightRuleEditorDraft?

    suspend fun write(session: String, draft: HighlightRuleEditorDraft)

    suspend fun insert(rule: HighlightRuleDraft): Long

    fun releaseSeed(key: String?)
}

internal interface HighlightRuleEditorRepository {
    suspend fun initial(session: String, id: Long, seed: String?): HighlightRuleEditorDraft

    suspend fun draft(session: String, draft: HighlightRuleEditorDraft)

    suspend fun save(session: String, draft: HighlightRuleEditorDraft): HighlightRuleEditorDraft
}

internal class DefaultHighlightRuleEditorRepository(
    private val store: HighlightRuleEditorStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : HighlightRuleEditorRepository {
    override suspend fun initial(session: String, id: Long, seed: String?) =
        withContext(io) {
            store.read(session)?.let {
                return@withContext it
            }
            val rule =
                if (id > 0) store.load(id) ?: throw MissingHighlightRuleException()
                else store.seed(seed)
            val initial = HighlightRuleEditorDraft(rule)
            store.write(session, initial)
            store.releaseSeed(seed)
            initial
        }

    override suspend fun draft(session: String, draft: HighlightRuleEditorDraft) =
        withContext(io) { store.write(session, draft) }

    override suspend fun save(
        session: String,
        draft: HighlightRuleEditorDraft,
    ): HighlightRuleEditorDraft =
        withContext(io) {
            if (draft.savedRuleId != null) return@withContext draft
            val rule = draft.rule
            if (
                rule.pattern.isEmpty() ||
                    rule.isRegex && runCatching { Pattern.compile(rule.pattern) }.isFailure
            )
                throw InvalidHighlightRuleException(rule.pattern)
            val normalized =
                rule.copy(
                    scope = rule.scope?.takeUnless { it.isBlank() },
                    group = rule.group?.trim()?.takeUnless { it.isBlank() },
                    style = rule.style.normalized(),
                )
            // Persist the complete identity before touching Room; retries keep the same UUID.
            store.write(session, draft.copy(rule = normalized))
            val id = store.insert(normalized)
            val result =
                draft.copy(
                    rule = normalized.copy(id = id),
                    revision = draft.revision + 1,
                    savedRuleId = id,
                )
            store.write(session, result)
            result
        }
}
