package io.legado.app.lib.dialogs

import io.legado.app.utils.resizeForIme

import android.content.Context
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.R
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout

/** Hosts the passphrase form in a dialog-owned lifecycle and Compose composition. */
internal class SourceSharePassphraseComposeDialog(
    context: Context,
    private val passphrase: String,
) : ComponentDialog(context, R.style.dialog_style) {

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(
            ComposeView(context).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
                setContent {
                    LegadoComposeTheme {
                        SourceSharePassphraseScreen(
                            passphrase = passphrase,
                            onCopy = {
                                context.sendToClip(passphrase)
                                dismiss()
                            },
                        )
                    }
                }
            }
        )
        setCanceledOnTouchOutside(true)
    }

    override fun onStart() {
        super.onStart()
        window?.apply {
            setBackgroundDrawableResource(R.color.transparent)
            resizeForIme()
        }
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
