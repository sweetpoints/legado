package io.legado.app.model.webBook

/** Global search preferences remain distinct from an Activity's private editable draft. */
data class BookSearchPreferences(
    val precision: Boolean = false,
    val showReadRecord: Boolean = true,
    val resultFilter: String = "",
    val scope: String = "",
    val loadCoverOnlyWifi: Boolean = false,
)
