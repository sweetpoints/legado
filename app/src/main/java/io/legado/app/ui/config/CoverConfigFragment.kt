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

class CoverConfigFragment : Fragment(), ConfigSearchPage {
    private val model by viewModels<CoverSettingsViewModel> { viewModelFactory { initializer {
        val application = requireContext().applicationContext
        CoverSettingsViewModel(DefaultCoverSettingsRepository(AppCoverSettingsStore(application)), FileCoverImageInputRepository(application), createSavedStateHandle())
    } } }
    private var query by mutableStateOf<String?>(null); private var selected: (() -> Unit)? = null
    private val selectImage = registerForActivityResult(HandleFileContract()) { result -> model.pickedImage(result.uri?.toString(), result.value) }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { LegadoComposeTheme { CoverSettingsRoute(model, { isAdded && !parentFragmentManager.isStateSaved },
            { key -> selectImage.launch { value = key.key; mode = HandleFileContract.IMAGE } }, ::destination, query,
            { query = null; selected?.invoke(); selected = null }, { query = null; selected = null; toastOnUi(R.string.config_search_empty) }) } }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) { super.onViewCreated(view, savedInstanceState); activity?.setTitle(R.string.cover_config) }
    override fun searchSettings(query: String, onSelected: () -> Unit) { this.query = query; selected = onSelected }
    private fun destination(destination: CoverDestination) { when (destination) {
        CoverDestination.Rules -> CoverRuleConfigDialog().show(childFragmentManager, "coverRules")
        CoverDestination.Font -> startActivity<ConfigActivity> { putExtra("configTag", ConfigTag.COVER_FONT_CONFIG) }
    } }
    override fun onDestroy() {
        if (isRemoving || activity?.isFinishing == true) {
            val captured = model; captured.stop()
            Coroutine.async(context = Dispatchers.Main.immediate) { captured.release() }.onError { AppLog.put("清理封面图片草稿失败", it) }
        }
        selected = null; super.onDestroy()
    }
}
