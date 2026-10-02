package io.legado.app.base

import android.os.Bundle
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.viewbinding.ViewBinding
import io.legado.app.constant.Theme
import io.legado.app.lib.theme.compose.LegadoComposeTheme

/**
 * Compose 页面的基类。
 *
 * 复用 [BaseActivity] 的全部能力（主题切换、系统栏、背景图、多窗口、预测性返回、溢出菜单），
 * 只把内容视图替换为一个不经过 XML 的 [ComposeView]。
 *
 * 这样新页面能写纯 Compose，同时不必复制 [BaseActivity] 里近两百行逻辑，也不必额外引入一个
 * 「只放一个 ComposeView」的 layout 文件。副作用是根布局仍然是 View，但对一个新页面无实际成本。
 */
abstract class BaseComposeActivity(
    fullScreen: Boolean = true,
    theme: Theme = Theme.Auto,
    toolBarTheme: Theme = Theme.Auto,
    transparent: Boolean = false,
    imageBg: Boolean = true,
    showOpenMenuIcon: Boolean = true,
) : BaseActivity<ComposeRootBinding>(
    fullScreen, theme, toolBarTheme, transparent, imageBg, showOpenMenuIcon
) {

    /**
     * 在 [BaseActivity.onCreate] 中经 `setContentView(binding.root)` 创建，
     * 此时 `initTheme()` 已执行，因此 ComposeView 拿到的是正确的主题 Context。
     */
    override val binding: ComposeRootBinding by lazy { ComposeRootBinding(ComposeView(this)) }

    final override fun onActivityCreated(savedInstanceState: Bundle?) {
        // 注意用 composeView 而非 root：root 的静态类型是 View，没有 setContent
        binding.composeView.setContent {
            LegadoComposeTheme {
                Content(savedInstanceState)
            }
        }
        onComposeCreated(savedInstanceState)
    }

    /**
     * 页面内容，相当于 Fragment 的 `onViewCreated`。
     */
    @Composable
    abstract fun Content(savedInstanceState: Bundle?)

    /**
     * 需要在 Compose 之外做一次性初始化的（注册 EventBus、请求权限等）覆写这里。
     */
    open fun onComposeCreated(savedInstanceState: Bundle?) {}

}

/**
 * 把 [ComposeView] 适配成 [ViewBinding]，以便直接复用
 * [BaseActivity] 的 `setContentView(binding.root)`。
 *
 * `composeView` 必须对外可见：`root` 的静态类型是 [View]，调用方需要它来 `setContent`。
 */
class ComposeRootBinding(val composeView: ComposeView) : ViewBinding {

    override fun getRoot(): View = composeView

}
