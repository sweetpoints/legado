package io.legado.app.ui.widget.dialog

import android.content.Context
import android.view.Window
import androidx.activity.ComponentDialog
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.R
import io.legado.app.ui.theme.LegadoComposeTheme

/** Reusable platform dialog with Compose content and the existing show/dismiss API. */
class WaitDialog(context: Context) : ComponentDialog(context) {
    private var message by mutableStateOf(context.getString(R.string.loading))

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCanceledOnTouchOutside(false)
    }

    override fun onStart() {
        super.onStart()
        // ComponentDialog destroys its lifecycle on dismissal. Install a new host on each show
        // so reused WaitDialog instances compose against the new lifecycle and window owners.
        setContentView(
            ComposeView(context).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
                setContent {
                    LegadoComposeTheme { WaitDialogContent(message) }
                }
            }
        )
    }

    fun setText(text: String): WaitDialog {
        message = text
        return this
    }

    fun setText(@StringRes res: Int): WaitDialog = setText(context.getString(res))
}
