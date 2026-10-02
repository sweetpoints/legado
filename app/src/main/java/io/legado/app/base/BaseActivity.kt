package io.legado.app.base

import androidx.viewbinding.ViewBinding
import io.legado.app.constant.Theme

/** Legacy View host. Compose activities inherit BaseThemedActivity directly. */
abstract class BaseActivity<VB : ViewBinding>(
    fullScreen: Boolean = true,
    theme: Theme = Theme.Auto,
    toolBarTheme: Theme = Theme.Auto,
    transparent: Boolean = false,
    imageBg: Boolean = true,
    showOpenMenuIcon: Boolean = true,
) : BaseThemedActivity(fullScreen, theme, toolBarTheme, transparent, imageBg, showOpenMenuIcon) {
    protected abstract val binding: VB

    final override fun createContentView() {
        setContentView(binding.root)
    }
}
