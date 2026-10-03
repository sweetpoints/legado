package io.legado.app.model.webBook

/** Plain scope projection for Compose; no LiveData or global preference writes during rendering. */
data class BookSearchScopeSelection(val value: String) {
    val isSource: Boolean
        get() = value.contains("::")

    val names: List<String>
        get() =
            if (isSource) listOf(value.substringBefore("::"))
            else value.split(',').map(String::trim).filter(String::isNotBlank)

    fun remove(name: String): BookSearchScopeSelection {
        if (isSource) return BookSearchScopeSelection("")
        val remaining = StringBuilder()
        value.split(',').forEach { part ->
            if (part != name) {
                if (remaining.isNotEmpty()) remaining.append(',')
                remaining.append(part)
            }
        }
        return BookSearchScopeSelection(remaining.toString())
    }

    fun hasCheckedChoice(availableGroups: List<String>): Boolean {
        return isSource || names.isEmpty() || availableGroups.any { it in names }
    }
}
