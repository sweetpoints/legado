package io.legado.app.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.R
import io.legado.app.ui.theme.LegadoComposeTheme

/** XML-free dialog content with the existing show/dismiss and e-ink window behavior. */
abstract class BaseComposeDialogFragment : BaseDialogFragment(0) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    final override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            id = R.id.compose_dialog_content
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { LegadoComposeTheme { this@BaseComposeDialogFragment.Content() } }
        }

    final override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        onComposeCreated(savedInstanceState)
    }

    @Composable protected abstract fun Content()

    protected open fun onComposeCreated(savedInstanceState: Bundle?) = Unit
}
