package io.legado.app.ui.code.config

import android.view.ViewGroup
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
import io.legado.app.data.preferences.AppCodeThemePreferences
import io.legado.app.help.config.ThemeConfig
import io.legado.app.utils.setLayout

class ChangeThemeDialog : BaseComposeDialogFragment() {
    private val model by
        viewModels<CodeThemeViewModel> {
            viewModelFactory {
                initializer {
                    CodeThemeViewModel(
                        AppCodeThemePreferences(requireContext()),
                        createSavedStateHandle(),
                    )
                }
            }
        }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onResume() {
        super.onResume()
        model.setSystemDark(ThemeConfig.isDarkTheme())
    }

    @Composable
    override fun Content() {
        CodeThemeRoute(
            model,
            { (activity as? CallBack)?.upTheme(it) },
            Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .8f),
        )
    }

    interface CallBack {
        fun upTheme(index: Int)
    }
}
