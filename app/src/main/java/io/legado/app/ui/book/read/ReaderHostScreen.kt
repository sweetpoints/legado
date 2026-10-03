package io.legado.app.ui.book.read

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.ui.book.read.page.ReadView

/** Native pagination owns its canvas geometry; every overlay is composed independently. */
@Composable
internal fun ReaderHostScreen(
    readView: ReadView,
    readMenu: ReaderMenuController,
    searchMenu: ReaderSearchControls,
    aloudControls: ReadAloudComposeControls,
    selection: ReaderSelectionState,
    selectionColor: Color,
    selectionBegin: () -> Unit,
    selectionMove: (SelectionHandle, Offset) -> Unit,
    selectionEnd: () -> Unit,
    contextMenu: ReaderContextMenuState?,
    contextDismiss: () -> Unit,
    contextAction: (String) -> Unit,
    navigationVisible: Boolean,
    navigationColor: Color,
) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val safeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val left = safeInsets.getLeft(density, direction)
    val top = safeInsets.getTop(density)
    val right = safeInsets.getRight(density, direction)
    val bottom = safeInsets.getBottom(density)
    SideEffect {
        aloudControls.updateViewport(viewport.width, viewport.height, left, top, right, bottom)
    }
    Box(Modifier.fillMaxSize().testTag("reader-host").onSizeChanged { viewport = it }) {
        AndroidView(factory = { readView }, modifier = Modifier.fillMaxSize())
        ReaderSelectionCursorsScreen(
            state = selection,
            color = selectionColor,
            begin = selectionBegin,
            move = selectionMove,
            end = selectionEnd,
        )
        Box(Modifier.fillMaxSize().navigationBarsPadding()) { readMenu.Content() }
        // Search already applies navigation padding inside its own bottom panel.
        searchMenu.Content()
        if (navigationVisible) {
            Spacer(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.systemBars)
                    .background(navigationColor)
            )
        }
        aloudControls.Content()
        ReaderContextMenuScreen(
            state = contextMenu,
            dismiss = contextDismiss,
            action = contextAction,
        )
    }
}
