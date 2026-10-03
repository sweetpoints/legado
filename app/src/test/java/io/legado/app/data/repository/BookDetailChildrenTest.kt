package io.legado.app.data.repository

import org.junit.Assert.*
import org.junit.Test

class BookDetailChildrenTest {
    private val owner = BookDetailChildOwner("token", BookDetailChildKind.Toc, "book")

    @Test
    fun nativeResultBeforePageLoadRetainsLargeAnchorAndCanBeAppliedAfterRestore() {
        val anchor = "word".repeat(100_000)
        val result =
            BookDetailChildResult(
                owner,
                position = BookDetailPosition(2, 10, 0, 2),
                highlightTitleLength = 12,
                highlightAnchor = anchor,
            )
        val restored = BookDetailChildren().owner(owner).result(result).copy()
        assertEquals(anchor, restored.pending.single().highlightAnchor)
        assertEquals(2, restored.pending.single().position!!.index)
    }

    @Test
    fun duplicateResultAndCompletedOwnerCannotDeliverAgain() {
        val result = BookDetailChildResult(owner, value = "first")
        val accepted =
            BookDetailChildren()
                .owner(owner)
                .result(result)
                .result(result.copy(value = "duplicate"))
        assertEquals("first", accepted.pending.single().value)
        val finished = accepted.complete(owner.token)
        assertTrue(finished.pending.isEmpty())
        assertEquals(finished, finished.result(result).owner(owner))
    }

    @Test
    fun replacedOwnerRejectsLateOldResultWithoutChangingCurrentOwner() {
        val next = owner.copy(token = "new")
        val before = BookDetailChildren().owner(owner).owner(next)
        assertEquals(before, before.result(BookDetailChildResult(owner, value = "late")))
        assertEquals(next, before.owners.single())
    }

    @Test
    fun resultMustMatchCompleteOwnerIncludingBookAndSource() {
        val before = BookDetailChildren().owner(owner)
        assertEquals(before, before.result(BookDetailChildResult(owner.copy(bookUrl = "other"))))
        assertEquals(before, before.result(BookDetailChildResult(owner.copy(sourceUrl = "other"))))
    }

    @Test
    fun returnedUnappliedResultCannotBeDisplacedByAnotherLauncher() {
        val before = BookDetailChildren().owner(owner).result(BookDetailChildResult(owner))
        assertThrows(IllegalStateException::class.java) { before.owner(owner.copy(token = "next")) }
        assertEquals(listOf(owner.token), before.complete(owner.token).completed)
    }

    @Test
    fun nullableVariableAndCancellationAreSeparateResults() {
        val variable = owner.copy(kind = BookDetailChildKind.Variable)
        val clear = BookDetailChildResult(variable, value = null, canceled = false)
        assertFalse(BookDetailChildren().owner(variable).result(clear).pending.single().canceled)
        assertTrue(
            BookDetailChildren()
                .owner(owner)
                .result(BookDetailChildResult(owner, canceled = true))
                .pending
                .single()
                .canceled
        )
    }
}
