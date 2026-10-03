package io.legado.app.ui.book.read.config

import android.animation.ValueAnimator
import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.preferences.AppPaddingSettingsRepository
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.setLayout

class PaddingConfigDialog : BaseComposeDialogFragment() {
    private val viewModel by
        viewModels<PaddingSettingsViewModel> {
            viewModelFactory {
                initializer {
                    PaddingSettingsViewModel(
                        AppPaddingSettingsRepository(),
                        createSavedStateHandle(),
                    )
                }
            }
        }
    private val visibility = PaddingPanelVisibility()
    private var alphaAnimator: ValueAnimator? = null

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            attributes = attributes.apply {
                dimAmount = 0f
                gravity = Gravity.CENTER
            }
        }
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        val host = activity as? ReadBookActivity ?: return
        visibility.acquire(
            object : PaddingPanelVisibility.Owner {
                override var bottomDialog: Int
                    get() = host.bottomDialog
                    set(value) {
                        host.bottomDialog = value
                    }
            }
        )
    }

    @Composable
    override fun Content() {
        val background = requireContext().bottomBackground
        val foreground = requireContext().getPrimaryTextColor(ColorUtils.isColorLight(background))
        PaddingSettingsRoute(
            viewModel,
            Color(background),
            Color(foreground),
            ::ghostWindow,
            Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .8f).dp),
        )
    }

    override fun onDestroyView() {
        viewModel.viewDestroyed()
        visibility.release()
        super.onDestroyView()
        restoreWindow()
    }

    override fun onDismiss(dialog: DialogInterface) {
        viewModel.dismissed(activity?.isChangingConfigurations == true)
        visibility.release()
        restoreWindow()
        super.onDismiss(dialog)
    }

    private fun ghostWindow(ghost: Boolean) {
        val window = dialog?.window ?: return
        alphaAnimator?.cancel()
        alphaAnimator =
            ValueAnimator.ofFloat(window.attributes.alpha, if (ghost) .25f else 1f).apply {
                duration = 120
                addUpdateListener { value ->
                    window.attributes =
                        window.attributes.apply { alpha = value.animatedValue as Float }
                }
                start()
            }
    }

    private fun restoreWindow() {
        alphaAnimator?.cancel()
        alphaAnimator = null
        dialog?.window?.let { window -> window.attributes = window.attributes.apply { alpha = 1f } }
    }
}
