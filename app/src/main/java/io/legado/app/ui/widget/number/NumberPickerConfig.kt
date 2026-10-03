package io.legado.app.ui.widget.number

import kotlin.math.abs

/**
 * Raw integer values remain the callback contract even when labels show decimals or percentages.
 */
data class NumberPickerConfig(
    val title: String = "",
    val minimum: Int = 0,
    val maximum: Int = 0,
    val initial: Int = 0,
    val decimal: Boolean = false,
    val labels: List<String>? = null,
) {
    init {
        require(minimum >= 0 && maximum >= minimum)
        require(maximum.toLong() - minimum + 1 <= Int.MAX_VALUE)
        require(labels == null || labels.size == maximum - minimum + 1)
    }

    val count: Int
        get() = maximum - minimum + 1

    val wraps: Boolean
        get() = count >= 4

    val wheelCount: Int
        get() = if (wraps) Int.MAX_VALUE else count

    fun label(value: Int): String =
        labels?.get(value.coerceIn(minimum, maximum) - minimum)
            ?: if (decimal) (value / 10.0).toString() else value.toString()

    fun fromText(text: String, current: Int): Int {
        if (text.isEmpty()) return current.coerceIn(minimum, maximum)
        if (labels != null || decimal) {
            (minimum..maximum)
                .firstOrNull { label(it).startsWith(text, ignoreCase = true) }
                ?.let {
                    return it
                }
        }
        return (text.toIntOrNull() ?: minimum).coerceIn(minimum, maximum)
    }

    fun step(current: Int, change: Int): Int {
        val next = current.toLong() + change
        return when {
            next > maximum -> if (wraps) minimum else maximum
            next < minimum -> if (wraps) maximum else minimum
            else -> next.toInt()
        }
    }

    fun valueAt(index: Int): Int =
        minimum + if (wraps) index % count else index.coerceIn(0, count - 1)

    fun wheelIndex(value: Int, near: Int? = null): Int {
        val offset = value.coerceIn(minimum, maximum) - minimum
        if (!wraps) return offset
        val center = near ?: Int.MAX_VALUE / 2
        val base = center.toLong() / count * count + offset
        return listOf(base - count, base, base + count)
            .filter { it in 0 until Int.MAX_VALUE.toLong() }
            .minBy { abs(it - center) }
            .toInt()
    }
}
