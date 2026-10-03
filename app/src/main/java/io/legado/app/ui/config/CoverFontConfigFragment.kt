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
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers

class CoverFontConfigFragment : Fragment(), ConfigSearchPage, FontSelectDialog.CallBack {
    private val model by
        viewModels<CoverFontSettingsViewModel> {
            viewModelFactory {
                initializer {
                    val application = requireContext().applicationContext
                    CoverFontSettingsViewModel(
                        DefaultCoverFontSettingsRepository(AppCoverFontSettingsStore(application)),
                        FileCoverFontDraftRepository(application),
                        createSavedStateHandle(),
                    )
                }
            }
        }
    private var query by mutableStateOf<String?>(null)
    private var selected: (() -> Unit)? = null
    override val curFontPath: String
        get() = model.state.value.settings?.fontPath.orEmpty()

    override val selectSystemTypefaceOnDefault = false

    override fun selectFont(path: String) = model.selectFont(path)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    CoverFontSettingsRoute(
                        model,
                        { isAdded && !parentFragmentManager.isStateSaved },
                        { FontSelectDialog().show(childFragmentManager, "coverFont") },
                        query,
                        {
                            query = null
                            selected?.invoke()
                            selected = null
                        },
                        {
                            query = null
                            selected = null
                            toastOnUi(R.string.config_search_empty)
                        },
                    )
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.cover_font_config)
    }

    override fun searchSettings(query: String, onSelected: () -> Unit) {
        this.query = query
        selected = onSelected
    }

    override fun onDestroy() {
        if (isRemoving || activity?.isFinishing == true) {
            val captured = model
            captured.stop()
            Coroutine.async(context = Dispatchers.Main.immediate) { captured.release() }
                .onError { AppLog.put("清理封面字体草稿失败", it) }
        }
        selected = null
        super.onDestroy()
    }
}
