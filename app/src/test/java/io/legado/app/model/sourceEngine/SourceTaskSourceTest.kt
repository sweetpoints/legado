package io.legado.app.model.sourceEngine

import io.legado.app.data.entities.BookSource
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceTaskSourceTest {
    private fun source() =
        BookSource(
            bookSourceUrl = "https://fixture.invalid/dao-key",
            bookSourceName = "Registered task source fixture",
        )

    @Test
    fun guestMaskOverridesItsParentWithoutLosingOtherContextElements() {
        val actor = SourceTaskSource(source(), "owner")
        val bound = EmptyCoroutineContext + actor + SourceTaskSourceSuppression(false)
        val guest = bound + SourceTaskSourceSuppression(true)
        assertSame(actor, guest[SourceTaskSource])
        org.junit.Assert.assertEquals(true, guest[SourceTaskSourceSuppression]?.suppressed)
        org.junit.Assert.assertEquals(false, bound[SourceTaskSourceSuppression]?.suppressed)
    }

    @Test
    fun carrierIdentityCanDifferFromDaoKeyWhileRetainingTheActualSource() {
        val source = source()
        val actor = SourceTaskSource(source, "carrier-source-id")
        val context = EmptyCoroutineContext + actor
        assertSame(actor, context[SourceTaskSource])
        assertSame(source, actor.sourceForTask("carrier-source-id"))
    }

    @Test
    fun aDifferentRegisteredTaskCannotBorrowTheSource() {
        val actor = SourceTaskSource(source(), "owner-a")
        assertThrows(IllegalArgumentException::class.java) { actor.sourceForTask("owner-b") }
    }

    @Test
    fun anEmptyRegisteredIdentityIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SourceTaskSource(source(), " ") }
    }
}
