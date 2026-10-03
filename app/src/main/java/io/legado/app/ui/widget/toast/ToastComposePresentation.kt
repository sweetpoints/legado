package io.legado.app.ui.widget.toast

import android.content.Context
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.ViewGroup
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.ToastMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Owns the short-lived Compose tree used by a native Toast window. */
internal class ToastComposePresentation(
    context: Context,
    private val message: ToastMessage,
    backgroundColor: Int,
    textColor: Int,
) {
    private val owner = ToastPresentationOwner()
    private val scope = CoroutineScope(AndroidUiDispatcher.Main + SupervisorJob())
    private val recomposer = Recomposer(scope.coroutineContext)
    private var closed = false
    private val detachListener =
        object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                close()
            }
        }

    val view =
        ComposeView(context).apply {
            id = View.generateViewId()
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams =
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setParentCompositionContext(recomposer)
            addOnAttachStateChangeListener(detachListener)
            setContent {
                LegadoComposeTheme {
                    ToastComposeContent(message, backgroundColor, textColor)
                }
            }
        }

    init {
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
    }

    fun onShown() {
        owner.moveTo(Lifecycle.State.RESUMED)
    }

    fun close() {
        if (closed) return
        closed = true
        view.removeOnAttachStateChangeListener(detachListener)
        view.disposeComposition()
        owner.close()
        recomposer.cancel()
        scope.cancel()
        message.inlineImages.values.forEach { image ->
            if (!image.bitmap.isRecycled) image.bitmap.recycle()
        }
    }
}

private class ToastPresentationOwner :
    LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = registry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedState.savedStateRegistry

    override val viewModelStore = ViewModelStore()

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun moveTo(state: Lifecycle.State) {
        if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = state
    }

    fun close() {
        moveTo(Lifecycle.State.DESTROYED)
        viewModelStore.clear()
    }
}
