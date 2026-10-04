package io.legado.app.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.ui.navigation.MainDestination
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun MainDestinationPager(
    state: MainUiState,
    onDestinationSelected: (MainDestination) -> Unit,
    content: @Composable (MainDestination, Modifier) -> Unit,
) {
    val destinations = state.destinations
    val pager = rememberPagerState(state.selectedIndex) { destinations.size }
    val select by rememberUpdatedState(onDestinationSelected)

    LaunchedEffect(state.selectedDestination, destinations) {
        if (pager.currentPage != state.selectedIndex) pager.scrollToPage(state.selectedIndex)
    }
    LaunchedEffect(pager, destinations) {
        snapshotFlow { pager.settledPage }
            .distinctUntilChanged()
            .collect { page -> destinations.getOrNull(page)?.let(select) }
    }

    HorizontalPager(
        state = pager,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = destinations.size,
        key = { destinations[it].key },
    ) { page ->
        val destination = destinations[page]
        key(destination) {
            Box(Modifier.fillMaxSize().focusProperties {
                canFocus = destination == state.selectedDestination
            }) {
                DestinationLifecycle(destination == state.selectedDestination) {
                    content(destination, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun DestinationLifecycle(selected: Boolean, content: @Composable () -> Unit) {
    val parent = LocalLifecycleOwner.current
    val owner = remember(parent) { MainTabLifecycleOwner() }
    val isSelected by rememberUpdatedState(selected)
    DisposableEffect(parent, owner) {
        fun update() = owner.update(parent.lifecycle.currentState, isSelected)
        val observer = LifecycleEventObserver { _, _ -> update() }
        parent.lifecycle.addObserver(observer)
        update()
        onDispose {
            parent.lifecycle.removeObserver(observer)
            owner.update(Lifecycle.State.DESTROYED, selected = false)
        }
    }
    LaunchedEffect(parent, owner, selected) {
        owner.update(parent.lifecycle.currentState, selected)
    }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content)
}
