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
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers

class WelcomeConfigFragment : Fragment(), ConfigSearchPage {
    private val model by
        viewModels<WelcomeSettingsViewModel> {
            viewModelFactory {
                initializer {
                    val application = requireContext().applicationContext
                    WelcomeSettingsViewModel(
                        DefaultWelcomeSettingsRepository(AppWelcomeSettingsStore(application)),
                        FileWelcomeImageInputRepository(application),
                        createSavedStateHandle(),
                    )
                }
            }
        }
    private var query by mutableStateOf<String?>(null)
    private var selected: (() -> Unit)? = null
    private val selectImage =
        registerForActivityResult(HandleFileContract()) { value ->
            value.uri?.let {
                if (it.scheme?.lowercase() in listOf("http", "https")) toastOnUi("下载图片中...")
            }
            model.pickedImage(value.uri?.toString(), value.requestCode)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    WelcomeSettingsRoute(
                        model,
                        { isAdded && !parentFragmentManager.isStateSaved },
                        { night ->
                            selectImage.launch {
                                requestCode = if (night) 222 else 221
                                mode = HandleFileContract.IMAGE
                            }
                        },
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
                        { toastOnUi(it) },
                    )
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.welcome_style)
    }

    override fun searchSettings(query: String, onSelected: () -> Unit) {
        this.query = query
        selected = onSelected
    }

    override fun onStop() {
        val captured = model
        Coroutine.async(context = Dispatchers.Main.immediate) { captured.flush() }
            .onError { AppLog.put("保存启动页时长失败", it) }
        super.onStop()
    }

    override fun onDestroy() {
        if (isRemoving || activity?.isFinishing == true) {
            val captured = model
            captured.stop()
            Coroutine.async(context = Dispatchers.Main.immediate) { captured.release() }
                .onError { AppLog.put("清理启动页图片草稿失败", it) }
        }
        selected = null
        super.onDestroy()
    }
}
