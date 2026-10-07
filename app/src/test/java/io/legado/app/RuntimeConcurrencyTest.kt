package io.legado.app

import io.legado.app.data.entities.BookChapter
import io.legado.app.ui.book.read.page.entities.TextChapter
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeConcurrencyTest {

    @Test
    fun layoutPageStorageSupportsConcurrentAppendAndIteration() {
        val chapter =
            TextChapter(
                chapter = BookChapter(),
                position = 0,
                title = "test",
                chaptersSize = 1,
                sameTitleRemoved = false,
                isVip = false,
                isPay = false,
                effectiveReplaceRules = null,
            )
        val field =
            TextChapter::class.java.getDeclaredField("textPages").apply {
                isAccessible = true
            }
        val storage = field.get(chapter)
        assertTrue(storage is CopyOnWriteArrayList<*>)

        @Suppress("UNCHECKED_CAST") val pages = storage as CopyOnWriteArrayList<Any>
        val writer =
            thread(start = true) {
                repeat(2_000) { pages.add(it) }
            }
        while (writer.isAlive) {
            pages.forEach { assertTrue(it is Int) }
            pages.getOrNull(pages.lastIndex)
        }
        writer.join()
        assertEquals(2_000, pages.size)
    }
}
