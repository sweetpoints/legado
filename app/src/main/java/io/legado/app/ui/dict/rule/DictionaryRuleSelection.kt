package io.legado.app.ui.dict.rule

/** Original ToggleAndReverse: the anchor sets range state; reversed rows take the anchor's original state. */
class DictionaryRangeSelection(val names: List<String>, val initial: Set<String>, val anchor: Int) {
    private var visitedStart = anchor
    private var visitedEnd = anchor
    fun at(index: Int): Set<String> {
        if (names.isEmpty() || anchor !in names.indices) return initial
        val end = index.coerceIn(0, names.lastIndex)
        val start = minOf(anchor, end)
        val last = maxOf(anchor, end)
        visitedStart = minOf(visitedStart, start)
        visitedEnd = maxOf(visitedEnd, last)
        val anchorWasSelected = names[anchor] in initial
        return initial.toMutableSet().apply {
            (visitedStart..visitedEnd).forEach { position ->
                val selected = if (position in start..last) !anchorWasSelected else anchorWasSelected
                if (selected) add(names[position]) else remove(names[position])
            }
        }.toSet()
    }
}
