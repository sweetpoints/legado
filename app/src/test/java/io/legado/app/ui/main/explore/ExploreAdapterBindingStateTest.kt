package io.legado.app.ui.main.explore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner boundary replaces recycled View binding tokens with stable source identity. */
class ExploreAdapterBindingStateTest {
    @Test
    fun onlyLatestSourceOwnerAcceptsPanelCompletion() {
        assertTrue(acceptsExplorePanel(2, 2, "source", "source"))
        assertFalse(acceptsExplorePanel(1, 2, "source", "source"))
        assertFalse(acceptsExplorePanel(2, 2, "source", "other"))
        assertFalse(acceptsExplorePanel(2, 2, "source", null))
    }
}
