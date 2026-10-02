package io.legado.app.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.navigation.MainDestination
import io.legado.app.ui.theme.LocalLegadoColors

/** State and callbacks only: no Activity, preferences, database or ViewModel access. */
@Composable
fun MainScreen(
    state: MainUiState,
    updatingBooks: Int,
    skinIcons: Map<MainDestination, MainSkinIcon>,
    transparentNavigation: Boolean,
    onDestinationClick: (MainDestination) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalLegadoColors.current
    Column(modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        Column(
            Modifier.fillMaxWidth()
                .background(if (transparentNavigation) Color.Transparent else colors.bottomBackground)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            if (state.isEInkMode) HorizontalDivider(color = colors.textPrimary, thickness = 1.dp)
            Row(Modifier.fillMaxWidth().height(50.dp).selectableGroup()) {
                state.destinations.forEach { destination ->
                    val selected = destination == state.selectedDestination
                    Box(
                        Modifier.weight(1f).height(50.dp)
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = { onDestinationClick(destination) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        BadgedBox(badge = {
                            if (destination == MainDestination.Bookshelf && updatingBooks != 0) {
                                Badge(
                                    containerColor = colors.accent,
                                    contentColor = if (colors.accent.luminance() > 0.5f) Color.Black else Color.White,
                                ) { Text(updatingBooks.toString()) }
                            }
                        }) {
                            val title = stringResource(destination.titleRes)
                            val skin = skinIcons[destination]
                            if (skin != null) {
                                Image(
                                    bitmap = if (selected) skin.selected else skin.unselected,
                                    contentDescription = title,
                                    modifier = Modifier.size(30.dp),
                                )
                            } else {
                                Icon(
                                    painterResource(destination.iconRes),
                                    contentDescription = title,
                                    tint = if (selected) colors.accent else colors.textSecondary,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

data class MainSkinIcon(val selected: ImageBitmap, val unselected: ImageBitmap)

private val MainDestination.titleRes: Int
    get() = when (this) {
        MainDestination.Bookshelf -> R.string.bookshelf
        MainDestination.Explore -> R.string.discovery
        MainDestination.Rss -> R.string.rss
        MainDestination.My -> R.string.my
    }

private val MainDestination.iconRes: Int
    get() = when (this) {
        MainDestination.Bookshelf -> R.drawable.ic_bottom_books
        MainDestination.Explore -> R.drawable.ic_bottom_explore
        MainDestination.Rss -> R.drawable.ic_bottom_rss_feed
        MainDestination.My -> R.drawable.ic_bottom_person
    }
