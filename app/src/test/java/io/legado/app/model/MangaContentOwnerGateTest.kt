package io.legado.app.model

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaContentOwnerGateTest {
    @Test
    fun oldBookCompletionCannotClearReplacementLoadingOrPublish() {
        val gate = MangaContentOwnerGate<Any>()
        val oldBook = Any()
        var currentBook: Any = oldBook
        val oldRequest = gate.capture(oldBook)
        var loading = true
        var chapter = "replacement"
        gate.invalidate { currentBook = Any() }

        val accepted =
            gate.accept(oldRequest, { currentBook }) {
                loading = false
                chapter = "obsolete"
            }

        assertFalse(accepted)
        assertTrue(loading)
        assertEquals("replacement", chapter)
    }

    @Test
    fun parsingSuspendedAcrossSameBookResetCannotPublishIntoNewEpoch() {
        val gate = MangaContentOwnerGate<Any>()
        val book = Any()
        val parsing = CountDownLatch(1)
        val finishParsing = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var loading = true
        var chapter = "initial"
        val oldRequest = gate.capture(book)
        try {
            val completion =
                executor.submit<Boolean> {
                    assertTrue(gate.accept(oldRequest, { book }) { loading = false })
                    parsing.countDown()
                    check(finishParsing.await(5, TimeUnit.SECONDS))
                    gate.accept(oldRequest, { book }) { chapter = "obsolete parsed chapter" }
                }
            assertTrue(parsing.await(5, TimeUnit.SECONDS))
            gate.invalidate {
                loading = true
                chapter = "replacement"
            }
            val newRequest = gate.capture(book)
            finishParsing.countDown()

            assertFalse(completion.get(5, TimeUnit.SECONDS))
            assertTrue(loading)
            assertEquals("replacement", chapter)
            assertTrue(
                gate.accept(newRequest, { book }) {
                    loading = false
                    chapter = "fresh chapter"
                }
            )
            assertFalse(loading)
            assertEquals("fresh chapter", chapter)
        } finally {
            finishParsing.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun changedOwnerRejectsReceiptEvenWithoutExplicitReset() {
        val gate = MangaContentOwnerGate<Any>()
        val oldBook = Any()
        val request = gate.capture(oldBook)
        assertFalse(gate.accept(request, { Any() }) { error("must not publish") })
    }
}
