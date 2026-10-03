package io.legado.app.ui.book.read

interface ReaderMenuCallbacks {
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
