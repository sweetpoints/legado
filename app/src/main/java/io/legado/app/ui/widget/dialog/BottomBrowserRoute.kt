package io.legado.app.ui.widget.dialog

import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** WebView and fullscreen video remain platform surfaces; the fragment owns their pool lease. */
@Composable
internal fun BottomBrowserRoute(
    pageContainer: FrameLayout,
    videoContainer: FrameLayout,
    fullscreen: Boolean
) {
    BottomBrowserScreen(
        fullscreen = fullscreen,
        page = {
            AndroidView(factory = { pageContainer }, modifier = Modifier.fillMaxSize())
        },
        video = {
            AndroidView(factory = { videoContainer }, modifier = Modifier.fillMaxSize())
        }
    )
}
