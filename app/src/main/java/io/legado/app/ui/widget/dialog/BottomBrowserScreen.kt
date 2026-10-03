package io.legado.app.ui.widget.dialog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** The browser has no chrome: the entire sheet belongs to the page or its fullscreen video. */
@Composable
internal fun BottomBrowserScreen(
    fullscreen: Boolean,
    page: @Composable () -> Unit,
    video: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().testTag("bottom-browser")) {
        page()
        // An empty AndroidView above the page can intercept page touches. Only mount the
        // fullscreen platform surface while the browser has a custom view to display.
        if (fullscreen) video()
    }
}
