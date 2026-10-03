package io.legado.app.ui.book.source.manage

import io.legado.app.data.entities.BookSourcePart
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.cnCompare

internal data class SourceManagerRow(
    val url: String,
    val name: String,
    val group: String?,
    val order: Int,
    val enabled: Boolean,
    val exploreEnabled: Boolean,
    val hasExplore: Boolean,
    val hasLogin: Boolean,
    val hasJs: Boolean,
    val updated: Long,
    val response: Long,
    val weight: Int,
    val checkStatus: String,
    val checkDetail: String,
    val host: String,
) {
    val displayName
        get() = if (group.isNullOrBlank()) name else "$name ($group)"

    companion object {
        fun from(it: BookSourcePart) =
            SourceManagerRow(
                it.bookSourceUrl,
                it.bookSourceName,
                it.bookSourceGroup,
                it.customOrder,
                it.enabled,
                it.enabledExplore,
                it.hasExploreUrl,
                it.hasLoginUrl,
                it.hasJs,
                it.lastUpdateTime,
                it.respondTime,
                it.weight,
                it.checkStatus,
                it.checkDetail,
                NetworkUtils.getSubDomainOrNull(it.bookSourceUrl) ?: "#",
            )
    }
}

internal data class SourceManagerState(
    val query: String = "",
    val rows: List<SourceManagerRow> = emptyList(),
    val groups: List<String> = emptyList(),
    val selected: Set<String> = emptySet(),
    val counts: Map<String, Int> = emptyMap(),
    val sort: BookSourceSort = BookSourceSort.Default,
    val ascending: Boolean = true,
    val byDomain: Boolean = false,
    val showStatus: Boolean = false,
    val status: String = "",
    val busy: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val checkMessage: String? = null,
    val debugMessages: Map<String, String> = emptyMap(),
    val dialog: SourceManagerDialog? = null,
    val draft: String = "",
    val effect: SourceManagerEffect? = null,
    val blockNavigation: Boolean = false,
    val draggingKey: String? = null,
    val importHistory: List<String> = emptyList(),
) {
    val canMove
        get() = sort == BookSourceSort.Default && !byDomain && !busy
}

internal enum class SourceManagerDialog {
    DELETE,
    ADD_GROUP,
    REMOVE_GROUP,
    IMPORT,
    CHECK,
}

internal data class SourceManagerEffect(
    val id: String,
    val action: String,
    val key: String = "",
    val export: SourceExport? = null,
    val keys: List<String> = emptyList(),
)

internal fun isMissingBookSourceGroupFilter(query: CharSequence?, groups: Set<String>): Boolean =
    query?.toString()?.let { it.startsWith("group:") && it.removePrefix("group:") !in groups }
        ?: false

internal fun sortSourceManagerRows(
    rows: List<SourceManagerRow>,
    sort: BookSourceSort,
    ascending: Boolean,
    domain: Boolean,
): List<SourceManagerRow> {
    if (domain)
        return rows.sortedWith(
            compareBy<SourceManagerRow> { it.host == "#" }
                .thenBy { it.host }
                .thenByDescending { it.updated }
        )
    val comparator =
        when (sort) {
            BookSourceSort.Weight -> compareBy<SourceManagerRow> { it.weight }
            BookSourceSort.Name -> Comparator { a, b -> a.name.cnCompare(b.name) }
            BookSourceSort.Url -> compareBy { it.url }
            BookSourceSort.Update -> compareByDescending { it.updated }
            BookSourceSort.Respond -> compareBy { it.response }
            BookSourceSort.Enable ->
                Comparator { a, b ->
                    (-a.enabled.compareTo(b.enabled)).takeIf { it != 0 } ?: a.name.cnCompare(b.name)
                }
            else -> compareBy { it.order }
        }
    // Enabled tie names remain ascending in both directions, matching the original page.
    if (!ascending && sort == BookSourceSort.Enable)
        return rows.sortedWith(
            Comparator { a, b ->
                a.enabled.compareTo(b.enabled).takeIf { it != 0 } ?: a.name.cnCompare(b.name)
            }
        )
    return rows.sortedWith(if (ascending) comparator else comparator.reversed())
}
