package io.legado.app.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.preferences.*
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers

/** The existing ConfigActivity destination hosts an entirely Compose settings page. */
class ThemeConfigFragment : Fragment(), ConfigSearchPage {
    private val model by viewModels<ThemeSettingsViewModel> { viewModelFactory { initializer {
        val application = requireContext().applicationContext
        ThemeSettingsViewModel(DefaultThemeSettingsRepository(AppThemeSettingsStore(application), AppThemeSettingsPlatform(application)),
            FileThemeNameDraftRepository(application), createSavedStateHandle())
    } } }
    private var query by mutableStateOf<String?>(null)
    private var selected: (() -> Unit)? = null
    private val selectImage = registerForActivityResult(HandleFileContract()) { value ->
        value.uri?.let { uri -> if (uri.scheme?.lowercase() in listOf("http", "https")) toastOnUi("下载背景图片中...") }
        model.pickedImage(value.requestCode, value.uri?.toString())
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { LegadoComposeTheme { ThemeSettingsRoute(model, { isAdded && !parentFragmentManager.isStateSaved }, ::destination,
                query, { query = null; selected?.invoke(); selected = null }, { query = null; selected = null; toastOnUi(R.string.config_search_empty) }, message = { toastOnUi(it) }) } }
        }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.theme_setting)
        parentFragmentManager.setFragmentResultListener(BackgroundBlurDialog.RESULT, viewLifecycleOwner) { _, result ->
            model.blurFinished(result.getBoolean(BackgroundBlurDialog.NIGHT))
        }
    }
    override fun searchSettings(query: String, onSelected: () -> Unit) { this.query = query; selected = onSelected }
    private fun destination(value: ThemeSettingsDestination) {
        when (value) {
            ThemeSettingsDestination.ThemeList -> ThemeListDialog().show(childFragmentManager, "themeList")
            ThemeSettingsDestination.Welcome -> startActivity<ConfigActivity> { putExtra("configTag", ConfigTag.WELCOME_CONFIG) }
            ThemeSettingsDestination.Cover -> startActivity<ConfigActivity> { putExtra("configTag", ConfigTag.COVER_CONFIG) }
            ThemeSettingsDestination.BottomSkin -> startActivity<BottomBarSkinActivity>()
            ThemeSettingsDestination.BlurDay, ThemeSettingsDestination.BlurNight -> BackgroundBlurDialog.newInstance(value == ThemeSettingsDestination.BlurNight).show(parentFragmentManager, "background-blur")
            ThemeSettingsDestination.ImageDay, ThemeSettingsDestination.ImageNight -> {
                val night = value == ThemeSettingsDestination.ImageNight
                model.imagePicker(night)
                selectImage.launch { requestCode = if (night) 122 else 121; mode = HandleFileContract.IMAGE }
            }
        }
    }
    override fun onStop() {
        val captured = model
        Coroutine.async(context = Dispatchers.Main.immediate) { captured.flush() }.onError { AppLog.put("保存主题名称草稿失败", it) }
        super.onStop()
    }
    override fun onDestroy() {
        if (activity?.isFinishing == true || isRemoving) {
            val captured = model; captured.stop()
            Coroutine.async(context = Dispatchers.Main.immediate) { captured.release() }.onError { AppLog.put("清理主题名称草稿失败", it) }
        }
        selected = null
        super.onDestroy()
    }
}
