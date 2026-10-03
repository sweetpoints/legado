package io.legado.app.ui.main

import io.legado.app.ui.navigation.MainDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainUiStateTest {
    @Test
    fun hidingTabsKeepsTheSelectedDestinationInsteadOfItsOldPosition() {
        val original = MainUiState(selectedDestination = MainDestination.My)
        val hidden = original.withVisibleDestinations(showDiscovery = false, showRss = false)
        assertEquals(listOf(MainDestination.Bookshelf, MainDestination.My), hidden.destinations)
        assertEquals(MainDestination.My, hidden.selectedDestination)
        assertEquals(1, hidden.selectedIndex)
        assertEquals(
            MainDestination.My,
            hidden.withVisibleDestinations(true, true).selectedDestination,
        )
    }

    @Test
    fun disablingTheSelectedTabReturnsToTheBookshelf() {
        for (destination in listOf(MainDestination.Explore, MainDestination.Rss)) {
            val state =
                MainUiState(selectedDestination = destination).withVisibleDestinations(false, false)
            assertEquals(MainDestination.Bookshelf, state.selectedDestination)
            assertEquals(0, state.selectedIndex)
        }
    }

    @Test
    fun everyVisibilityCombinationHasAnAvailableSelection() {
        for (showDiscovery in listOf(false, true)) {
            for (showRss in listOf(false, true)) {
                for (selected in MainDestination.entries) {
                    val state =
                        MainUiState(selectedDestination = selected)
                            .withVisibleDestinations(showDiscovery, showRss)
                    assertTrue(state.selectedDestination in state.destinations)
                    assertEquals(state.selectedDestination, state.destinations[state.selectedIndex])
                }
            }
        }
    }

    @Test
    fun savedDestinationKeysAreStableAndUnknownValuesRecover() {
        MainDestination.entries.forEach { assertEquals(it, MainDestination.fromKey(it.key)) }
        assertEquals(MainDestination.Bookshelf, MainDestination.fromKey("removed-destination"))
        assertEquals(MainDestination.Bookshelf, MainDestination.fromKey(null))
    }
}
