package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.preferences.MoreReaderSetting
import io.legado.app.data.preferences.MoreReaderSettings
import io.legado.app.data.preferences.MoreReaderSettingsRepository
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.postEvent

/** Compose implementation of all settings formerly hosted by pref_config_read.xml. */
class MoreConfigDialog : BaseComposeDialogFragment() {
    private val settingsRepository by lazy { MoreReaderSettingsRepository(requireContext()) }
    private val settingsViewModel by
        viewModels<MoreReaderSettingsViewModel> {
            viewModelFactory { initializer { MoreReaderSettingsViewModel(settingsRepository) } }
        }
    private var preferenceObservation: AutoCloseable? = null
    private var bottomDialogOwner: ReadBookActivity? = null
    private var ownsBottomDialogCount = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A pre-Compose saved child can still be restored after an app update. Retire it before
        // the Compose dialog is attached so it cannot recreate the old native preference screen.
        childFragmentManager.findFragmentByTag(LEGACY_PREFERENCE_TAG)?.let { legacyFragment ->
            childFragmentManager
                .beginTransaction()
                .remove(legacyFragment)
                .commitNowAllowingStateLoss()
        }
    }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        acquireBottomDialogCount()
        if (!CanvasRecorderFactory.isSupport) {
            settingsViewModel.remove(PreferKey.optimizeRender)
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(R.color.background)
            decorView.setPadding(0, 0, 0, 0)
            attributes = attributes.apply {
                dimAmount = 0f
                gravity = Gravity.BOTTOM
            }
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 360.dpToPx())
        }
    }

    override fun onResume() {
        super.onResume()
        if (!settingsViewModel.state.value.isLoading) settingsViewModel.refresh()
        preferenceObservation?.close()
        preferenceObservation = settingsRepository.observe { key ->
            val observedOwner = bottomDialogOwner
            observedOwner?.runOnUiThread {
                if (isResumed && activity === observedOwner) {
                    settingsViewModel.refresh()
                    handlePreferenceChange(key)
                }
            }
        }
    }

    override fun onPause() {
        preferenceObservation?.close()
        preferenceObservation = null
        super.onPause()
    }

    @Composable
    override fun Content() {
        val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f
        val visibleSettings =
            if (CanvasRecorderFactory.isSupport) {
                MoreReaderSettings.all
            } else {
                MoreReaderSettings.all.filterNot { it.key == PreferKey.optimizeRender }
            }
        MoreReaderSettingsRoute(
            viewModel = settingsViewModel,
            visibleSettings = visibleSettings,
            background = Color(requireContext().bottomBackground),
            slopSummary =
                getString(
                    R.string.page_touch_slop_summary,
                    ViewConfiguration.get(requireContext()).scaledTouchSlop.toString(),
                ),
            bookmarkSummary =
                getString(
                    R.string.pull_bookmark_distance_summary,
                    (ViewConfiguration.get(requireContext()).scaledTouchSlop * 6).toString(),
                ),
            onToggle = settingsViewModel::toggle,
            onChoice = settingsViewModel::choose,
            onSeekBar = settingsViewModel::setSpeed,
            onAction = ::handleSettingAction,
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
        )
    }

    private fun handleSettingAction(setting: MoreReaderSetting.Action) {
        when (setting.key) {
            KEY_CUSTOM_PAGE_KEY -> PageKeyDialog(requireContext()).show()
            KEY_CLICK_REGIONAL_CONFIG -> (activity as? ReadBookActivity)?.showClickRegionalConfig()
            KEY_CUSTOM_TEXT_MENU -> (activity as? ReadBookActivity)?.showTextSelectMenuConfig()
            KEY_CUSTOM_READER_MENU -> (activity as? ReadBookActivity)?.showReaderMenuConfig()
            PreferKey.pageTouchSlop ->
                showNumberPicker(
                    setting = setting,
                    title = R.string.page_touch_slop_dialog_title,
                ) {
                    postEvent(EventBus.UP_CONFIG, arrayListOf(4))
                }
            PreferKey.pullBookmarkDistance ->
                showNumberPicker(
                    setting = setting,
                    title = R.string.pull_bookmark_distance_dialog_title,
                ) {}
            PreferKey.pageTouchClick ->
                showNumberPicker(
                    setting = setting,
                    title = R.string.page_touch_click_dialog_title,
                ) {
                    postEvent(EventBus.UP_CONFIG, arrayListOf(12))
                }
        }
    }

    private fun showNumberPicker(
        setting: MoreReaderSetting.Action,
        title: Int,
        onSaved: () -> Unit,
    ) {
        val maximum = requireNotNull(setting.numericMaximum)
        NumberPickerDialog(requireContext())
            .setTitle(getString(title))
            .setMaxValue(maximum)
            .setMinValue(0)
            .setValue(settingsViewModel.numericValue(setting.key))
            .show { value ->
                settingsViewModel.saveNumber(setting, value) {
                    if (isResumed && activity === bottomDialogOwner) onSaved()
                }
            }
    }

    private fun handlePreferenceChange(key: String) {
        when (key) {
            PreferKey.readBodyToLh -> activity?.recreate()
            PreferKey.hideStatusBar -> {
                ReadBookConfig.hideStatusBar =
                    requireContext().getPrefBoolean(PreferKey.hideStatusBar)
                postEvent(EventBus.UP_CONFIG, arrayListOf(0, 2))
            }
            PreferKey.hideNavigationBar -> {
                ReadBookConfig.hideNavigationBar =
                    requireContext().getPrefBoolean(PreferKey.hideNavigationBar)
                postEvent(EventBus.UP_CONFIG, arrayListOf(0, 2))
            }
            PreferKey.keepLight -> postEvent(key, true)
            PreferKey.textSelectAble -> postEvent(key, requireContext().getPrefBoolean(key))
            PreferKey.screenOrientation -> (activity as? ReadBookActivity)?.setOrientation()
            PreferKey.textFullJustify,
            PreferKey.textBottomJustify,
            PreferKey.hangingPunctuation,
            PreferKey.punctuationCompress,
            PreferKey.useZhLayout,
            PreferKey.adaptSpecialStyle -> postEvent(EventBus.UP_CONFIG, arrayListOf(5))
            PreferKey.showBrightnessView -> postEvent(PreferKey.showBrightnessView, "")
            PreferKey.doublePageHorizontal -> {
                ChapterProvider.upLayout()
                ReadBook.loadContent(false)
            }
            PreferKey.showReadTitleAddition,
            PreferKey.showReadTitleChapterNameOnly,
            PreferKey.readBarStyleFollowPage -> postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
            PreferKey.progressBarBehavior -> postEvent(EventBus.UP_SEEK_BAR, true)
            PreferKey.noAnimScrollPage -> ReadBook.callBack?.upPageAnim()
            PreferKey.pullToToggleBookmark -> (activity as? ReadBookActivity)?.upBookmarkIndicator()
            PreferKey.optimizeRender -> {
                ChapterProvider.upStyle()
                ReadBook.callBack?.upPageAnim(true)
                ReadBook.loadContent(false)
            }
            PreferKey.paddingDisplayCutouts -> postEvent(EventBus.UP_CONFIG, arrayListOf(2))
        }
    }

    private fun acquireBottomDialogCount() {
        if (ownsBottomDialogCount) return
        val owner = activity as? ReadBookActivity ?: return
        owner.bottomDialog++
        bottomDialogOwner = owner
        ownsBottomDialogCount = true
    }

    private fun releaseBottomDialogCount() {
        if (!ownsBottomDialogCount) return
        bottomDialogOwner?.let { owner ->
            owner.bottomDialog = (owner.bottomDialog - 1).coerceAtLeast(0)
        }
        bottomDialogOwner = null
        ownsBottomDialogCount = false
    }

    override fun onDestroyView() {
        releaseBottomDialogCount()
        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        releaseBottomDialogCount()
        super.onDismiss(dialog)
    }

    /** Compatibility shell solely for retiring restored fragments created by older versions. */
    class ReadPreferenceFragment : Fragment()

    private companion object {
        const val LEGACY_PREFERENCE_TAG = "readPreferenceFragment"
        const val KEY_CUSTOM_PAGE_KEY = "customPageKey"
        const val KEY_CLICK_REGIONAL_CONFIG = "clickRegionalConfig"
        const val KEY_CUSTOM_TEXT_MENU = "customTextMenu"
        const val KEY_CUSTOM_READER_MENU = "customReaderMenu"
    }
}
