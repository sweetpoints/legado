package io.legado.app.ui.config

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.AppThemeListStore
import io.legado.app.data.repository.DefaultThemeListRepository
import io.legado.app.utils.getClipText
import io.legado.app.utils.setLayout
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi

class ThemeListDialog : BaseComposeDialogFragment() {
    private val model by
        viewModels<ThemeListViewModel> {
            viewModelFactory {
                initializer {
                    ThemeListViewModel(
                        DefaultThemeListRepository(AppThemeListStore(requireContext())),
                        createSavedStateHandle(),
                    )
                }
            }
        }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
    }

    @Composable
    override fun Content() {
        ThemeListRoute(
            model,
            { isAdded && !parentFragmentManager.isStateSaved },
            { requireContext().getClipText() },
            { requireContext().share(it, "主题分享") },
            { requireContext().toastOnUi("格式不对,添加失败") },
            { dismissAllowingStateLoss() },
            Modifier.fillMaxSize(),
        )
    }

    fun initData() = model.reload()

    fun delete(index: Int) {
        model.state.value.items.getOrNull(index)?.let { model.delete(it.key) }
    }

    fun share(index: Int) {
        model.state.value.items.getOrNull(index)?.let { model.share(it.key) }
    }
}
