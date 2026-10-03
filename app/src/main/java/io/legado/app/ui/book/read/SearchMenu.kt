package io.legado.app.ui.book.read

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.isVisible
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.activity
import io.legado.app.utils.invisible
import io.legado.app.utils.visible

/** Temporary reader embedding bridge. All search-menu content and animations are Compose. */
class SearchMenu @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    private val callBack: CallBack
        get() = activity as CallBack

    private val controller = ReaderSearchMenuController()
    private var exitCallback: (() -> Unit)? = null
    private var pendingExit = 0L
    private val readerBackground = context.bottomBackground
    private val readerForeground =
        context.getPrimaryTextColor(ColorUtils.isColorLight(readerBackground))

    init {
        addView(
            ComposeView(context).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
                )
                setContent {
                    LegadoComposeTheme {
                        ReaderSearchMenuRoute(
                            controller,
                            Color(readerBackground),
                            Color(readerForeground),
                            ::settled,
                            { runMenuOut() },
                            ::navigate,
                            {
                                runMenuOut {
                                    callBack.openSearchActivity(selectedSearchResult?.query)
                                }
                            },
                            {
                                runMenuOut {
                                    callBack.cancelSelect()
                                    callBack.showMenuBar()
                                    this@SearchMenu.invisible()
                                }
                            },
                            { runMenuOut { callBack.exitSearchMenu() } },
                        )
                    }
                }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        updateSearchInfo()
    }

    val selectedSearchResult
        get() = controller.state.value.selected

    val previousSearchResult
        get() = controller.state.value.previous

    val bottomMenuVisible
        get() = isVisible && controller.state.value.panelVisible

    fun upSearchResultList(resultList: List<SearchResult>) {
        controller.results(resultList)
        updateSearchInfo()
    }

    fun updateSearchResultIndex(updateIndex: Int) {
        controller.index(updateIndex)
    }

    fun updateSearchInfo() {
        ReadBook.curTextChapter?.let { controller.chapter(it.title) }
    }

    fun runMenuIn() {
        visible()
        exitCallback = null
        pendingExit = 0
        controller.show()
        callBack.upSystemUiVisibility()
    }

    fun runMenuOut(onMenuOutEnd: (() -> Unit)? = null) {
        if (!isVisible || pendingExit != 0L) return
        val id = controller.hide()
        if (id == null) {
            onMenuOutEnd?.invoke()
            return
        }
        pendingExit = id
        exitCallback = onMenuOutEnd
    }

    private fun settled(visible: Boolean, id: Long) {
        if (visible) {
            callBack.upSystemUiVisibility()
            return
        }
        if (pendingExit != id || !controller.hidden(id)) return
        val callback = exitCallback
        pendingExit = 0
        exitCallback = null
        callback?.invoke()
        callBack.upSystemUiVisibility()
    }

    private fun navigate(delta: Int) {
        controller.navigate(delta)?.let { (result, index) ->
            callBack.navigateToSearch(result, index)
        }
    }

    override fun onDetachedFromWindow() {
        if (pendingExit != 0L) controller.hidden(pendingExit)
        exitCallback = null
        pendingExit = 0
        super.onDetachedFromWindow()
    }

    interface CallBack {
        var isShowingSearchResult: Boolean

        fun openSearchActivity(searchWord: String?)

        fun showSearchSetting()

        fun upSystemUiVisibility()

        fun exitSearchMenu()

        fun showMenuBar()

        fun navigateToSearch(searchResult: SearchResult, index: Int)

        fun onMenuShow()

        fun onMenuHide()

        fun cancelSelect()
    }
}
