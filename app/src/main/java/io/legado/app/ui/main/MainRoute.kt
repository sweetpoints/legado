package io.legado.app.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.help.BottomBarSkinManager
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.ui.navigation.MainDestination
import io.legado.app.ui.theme.LocalLegadoColors
import io.legado.app.ui.theme.Material3SystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Connects lifecycle-aware feature state and platform resources to the pure screen. */
@Composable
fun MainRoute(
    viewModel: MainViewModel,
    onDestinationReselected: (MainDestination) -> Unit,
    content: @Composable (MainUiState) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val updatingBooks by viewModel.onUpBooksLiveData.observeAsState(0)
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val sizePx = with(LocalDensity.current) { 30.dp.roundToPx() }
    val transparentNavigation = remember(context) { context.transparentNavBar }
    val icons by
        produceState<Map<MainDestination, MainSkinIcon>>(
            initialValue = emptyMap(),
            state.skinName,
            state.skinRevision,
            sizePx,
        ) {
            // Decode skin files outside composition and off the main thread.
            value = emptyMap()
            value =
                withContext(Dispatchers.IO) {
                    if (state.skinName.isEmpty() || !BottomBarSkinManager.hasSkin(state.skinName)) {
                        emptyMap()
                    } else {
                        MainDestination.entries
                            .mapNotNull { destination ->
                                BottomBarSkinManager.getStateDrawable(
                                        state.skinName,
                                        destination.skinSlot,
                                        sizePx,
                                    )
                                    ?.let { drawable ->
                                        drawable.state = intArrayOf(android.R.attr.state_checked)
                                        val selected =
                                            drawable.toBitmap(sizePx, sizePx).asImageBitmap()
                                        drawable.state = intArrayOf()
                                        val unselected =
                                            drawable.toBitmap(sizePx, sizePx).asImageBitmap()
                                        destination to MainSkinIcon(selected, unselected)
                                    }
                            }
                            .toMap()
                    }
                }
        }
    Material3SystemBars(
        MaterialTheme.colorScheme.surface,
        if (transparentNavigation) MaterialTheme.colorScheme.background
        else LocalLegadoColors.current.bottomBackground,
    )
    MainScreen(
        state = state,
        updatingBooks = updatingBooks,
        skinIcons = icons,
        transparentNavigation = transparentNavigation,
        statusBarColor = MaterialTheme.colorScheme.surface,
        onDestinationClick = { destination ->
            if (destination == state.selectedDestination) onDestinationReselected(destination)
            else {
                // A focused field on the old pager page can bring that page back into view.
                focusManager.clearFocus(force = true)
                keyboard?.hide()
                viewModel.selectDestination(destination)
            }
        },
    ) {
        content(state)
    }
}
