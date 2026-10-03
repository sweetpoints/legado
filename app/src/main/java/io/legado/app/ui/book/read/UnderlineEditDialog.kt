package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.help.HighlightStyle.Kind
import io.legado.app.help.HighlightStyle.Underline
import io.legado.app.utils.setLayout

class UnderlineEditDialog : BaseComposeDialogFragment() {

    interface Callback {
        fun onUnderlineChanged(underline: Underline)
    }

    private val initialUnderline: Underline
        get() =
            Underline(
                    kind =
                        arguments?.getString(ARG_KIND)?.let {
                            runCatching { Kind.valueOf(it) }.getOrNull()
                        } ?: Underline().kind,
                    color = arguments?.getInt(ARG_COLOR) ?: Underline().color,
                    width = arguments?.getFloat(ARG_WIDTH) ?: Underline().width,
                    distance = arguments?.getFloat(ARG_DISTANCE) ?: Underline().distance,
                )
                .normalized()

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun Content() {
        UnderlineEditRoute(
            initialUnderline,
            {
                (parentFragment as? Callback)?.onUnderlineChanged(it)
                dismiss()
            },
            { dismiss() },
            Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .8f),
        )
    }

    companion object {
        private const val ARG_KIND = "kind"
        private const val ARG_COLOR = "color"
        private const val ARG_WIDTH = "width"
        private const val ARG_DISTANCE = "distance"

        fun show(fragmentManager: androidx.fragment.app.FragmentManager, underline: Underline) {
            UnderlineEditDialog()
                .apply {
                    arguments =
                        Bundle().apply {
                            putString(ARG_KIND, underline.kind.name)
                            putInt(ARG_COLOR, underline.color)
                            putFloat(ARG_WIDTH, underline.width)
                            putFloat(ARG_DISTANCE, underline.distance)
                        }
                }
                .show(fragmentManager, UnderlineEditDialog::class.simpleName)
        }
    }
}
