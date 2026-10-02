package io.legado.app.ui.main

import io.legado.app.ui.navigation.MainDestination

/** Main shell state. Feature data remains owned by each feature's ViewModel. */
data class MainUiState(
    val destinations: List<MainDestination> = MainDestination.entries.toList(),
    val selectedDestination: MainDestination = MainDestination.Bookshelf,
    val skinName: String = "",
    val skinRevision: Int = 0,
    val isEInkMode: Boolean = false,
) {
    val selectedIndex: Int get() = destinations.indexOf(selectedDestination).coerceAtLeast(0)

    fun withVisibleDestinations(showDiscovery: Boolean, showRss: Boolean): MainUiState {
        val visible = MainDestination.entries.filter {
            when (it) {
                MainDestination.Explore -> showDiscovery
                MainDestination.Rss -> showRss
                else -> true
            }
        }
        return copy(
            destinations = visible,
            selectedDestination = selectedDestination.takeIf { it in visible } ?: MainDestination.Bookshelf,
        )
    }
}
