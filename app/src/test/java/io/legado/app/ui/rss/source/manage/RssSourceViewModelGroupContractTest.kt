package io.legado.app.ui.rss.source.manage

import io.legado.app.utils.renameGroupExact
import org.junit.Assert.*
import org.junit.Test

/** The named-group manager retains exact-member rename/delete rather than substring matching. */
class RssSourceViewModelGroupContractTest {
    @Test fun exactDeletionKeepsOtherNormalizedMembers() {
        assertEquals("AA,History", "A；AA，History".renameGroupExact("A", null))
        assertNull("AA,History".renameGroupExact("A", null))
    }
    @Test fun renamePreservesUnrelatedGroupAndLiteralWildcard() {
        assertEquals("History,New", "%；History".renameGroupExact("%", "New"))
        assertNull("AA,History".renameGroupExact("_", "New"))
    }
}
