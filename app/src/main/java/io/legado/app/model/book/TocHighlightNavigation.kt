package io.legado.app.model.book

import io.legado.app.data.entities.BookHighlight

fun tocHighlightChapterIndex(highlight: BookHighlight, chapterIndexes: Map<String, Int>): Int? =
    if (highlight.chapterUrl.isBlank()) highlight.chapterIndex
    else chapterIndexes[highlight.chapterUrl]

fun tocHighlightBodyPosition(highlight: BookHighlight): Int =
    (highlight.chapterPos - highlight.layoutTitleLength.coerceAtLeast(0)).coerceAtLeast(0)

fun tocHighlightAnchorText(highlight: BookHighlight): String =
    highlight.bookText
        .takeIf { highlight.chapterPosEnd - highlight.chapterPos == it.length }
        .orEmpty()

fun tocHighlightColor(highlight: BookHighlight): Int {
    val style = highlight.styleObj()
    return style.fill.takeIf { it != 0 }
        ?: style.textColor.takeIf { it != 0 }
        ?: style.underline?.color?.takeIf { it != 0 }
        ?: style.strike?.color?.takeIf { it != 0 }
        ?: style.box?.color?.takeIf { it != 0 }
        ?: style.emphasis?.color?.takeIf { it != 0 }
        ?: 0
}
