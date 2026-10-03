package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.help.HighlightStyle.Shadow
import io.legado.app.utils.setLayout

class ShadowEditDialog : BaseComposeDialogFragment() {

    interface Callback {
        fun onShadowChanged(shadow: Shadow)
    }

    private val initialShadow: Shadow
        get() =
            Shadow(
                radius = arguments?.getFloat(ARG_RADIUS) ?: Shadow().radius,
                dx = arguments?.getFloat(ARG_DX) ?: Shadow().dx,
                dy = arguments?.getFloat(ARG_DY) ?: Shadow().dy,
                color = arguments?.getInt(ARG_COLOR) ?: Shadow().color,
            )

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun Content() {
        ShadowEditRoute(
            initialShadow,
            {
                (parentFragment as? Callback)?.onShadowChanged(it)
                dismiss()
            },
            { dismiss() },
            Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .8f),
        )
    }

    companion object {
        private const val ARG_RADIUS = "radius"
        private const val ARG_DX = "dx"
        private const val ARG_DY = "dy"
        private const val ARG_COLOR = "color"

        fun show(fragmentManager: androidx.fragment.app.FragmentManager, shadow: Shadow) {
            ShadowEditDialog()
                .apply {
                    arguments =
                        Bundle().apply {
                            putFloat(ARG_RADIUS, shadow.radius)
                            putFloat(ARG_DX, shadow.dx)
                            putFloat(ARG_DY, shadow.dy)
                            putInt(ARG_COLOR, shadow.color)
                        }
                }
                .show(fragmentManager, ShadowEditDialog::class.simpleName)
        }
    }
}
