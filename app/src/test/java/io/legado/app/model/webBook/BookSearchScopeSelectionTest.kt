package io.legado.app.model.webBook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSearchScopeSelectionTest {
    @Test
    fun encodedSourceHasOneLabelAndRemovingItFallsBackToAllSources() {
        val selection = BookSearchScopeSelection("Named::synthetic-url,with,commas")
        assertTrue(selection.isSource)
        assertEquals(listOf("Named"), selection.names)
        assertTrue(selection.hasCheckedChoice(emptyList()))
        assertEquals("", selection.remove("Named").value)
    }

    @Test
    fun groupRemovalUsesExactOriginalMembershipAndPreservesOtherGroups() {
        val selection = BookSearchScopeSelection("One,Two,OneExtra")
        assertEquals("Two,OneExtra", selection.remove("One").value)
        assertEquals("One,Two,OneExtra", selection.remove("Missing").value)
        assertEquals(listOf("One", "Two"), BookSearchScopeSelection(" One , Two ").names)
        assertTrue(selection.hasCheckedChoice(listOf("Two")))
        assertFalse(selection.hasCheckedChoice(listOf("Missing")))
    }

    @Test
    fun allSourcesRemainsCheckedWhenNoGroupsExistAndPartialSurvivalDoesNotResetScope() {
        assertTrue(BookSearchScopeSelection("").hasCheckedChoice(emptyList()))
        assertTrue(
            BookSearchScopeSelection("Removed,Remaining").hasCheckedChoice(listOf("Remaining"))
        )
        assertFalse(BookSearchScopeSelection("Removed").hasCheckedChoice(emptyList()))
    }
}
