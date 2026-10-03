package io.legado.app.ui.book.read

import io.legado.app.ui.book.searchContent.SearchResult

interface ReaderSearchCallbacks {
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
