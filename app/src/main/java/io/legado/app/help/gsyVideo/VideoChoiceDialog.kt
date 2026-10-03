package io.legado.app.help.gsyVideo

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.R
import io.legado.app.ui.theme.LegadoComposeTheme

/** Owns Compose lifecycle even when invoked by the floating-window service. */
abstract class VideoChoiceDialog(context: Context, private val widthFraction: Float) :
    ComponentDialog(context, R.style.dialog_style) {
    protected var choices: List<String> = emptyList()
    protected var heading: String = ""
    protected var initialSelection: Int = -1
    private var accepted = false
    private var stopped = false

    protected abstract fun select(index: Int)

    protected abstract fun finished()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            ComposeView(context).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
                setContent {
                    LegadoComposeTheme {
                        VideoChoiceScreen(
                            heading,
                            choices,
                            initialSelection,
                            onSelect = { index ->
                                if (!accepted && isShowing && index in choices.indices) {
                                    accepted = true
                                    dismiss()
                                    select(index)
                                }
                            },
                        )
                    }
                }
            }
        )
        window?.attributes =
            window?.attributes?.apply {
                val display = context.resources.displayMetrics
                width = (display.widthPixels * widthFraction).toInt()
                height = display.heightPixels
                gravity = Gravity.END
            }
    }

    override fun onStart() {
        stopped = false
        accepted = false
        super.onStart()
    }

    override fun onStop() {
        if (!stopped) {
            stopped = true
            finished()
        }
        super.onStop()
    }
}
