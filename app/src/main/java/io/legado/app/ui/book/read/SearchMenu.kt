package io.legado.app.ui.book.read

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.isVisible
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.utils.activity

/** Temporary XML adapter; the Compose reader host composes ReaderSearchControls directly. */
class SearchMenu @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    private val controls =
        ReaderSearchControls(context, activity as CallBack) {
            visibility = if (it) View.VISIBLE else View.INVISIBLE
        }

    init {
        addView(
            ComposeView(context).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
                setContent { controls.Content() }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    val selectedSearchResult
        get() = controls.selectedSearchResult

    val previousSearchResult
        get() = controls.previousSearchResult

    val bottomMenuVisible
        get() = isVisible && controls.bottomMenuVisible

    fun upSearchResultList(resultList: List<SearchResult>) = controls.upSearchResultList(resultList)

    fun updateSearchResultIndex(updateIndex: Int) = controls.updateSearchResultIndex(updateIndex)

    fun updateSearchInfo() = controls.updateSearchInfo()

    fun runMenuIn() = controls.runMenuIn()

    fun runMenuOut(onMenuOutEnd: (() -> Unit)? = null) = controls.runMenuOut(onMenuOutEnd)

    override fun onDetachedFromWindow() {
        controls.dispose()
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
