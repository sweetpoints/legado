package io.legado.app.ui.widget.dialog.textlist

data class TextListEntry(val id: Long, val text: String)

data class TextListUiState(val title: String = "", val entries: List<TextListEntry> = emptyList()) {
    companion object {
        /**
         * Immutable argument snapshots do not reorder; repeated values and hash collisions get
         * distinct IDs.
         */
        fun from(title: String, values: List<String>): TextListUiState {
            val occurrences = mutableMapOf<Int, Int>()
            val entries = values.map { text ->
                val hash = text.hashCode()
                val occurrence = occurrences.getOrDefault(hash, 0)
                occurrences[hash] = occurrence + 1
                TextListEntry((hash.toLong() shl 32) or (occurrence.toLong() and 0xffffffffL), text)
            }
            return TextListUiState(title, entries)
        }
    }
}
