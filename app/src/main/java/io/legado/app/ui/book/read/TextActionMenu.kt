package io.legado.app.ui.book.read

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi

/** Native reader positioning remains in PopupWindow; its entire content is Compose. */
class TextActionMenu(private val context: Context, private val callBack: CallBack) :
    PopupWindow(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {
    private val store = ViewModelStore()
    private val model =
        TextActionMenuViewModel(DefaultTextActionRepository(AppTextActionStore(context))).also {
            store.put("text-action", it)
        }
    private val composeView =
        ComposeView(context).apply {
            id = View.generateViewId()
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )
            setContent {
                LegadoComposeTheme { TextActionMenuRoute(model, { isShowing }, ::handle) }
            }
        }
    private var owner: LifecycleOwner? = null
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) releaseOwner()
    }

    private fun releaseOwner() {
        dismiss()
        composeView.disposeComposition()
        store.clear()
        owner?.lifecycle?.removeObserver(lifecycleObserver)
        owner = null
    }

    init {
        contentView = composeView
        isTouchable = true
        isOutsideTouchable = false
        isFocusable = false
        setOnDismissListener {
            composeView.disposeComposition()
            model.reset()
        }
        (context as? LifecycleOwner)?.let {
            owner = it
            it.lifecycle.addObserver(lifecycleObserver)
        }
        upMenu()
    }

    fun upMenu() {
        model.refresh()
    }

    fun show(
        view: View,
        windowHeight: Int,
        startX: Int,
        startTopY: Int,
        startBottomY: Int,
        endX: Int,
        endBottomY: Int,
    ) {
        val lifecycleOwner =
            requireNotNull(view.findViewTreeLifecycleOwner() ?: context as? LifecycleOwner) {
                "Reader lifecycle owner is required"
            }
        if (owner !== lifecycleOwner) {
            owner?.lifecycle?.removeObserver(lifecycleObserver)
            owner = lifecycleOwner
            lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        }
        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(
            view.findViewTreeSavedStateRegistryOwner() ?: context as? SavedStateRegistryOwner
        )
        composeView.setViewTreeViewModelStoreOwner(
            object : ViewModelStoreOwner {
                override val viewModelStore = store
            }
        )
        upMenu()
        val position =
            textActionPopupPosition(windowHeight, startX, startTopY, startBottomY, endX, endBottomY)
        showAtLocation(
            view,
            (if (position.edge == TextPopupEdge.Bottom) Gravity.BOTTOM else Gravity.TOP) or
                Gravity.START,
            position.x,
            position.y,
        )
    }

    private fun handle(event: TextActionEvent) {
        when (event.kind) {
            TextActionEventKind.Edit -> {
                dismiss()
                callBack.onEditTextActionMenu()
            }
            TextActionEventKind.Toast -> context.toastOnUi(event.message)
            TextActionEventKind.Invoke ->
                event.action?.let { action ->
                    try {
                        val id = TextSelectMenuItem.byKey[action.kind.configKey]?.menuId ?: 0
                        if (!callBack.onMenuItemSelected(id)) perform(action)
                    } finally {
                        callBack.onMenuActionFinally()
                    }
                }
        }
    }

    private fun perform(action: TextAction) {
        when (action.kind) {
            TextActionKind.Copy -> context.sendToClip(callBack.selectedText)
            TextActionKind.Share -> context.share(callBack.selectedText)
            TextActionKind.Browser -> runCatching {
                    textActionIntent(action, callBack.selectedText)?.let(context::startActivity)
                }
                    .onFailure {
                        it.printOnDebug()
                        context.toastOnUi(it.localizedMessage ?: "ERROR")
                    }
            TextActionKind.ProcessText ->
                if (Build.VERSION.SDK_INT >= 23)
                    runCatching {
                        textActionIntent(action, callBack.selectedText)?.let(context::startActivity)
                    }
                        .onFailure { AppLog.put("执行文本菜单操作出错\n$it", it, true) }
            else -> Unit
        }
    }

    interface CallBack {
        val selectedText: String

        fun onMenuItemSelected(itemId: Int): Boolean

        fun onMenuActionFinally()

        fun onEditTextActionMenu()
    }
}

internal fun textActionIntent(action: TextAction, selectedText: String): Intent? =
    when (action.kind) {
        TextActionKind.Browser ->
            if (selectedText.isAbsUrl()) Intent(Intent.ACTION_VIEW, Uri.parse(selectedText))
            else Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, selectedText)
        TextActionKind.ProcessText ->
            action.process?.let { target ->
                Intent(Intent.ACTION_PROCESS_TEXT)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, selectedText)
                    .setClassName(target.packageName, target.className)
            }
        else -> null
    }
