package io.legado.app.help.gsyVideo

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.theme.LegadoComposeTheme

internal data class VideoFeedbackState(val label: String = "", val fraction: Float = 0f)

/** The native gesture owns this window; it never takes touch or focus from the media surface. */
internal class VideoFeedbackDialog(context: Context) :
    ComponentDialog(context, R.style.dialog_style) {
    var feedback by mutableStateOf(VideoFeedbackState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            ComposeView(context).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
                setContent { LegadoComposeTheme { VideoFeedbackScreen(feedback) } }
            }
        )
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            )
        }
    }

    fun showOver(mediaSurface: View) {
        if (!mediaSurface.isAttachedToWindow || isShowing) return
        // Gesture moves update only Compose state; avoid WindowManager layout work on every frame.
        show()
        val surfaceLocation = IntArray(2)
        mediaSurface.getLocationOnScreen(surfaceLocation)
        window?.attributes =
            window?.attributes?.apply {
                gravity = Gravity.TOP or Gravity.START
                width = mediaSurface.width
                height = mediaSurface.height
                x = surfaceLocation[0]
                y = surfaceLocation[1]
            }
    }
}

@Composable
internal fun VideoFeedbackScreen(state: VideoFeedbackState, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(shape = androidx.compose.material3.MaterialTheme.shapes.medium) {
            Column(
                Modifier.widthIn(min = 160.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.label)
                LinearProgressIndicator(progress = { state.fraction.coerceIn(0f, 1f) })
            }
        }
    }
}
