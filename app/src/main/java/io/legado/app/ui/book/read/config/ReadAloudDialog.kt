package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxWidth
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
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.preferences.AppReadAloudControlRepository
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.widget.dialog.SleepTimerDialog
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.observeEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

class ReadAloudDialog : BaseComposeDialogFragment(), SpeakEngineDialog.CallBack, SleepTimerDialog.CallBack {
    private val callBack get() = activity as? CallBack
    private val viewModel by viewModels<ReadAloudViewModel> {
        viewModelFactory { initializer { ReadAloudViewModel(AppReadAloudControlRepository(requireContext()), createSavedStateHandle()) } }
    }
    private val lease = ReadAloudDialogLease()
    private var countOwner: ReadBookActivity? = null

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        val host = activity as? ReadBookActivity ?: return
        if (!lease.acquire(host.bottomDialog)) { dismissAllowingStateLoss(); return }
        countOwner = host
        host.bottomDialog++
    }
    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(R.color.background)
            decorView.setPadding(0, 0, 0, 0)
            attributes = attributes.apply { dimAmount = 0f; gravity = Gravity.BOTTOM }
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
    @Composable override fun Content() {
        val background = requireContext().bottomBackground
        val foreground = requireContext().getPrimaryTextColor(ColorUtils.isColorLight(background))
        ReadAloudRoute(viewModel, Color(background), Color(foreground),
            { isAdded && !childFragmentManager.isStateSaved }, ::handleEffect,
            { dismissAllowingStateLoss() },
            Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.85f))
    }

    private fun handleEffect(effect: ReadAloudEffect) {
        when (effect.control) {
            ReadAloudControl.PreviousChapter, ReadAloudControl.NextChapter -> navigateReadAloudChapter(
                previous = effect.control == ReadAloudControl.PreviousChapter,
                following = ReadAloud.followReadAloudPosition,
                speechPrevious = { ReadAloud.prevChapter(requireContext()) },
                speechNext = { ReadAloud.nextChapter(requireContext()) },
                visiblePrevious = { ReadBook.moveToPrevChapter(upContent = true, toLast = false) },
                visibleNext = { ReadBook.moveToNextChapter(upContent = true) })
            ReadAloudControl.PreviousParagraph -> ReadAloud.prevParagraph(requireContext())
            ReadAloudControl.NextParagraph -> ReadAloud.nextParagraph(requireContext())
            ReadAloudControl.PlayPause -> callBack?.onClickReadAloud()
            ReadAloudControl.Stop -> ReadAloud.stop(requireContext())
            ReadAloudControl.Catalog -> callBack?.openChapterList()
            ReadAloudControl.MainMenu -> callBack?.showMenuBar()
            ReadAloudControl.Background -> callBack?.finish()
            ReadAloudControl.Settings -> showDialogFragment(ReadAloudConfigDialog())
            ReadAloudControl.Engine -> showDialogFragment(SpeakEngineDialog())
            ReadAloudControl.SleepTimer -> showDialogFragment(SleepTimerDialog.newInstance(
                BaseReadAloudService.timeMinute,
                BaseReadAloudService.chapterToStop,
            ))
            ReadAloudControl.UpdateRate -> {
                ReadAloud.upTtsSpeechRate(requireContext())
                if (!BaseReadAloudService.pause) { ReadAloud.pause(requireContext()); ReadAloud.resume(requireContext()) }
            }
            ReadAloudControl.SetTimer -> ReadAloud.setTimer(requireContext(), effect.value)
            ReadAloudControl.SetChapterStop -> ReadAloud.setChapterStop(requireContext(), effect.value)
            ReadAloudControl.TimerSaved -> toastOnUi(R.string.success)
        }
    }
    override fun upSpeakEngineSummary() { viewModel.reloadEngine() }
    override fun onSleepTimerMinute(minute: Int) { viewModel.setSleepMinute(minute) }
    override fun onSleepTimerChapter(count: Int) { viewModel.setSleepChapter(count) }
    override fun observeLiveBus() {
        observeEvent<Int>(EventBus.ALOUD_STATE) { viewModel.refreshRuntime() }
        observeEvent<Int>(EventBus.READ_ALOUD_DS) { viewModel.refreshRuntime(timerEvent = it) }
        observeEvent<Int>(EventBus.READ_ALOUD_CHAPTER_STOP) { viewModel.refreshRuntime(chapterEvent = it) }
    }
    private fun releaseCount() {
        val owner = countOwner
        countOwner = null
        if (lease.release()) owner?.let { it.bottomDialog-- }
    }
    override fun onDismiss(dialog: DialogInterface) { releaseCount(); super.onDismiss(dialog) }
    override fun onDestroyView() { releaseCount(); super.onDestroyView() }

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun onClickReadAloud()
        fun backToSpeakingPosition()
        fun finish()
    }
}
