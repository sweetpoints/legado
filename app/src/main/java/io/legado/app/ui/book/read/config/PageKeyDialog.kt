package io.legado.app.ui.book.read.config

import io.legado.app.utils.resizeForIme

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import io.legado.app.data.preferences.PreferencePageKeySettingsRepository
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.setLayout

/** Keep the Context + show API used by reader preferences and TV settings. */
class PageKeyDialog(context: Context) : ComponentDialog(context), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    private val repository = PreferencePageKeySettingsRepository(context)
    private var savedState = SavedStateHandle()
    private var viewModel = PageKeyViewModel(repository, savedState)
    private var registered = true
    private val handledKeys = mutableSetOf<Int>()

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        viewModelStore.put("page-key", viewModel)
    }

    override fun onStart() {
        super.onStart()
        if (viewModel.state.value.finished) {
            savedState = SavedStateHandle()
            registered = false
            viewModelStore.clear()
        }
        if (!registered) {
            viewModel = PageKeyViewModel(repository, savedState)
            viewModelStore.put("page-key", viewModel)
            registered = true
        }
        val model = viewModel
        handledKeys.clear()
        // ComponentDialog recreates its lifecycle on each show; give Compose a fresh host.
        setContentView(
            ComposeView(context).apply {
                setViewTreeViewModelStoreOwner(this@PageKeyDialog)
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
                setContent {
                    LegadoComposeTheme {
                        PageKeyRoute(
                            model,
                            Color(context.backgroundColor),
                            ::dismiss,
                            Modifier.fillMaxWidth()
                                .heightIn(
                                    max = LocalConfiguration.current.screenHeightDp.dp * 0.85f
                                ),
                        )
                    }
                }
            }
        )
        window?.resizeForIme()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Intercept before Compose's text input consumes hardware letters/arrows.
        if (event.action == KeyEvent.ACTION_DOWN && viewModel.keyDown(event.keyCode)) {
            handledKeys += event.keyCode
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && handledKeys.remove(event.keyCode)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onSaveInstanceState(): Bundle =
        super.onSaveInstanceState().apply {
            putString("pageKey.previous", viewModel.state.value.values.previous)
            putString("pageKey.next", viewModel.state.value.values.next)
            putString("pageKey.focus", viewModel.state.value.focused?.name)
        }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        // Dialog restoration can call show(), so restore the editor before invoking super.
        viewModelStore.clear()
        savedState =
            SavedStateHandle(
                mapOf(
                    "pageKey.initialized" to true,
                    "pageKey.previous" to
                        savedInstanceState.getString("pageKey.previous").orEmpty(),
                    "pageKey.next" to savedInstanceState.getString("pageKey.next").orEmpty(),
                    "pageKey.focus" to savedInstanceState.getString("pageKey.focus"),
                )
            )
        viewModel = PageKeyViewModel(repository, savedState)
        viewModelStore.put("page-key", viewModel)
        registered = true
        super.onRestoreInstanceState(savedInstanceState)
    }

    override fun dismiss() {
        window?.decorView?.windowToken?.let { token ->
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(token, 0)
        }
        handledKeys.clear()
        super.dismiss()
        viewModelStore.clear()
        registered = false
    }
}
