package io.legado.app.ui.book.read.config

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
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.preferences.AppBgTextSettingsRepository
import io.legado.app.data.repository.AppReaderBackgroundFilesRepository
import io.legado.app.data.repository.AppReaderBackgroundPreviewRepository
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.getSecondaryTextColor
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.observeEvent
import io.legado.app.utils.toastOnUi

class BgTextConfigDialog : BaseComposeDialogFragment() {
    companion object {
        const val TEXT_COLOR = 121
        const val BG_COLOR = 122
        const val TEXT_ACCENT_COLOR = 123
        const val REVIEW_ICON_COLOR = 124
        const val UNDERLINE_COLOR = 125
    }
    private val model by viewModels<BgTextSettingsViewModel> {
        viewModelFactory { initializer { BgTextSettingsViewModel(AppBgTextSettingsRepository(requireContext()), AppReaderBackgroundFilesRepository(requireContext()), createSavedStateHandle()) } }
    }
    private val previews by lazy { AppReaderBackgroundPreviewRepository(requireContext()) }
    private val visibility = PaddingPanelVisibility()
    private val selectBgImage = registerForActivityResult(HandleFileContract()) { model.pickerResult(BgTextAction.PickBackground, it.uri?.toString()) }
    private val selectExportDir = registerForActivityResult(HandleFileContract()) { model.pickerResult(BgTextAction.PickExport, it.uri?.toString()) }
    private val selectImportDoc = registerForActivityResult(HandleFileContract()) {
        if (it.uri?.path in listOf("/${getString(R.string.import_on_line)}", "/网络导入")) model.networkImportPickerResult()
        else model.pickerResult(BgTextAction.PickImport, it.uri?.toString())
    }
    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0); attributes = attributes.apply { dimAmount = 0f; gravity = Gravity.BOTTOM }
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
    override fun onComposeCreated(savedInstanceState: Bundle?) {
        val host = activity as? ReadBookActivity
        if (host != null) visibility.acquire(object : PaddingPanelVisibility.Owner {
            override var bottomDialog: Int
                get() = host.bottomDialog
                set(value) { host.bottomDialog = value }
        })
        // Keep compatibility with color callbacks originating from the reader host and other panels.
        observeEvent<ArrayList<Int>>(EventBus.UP_CONFIG) { model.refresh() }
    }
    @Composable override fun Content() {
        val background = requireContext().bottomBackground; val light = ColorUtils.isColorLight(background)
        BgTextSettingsRoute(model, previews, ::handleEffect, Color(background), Color(requireContext().getPrimaryTextColor(light)),
            Color(requireContext().getSecondaryTextColor(light)), Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp))
    }
    private fun handleEffect(effect: BgTextEffect, acknowledge: () -> Unit): Boolean {
        if (!isAdded || parentFragmentManager.isStateSaved) return false
        if (effect.update?.systemUi == true) (activity as? ReadBookActivity)?.upSystemUiVisibility()
        acknowledge()
        when (effect.action) {
            BgTextAction.PickBackground -> selectBgImage.launch { mode = HandleFileContract.IMAGE }
            BgTextAction.PickExport -> selectExportDir.launch { title = getString(R.string.export_str) }
            BgTextAction.PickImport -> selectImportDoc.launch {
                mode = HandleFileContract.FILE; title = getString(R.string.import_str); allowExtensions = arrayOf("zip")
                otherActions = arrayListOf(SelectItem(getString(R.string.import_on_line), -1))
            }
            BgTextAction.Close -> dismissAllowingStateLoss()
            BgTextAction.Message -> effect.message?.let { requireContext().toastOnUi(messageText(it, effect.text)) }
            else -> Unit
        }
        return true
    }
    private fun messageText(message: BgTextMessage, text: String): String = getString(when (message) {
        BgTextMessage.Imported -> R.string.bg_text_imported
        BgTextMessage.Exported -> R.string.bg_text_exported
        BgTextMessage.BackgroundSet -> R.string.bg_text_background_set
        BgTextMessage.TemplateSaved -> R.string.review_icon_template_saved
        BgTextMessage.TemplateRenamed -> R.string.review_icon_template_renamed
        BgTextMessage.TemplateDeleted -> R.string.review_icon_template_deleted
        BgTextMessage.ReviewColorReset -> R.string.review_icon_color_reset
        BgTextMessage.MinimumPresets -> R.string.bg_text_minimum_presets
        BgTextMessage.Failed -> R.string.bg_text_failed
    }, text)
    override fun onDestroyView() { visibility.release(); super.onDestroyView() }
    override fun onDismiss(dialog: DialogInterface) {
        model.dismissed(activity?.isChangingConfigurations == true); visibility.release(); super.onDismiss(dialog)
    }
}
