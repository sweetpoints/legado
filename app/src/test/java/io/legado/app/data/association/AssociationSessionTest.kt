package io.legado.app.data.association

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssociationSessionTest {
    private val input = AssociationInput(AssociationHostKind.File, AssociationInputKind.View)

    @Test
    fun claimAndAcknowledgmentRequireCurrentOwnerAndExactReceipt() {
        val effect =
            AssociationNativeReceipt("first", 7, AssociationNativeKind.OpenBook, payload = "book")
        val initial = AssociationSession(input, generation = 7, effects = listOf(effect))
        assertNull(initial.claim("other", 7))
        assertNull(initial.claim("first", 6))
        val claimed = checkNotNull(initial.claim("first", 7))
        assertEquals(1L, claimed.revision)
        assertEquals(emptyList<AssociationNativeReceipt>(), claimed.effects)
        assertEquals(listOf(effect), claimed.claimedEffects)
        assertNull(claimed.claim("first", 7))
        assertNull(claimed.acknowledge("first", 8))
        val acknowledged = checkNotNull(claimed.acknowledge("first", 7))
        assertEquals(2L, acknowledged.revision)
        assertEquals(emptyList<AssociationNativeReceipt>(), acknowledged.claimedEffects)
        assertNull(acknowledged.acknowledge("first", 7))
    }

    @Test
    fun replacementGenerationRejectsLateResultWithoutRemovingNewEffect() {
        val old = AssociationNativeReceipt("old", 2, AssociationNativeKind.SelectDirectory)
        val next = AssociationNativeReceipt("new", 3, AssociationNativeKind.ImportDialog)
        val restored =
            AssociationSession(
                input,
                generation = 3,
                effects = listOf(next),
                claimedEffects = listOf(old),
            )
        assertNull(restored.acknowledge("old", 2))
        assertNull(restored.claim("new", 2))
        assertEquals(listOf(next), restored.effects)
        assertEquals(listOf(old), restored.claimedEffects)
    }
}
