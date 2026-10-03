package io.legado.app.ui.widget.dialog

import android.content.Context
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.ui.widget.dialog.urloption.UrlOptionDraft
import io.legado.app.ui.widget.dialog.urloption.UrlOptionRoute
import io.legado.app.utils.setLayout

class UrlOptionDialog(context: Context, private val success: (String) -> Unit) :
    ComponentDialog(context) {
    private var draft = UrlOptionDraft()

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    override fun onStart() {
        super.onStart()
        // Dismissal destroys ComponentDialog's lifecycle; reuse installs a fresh composition.
        setContentView(
            ComposeView(context).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
                setContent {
                    LegadoComposeTheme {
                        UrlOptionRoute(
                            AppConst.charsets,
                            success,
                            ::dismiss,
                            initialDraft = draft,
                            onDraftChanged = { draft = it },
                        )
                    }
                }
            }
        )
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.apply {
            setBackgroundDrawableResource(R.color.transparent)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
}
