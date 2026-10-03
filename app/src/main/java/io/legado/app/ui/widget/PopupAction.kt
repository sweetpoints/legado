package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.legado.app.R
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.theme.secondaryDisabledTextColor
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.applyMd3PopupStyle
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.resolveDropDownYOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The native window retains synchronous anchor placement; every control is composed. */
class PopupAction(private val context: Context) :
    PopupWindow(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {
    var onActionClick: ((action: String) -> Unit)? = null
    private var state by mutableStateOf(PopupActionState())
    private var presentation: Presentation? = null

    init {
        isTouchable = true
        isOutsideTouchable = false
        isFocusable = true
    }

    fun setItems(items: List<SelectItem<String>>) {
        setActionItems(items.map { PopupActionItem(it.title, it.value) })
    }

    fun setActionItems(items: List<PopupActionItem>) {
        state = state.copy(items = items.toList())
    }

    fun setVertical(vertical: Boolean) {
        state = state.copy(vertical = vertical)
    }

    fun setDangerValues(values: Set<String>) {
        state = state.copy(dangerValues = values.toSet())
    }

    fun setDisabledValues(values: Set<String>) {
        state = state.copy(disabledValues = values.toSet())
    }

    private fun prepare(anchor: View): Presentation {
        presentation?.let {
            return it
        }
        val frame = Rect()
        anchor.getWindowVisibleDisplayFrame(frame)
        val maximumWidth = frame.width().coerceAtLeast(1)
        val maximumHeight = frame.height().coerceAtLeast(1)
        state = state.copy(maxWidth = maximumWidth, maxHeight = maximumHeight)
        val host = anchor.findViewTreeLifecycleOwner()?.lifecycle
        check(host?.currentState != Lifecycle.State.DESTROYED) { "Popup anchor owner is destroyed" }
        val owner = PopupActionOwner()
        val scope = CoroutineScope(AndroidUiDispatcher.Main + SupervisorJob())
        val recomposer = Recomposer(scope.coroutineContext)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        val view =
            ComposeView(context).apply {
                layoutDirection = anchor.layoutDirection
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                // The window must measure content before attach to resolve keyboard-aware
                // placement.
                // An explicit parent recomposer lets that measurement compose while detached.
                setParentCompositionContext(recomposer)
                setContent {
                    LegadoComposeTheme {
                        CompositionLocalProvider(
                            LocalLayoutDirection provides
                                if (layoutDirection == View.LAYOUT_DIRECTION_RTL)
                                    LayoutDirection.Rtl
                                else LayoutDirection.Ltr
                        ) {
                            PopupActionContent(
                                state,
                                PopupActionColors(
                                    Color(context.getCompatColor(R.color.primaryText)),
                                    Color(context.getCompatColor(R.color.error)),
                                    Color(context.secondaryDisabledTextColor),
                                ),
                                this@PopupAction::dismiss,
                            ) { value ->
                                if (
                                    state.items.any {
                                        it.value == value &&
                                            popupItemEnabled(it, state.disabledValues)
                                    }
                                ) {
                                    onActionClick?.invoke(value)
                                }
                            }
                        }
                    }
                }
            }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) dismiss()
            else owner.moveTo(host?.currentState ?: Lifecycle.State.RESUMED)
        }
        val result = Presentation(view, owner, scope, recomposer, host, observer)
        presentation = result
        try {
            host?.addObserver(observer)
            contentView = view
            applyMd3PopupStyle()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(maximumWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(maximumHeight, View.MeasureSpec.AT_MOST),
            )
            return result
        } catch (error: Throwable) {
            release(result)
            throw error
        }
    }

    override fun showAsDropDown(anchor: View?, xoff: Int, yoff: Int, gravity: Int) {
        if (anchor == null) {
            super.showAsDropDown(anchor, xoff, yoff, gravity)
            return
        }
        if (isShowing) return
        val shown = prepare(anchor)
        try {
            val frame = Rect()
            anchor.getWindowVisibleDisplayFrame(frame)
            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            val resolvedYOff =
                resolveDropDownYOffset(
                    anchorTop = location[1],
                    anchorHeight = anchor.height,
                    popupHeight = shown.view.measuredHeight,
                    frameTop = frame.top,
                    frameBottom = frame.bottom,
                    gap = yoff,
                )
            super.showAsDropDown(anchor, xoff, resolvedYOff, gravity)
            shown.owner.moveTo(shown.host?.currentState ?: Lifecycle.State.RESUMED)
        } catch (error: Throwable) {
            release(shown)
            throw error
        }
    }

    override fun showAtLocation(parent: View?, gravity: Int, x: Int, y: Int) {
        if (parent == null) {
            super.showAtLocation(parent, gravity, x, y)
            return
        }
        if (isShowing) return
        val shown = prepare(parent)
        try {
            super.showAtLocation(parent, gravity, x, y)
            shown.owner.moveTo(shown.host?.currentState ?: Lifecycle.State.RESUMED)
        } catch (error: Throwable) {
            release(shown)
            throw error
        }
    }

    override fun dismiss() {
        val closing = presentation
        presentation = null
        try {
            super.dismiss()
        } finally {
            closing?.close()
        }
    }

    private fun release(shown: Presentation) {
        if (presentation === shown) presentation = null
        shown.close()
    }

    data class PopupActionItem(
        val title: String,
        val value: String,
        val icon: Drawable? = null,
        val enabled: Boolean = true,
        val checkable: Boolean = false,
        val checked: Boolean = false,
    )

    private class Presentation(
        val view: ComposeView,
        val owner: PopupActionOwner,
        val scope: CoroutineScope,
        val recomposer: Recomposer,
        val host: Lifecycle?,
        val observer: LifecycleEventObserver,
    ) {
        fun close() {
            host?.removeObserver(observer)
            view.disposeComposition()
            owner.close()
            recomposer.cancel()
            scope.cancel()
        }
    }
}
