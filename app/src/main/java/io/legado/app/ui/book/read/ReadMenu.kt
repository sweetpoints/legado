package io.legado.app.ui.book.read

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.activity
import io.legado.app.utils.applyNavigationBarPadding

/** Temporary XML adapter; the Compose reader host uses ReaderMenuController directly. */
class ReadMenu @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    private val controller =
        ReaderMenuController(
            requireNotNull(activity),
            activity as CallBack,
            visibilityChanged = { visibility = if (it) View.VISIBLE else View.INVISIBLE },
        )

    var canShowMenu: Boolean
        get() = controller.canShowMenu
        set(value) {
            controller.canShowMenu = value
        }

    init {
        addView(
            ComposeView(context).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
                setContent { controller.Content() }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        applyNavigationBarPadding()
    }

    fun reset() = controller.reset()

    fun refreshMenuColorFilter() = controller.refreshMenuColorFilter()

    fun upBrightnessState() = controller.upBrightnessState()

    fun runMenuIn(anim: Boolean = !AppConfig.isEInkMode) = controller.runMenuIn(anim)

    fun runMenuOut(anim: Boolean = !AppConfig.isEInkMode, onMenuOutEnd: (() -> Unit)? = null) =
        controller.runMenuOut(anim, onMenuOutEnd)

    fun upBookView() = controller.upBookView()

    fun updateToolbarActions(actions: List<ReaderToolbarAction>) =
        controller.updateToolbarActions(actions)

    fun openPopup(popup: ReaderPopup) = controller.openPopup(popup)

    fun setScreenBrightness(value: Float) = controller.setScreenBrightness(value)

    fun upSeekBar() = controller.upSeekBar()

    fun setSeekPage(seek: Int) = controller.setSeekPage(seek)

    fun setAutoPage(autoPage: Boolean) = controller.setAutoPage(autoPage)

    override fun onDetachedFromWindow() {
        controller.dispose()
        super.onDetachedFromWindow()
    }

    interface CallBack {
        fun readerToolbarActions(): List<ReaderToolbarAction>

        fun readerPopupEntries(popup: ReaderPopup): List<ReaderPopupEntry>

        fun readerPopupAction(popup: ReaderPopup, value: String)

        fun readerToolbarAction(id: Int)

        fun autoPage()

        fun openReplaceRule()

        fun openChapterList()

        fun openSearchActivity(searchWord: String?)

        fun openSourceEditActivity()

        fun openBookInfoActivity()

        fun showReadStyle()

        fun showMoreSetting()

        fun showBookMemo()

        fun showReadAloudDialog()

        fun upSystemUiVisibility()

        fun onClickReadAloud()

        fun showHelp()

        fun showLogin()

        fun payAction()

        fun disableSource()

        fun skipToChapter(index: Int)

        fun onMenuShow()

        fun onMenuHide()
    }
}
