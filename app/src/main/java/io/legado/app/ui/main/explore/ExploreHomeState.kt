package io.legado.app.ui.main.explore

import androidx.annotation.Keep

internal data class ExploreHomeSource(
    val url: String,
    val name: String,
    val hasLogin: Boolean,
    val revision: String = "",
)

internal data class ExploreControlStyle(
    val grow: Float = 0f,
    val shrink: Float = 1f,
    val basis: Float = -1f,
    val align: String = "auto",
    val justify: String = "auto",
    val wrapBefore: Boolean = false,
)

internal data class ExploreHomeControl(
    val id: Int,
    val type: String,
    val title: String,
    val label: String,
    val url: String?,
    val choices: List<String>,
    val value: String,
    val style: ExploreControlStyle,
    val action: String? = null,
)

internal data class ExploreHomePanel(val controls: List<ExploreHomeControl>)

internal data class ExploreHomeState(
    val query: String = "",
    val sources: List<ExploreHomeSource> = emptyList(),
    val groups: List<String> = emptyList(),
    val expandedUrl: String? = null,
    val controls: List<ExploreHomeControl> = emptyList(),
    val loading: Boolean = true,
    val sessionLoaded: Boolean = false,
    val panelLoading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val deleteUrl: String? = null,
    val errorText: String? = null,
    val showFastScroller: Boolean = false,
    val eInkMode: Boolean = false,
    val scrollRequest: Long = 0,
    val scrollTarget: Int = 0,
    val effect: ExploreHomeEffect? = null,
)

@Keep
internal data class ExploreHomeEffect(
    val id: String,
    val action: String,
    val sourceUrl: String = "",
    val title: String = "",
    val value: String = "",
    val values: Map<String, String> = emptyMap(),
)

internal fun exploreGroupFromQuery(query: CharSequence?): String? =
    query?.toString()?.takeIf { it.startsWith("group:") }?.removePrefix("group:")

internal fun isExploreAllQuery(query: CharSequence?): Boolean = exploreGroupFromQuery(query) == null

internal fun selectedExploreGroup(query: CharSequence?, groups: Set<String>): String? =
    exploreGroupFromQuery(query)?.takeIf(groups::contains)

/** A new row owner must reject both a previous source and its previous loading generation. */
internal fun acceptsExplorePanel(
    expectedGeneration: Long,
    generation: Long,
    expectedUrl: String,
    expandedUrl: String?,
): Boolean = expectedGeneration == generation && expectedUrl == expandedUrl

internal val exploreHomeRowActions = listOf("edit", "top", "login", "search", "refresh", "delete")

internal fun visibleExploreHomeRowActions(hasLogin: Boolean): List<String> =
    exploreHomeRowActions.filter {
        it != "login" || hasLogin
    }
