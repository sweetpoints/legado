package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.preferences.PreferenceClickActionSettingsRepository
import io.legado.app.ui.book.read.ReadBookActivity

/** Full-screen nine-region reader click configuration. */
class ClickActionConfigDialog : BaseComposeDialogFragment() {
    private val viewModel by
        viewModels<ClickActionSettingsViewModel> {
            viewModelFactory {
                initializer {
                    ClickActionSettingsViewModel(
                        PreferenceClickActionSettingsRepository(requireContext()),
                        createSavedStateHandle(),
                    )
                }
            }
        }
    private var countedActivity: ReadBookActivity? = null

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        releaseCounter()
        (activity as? ReadBookActivity)?.let {
            it.bottomDialog++
            countedActivity = it
        }
    }

    @Composable
    override fun Content() {
        ClickActionSettingsRoute(viewModel, ::dismissAllowingStateLoss)
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (activity?.isChangingConfigurations != true) viewModel.close()
        releaseCounter()
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        releaseCounter()
        super.onDestroyView()
    }

    override fun onDestroy() {
        if (activity?.isChangingConfigurations != true) viewModel.close()
        super.onDestroy()
    }

    private fun releaseCounter() {
        val owner = countedActivity ?: return
        countedActivity = null
        owner.bottomDialog = (owner.bottomDialog - 1).coerceAtLeast(0)
    }
}
