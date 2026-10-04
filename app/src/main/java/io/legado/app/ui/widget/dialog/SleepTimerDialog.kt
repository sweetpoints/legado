package io.legado.app.ui.widget.dialog

import io.legado.app.utils.resizeForIme

import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.preferences.AppSleepTimerPreferences
import io.legado.app.data.preferences.SleepTimerMode
import io.legado.app.ui.widget.dialog.sleeptimer.SleepTimerRoute
import io.legado.app.ui.widget.dialog.sleeptimer.SleepTimerViewModel
import io.legado.app.utils.setLayout

class SleepTimerDialog : BaseComposeDialogFragment() {
    interface CallBack {
        fun onSleepTimerMinute(minute: Int)

        fun onSleepTimerChapter(count: Int)
    }

    private val viewModel by
        viewModels<SleepTimerViewModel> {
            viewModelFactory {
                initializer {
                    SleepTimerViewModel(
                        AppSleepTimerPreferences(requireContext()),
                        createSavedStateHandle(),
                    )
                }
            }
        }
    private val callBack
        get() = (parentFragment as? CallBack) ?: (activity as? CallBack)

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog?.window?.resizeForIme()
    }

    @Composable
    override fun Content() {
        SleepTimerRoute(
            viewModel,
            { selection ->
                when (selection.mode) {
                    SleepTimerMode.Minutes -> callBack?.onSleepTimerMinute(selection.value)
                    SleepTimerMode.Chapters -> callBack?.onSleepTimerChapter(selection.value)
                }
            },
            ::dismissAllowingStateLoss,
            Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .8f),
        )
    }

    companion object {
        fun newInstance(minute: Int, chapter: Int, useEpisodes: Boolean = false) =
            SleepTimerDialog().apply {
                arguments =
                    Bundle().apply {
                        putInt("minute", minute)
                        putInt("chapter", chapter)
                        putBoolean("episodes", useEpisodes)
                    }
            }
    }
}
