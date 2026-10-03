package io.legado.app.data.repository

import io.legado.app.model.BookCover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CoverRuleDraft(
    val enabled: Boolean = true,
    val searchUrl: String = "",
    val coverRule: String = "",
)

interface CoverRuleRepository {
    suspend fun load(): CoverRuleDraft

    suspend fun save(draft: CoverRuleDraft)

    suspend fun delete()
}

class BookCoverRuleRepository : CoverRuleRepository {
    override suspend fun load() =
        withContext(Dispatchers.IO) {
            BookCover.getCoverRule().let { CoverRuleDraft(it.enable, it.searchUrl, it.coverRule) }
        }

    override suspend fun save(draft: CoverRuleDraft) =
        withContext(Dispatchers.IO) {
            BookCover.saveCoverRule(
                BookCover.CoverRule(draft.enabled, draft.searchUrl, draft.coverRule)
            )
        }

    override suspend fun delete() = withContext(Dispatchers.IO) { BookCover.delCoverRule() }
}
