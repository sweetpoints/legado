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
import io.legado.app.data.preferences.PreferenceAutoReadSettingsRepository
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.ColorUtils

class AutoReadDialog : BaseComposeDialogFragment() {
    private val viewModel by viewModels<AutoReadViewModel> {
        viewModelFactory { initializer {
            AutoReadViewModel(PreferenceAutoReadSettingsRepository(), createSavedStateHandle())
        } }
    }
    private val lease = AutoReadDialogLease()
    private var countOwner: ReadBookActivity? = null
    private val callBack get() = activity as? CallBack

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        val host = activity as? ReadBookActivity ?: return
        if (!lease.acquire(host.bottomDialog)) {
            dismissAllowingStateLoss()
            return
        }
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

    @Composable
    override fun Content() {
        val background = requireContext().bottomBackground
        val foreground = requireContext().getPrimaryTextColor(ColorUtils.isColorLight(background))
        AutoReadRoute(viewModel, Color(background), Color(foreground), ::updateTts,
            { callBack?.openChapterList() },
            { callBack?.showMenuBar(); dismissAllowingStateLoss() },
            { callBack?.autoPageStop(); dismissAllowingStateLoss() },
            { (activity as? ReadBookActivity)?.let { host ->
                host.showPageAnimConfig { host.upPageAnim(); ReadBook.loadContent(false) }
            } }, Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.8f))
    }

    private fun updateTts() {
        val context = requireContext()
        ReadAloud.upTtsSpeechRate(context)
        if (!BaseReadAloudService.pause) {
            ReadAloud.pause(context)
            ReadAloud.resume(context)
        }
    }

    private fun releaseCount() {
        val owner = countOwner
        countOwner = null
        if (lease.release()) owner?.let { it.bottomDialog-- }
    }

    override fun onDismiss(dialog: DialogInterface) {
        releaseCount()
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        releaseCount()
        super.onDestroyView()
    }

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun autoPageStop()
    }
}
