package io.legado.app.ui.book.read

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.ColorUtils

/** Reader-owned search actions; result payloads remain ephemeral in its state controller. */
class ReaderSearchControls(
    context: Context,
    private val callBack: ReaderSearchCallbacks,
    private val visibilityChanged: (Boolean) -> Unit = {},
) {
    private val controller = ReaderSearchMenuController()
    private var exitCallback: (() -> Unit)? = null
    private var pendingExit = 0L
    private val readerBackground = context.bottomBackground
    private val readerForeground =
        context.getPrimaryTextColor(ColorUtils.isColorLight(readerBackground))

    var isVisible by mutableStateOf(false)
        private set

    init {
        updateSearchInfo()
    }

    @Composable
    fun Content() {
        if (!isVisible) return
        LegadoComposeTheme {
            ReaderSearchMenuRoute(
                controller = controller,
                background = Color(readerBackground),
                foreground = Color(readerForeground),
                settled = ::settled,
                close = { runMenuOut() },
                navigate = ::navigate,
                results = {
                    runMenuOut {
                        callBack.openSearchActivity(selectedSearchResult?.query)
                    }
                },
                main = {
                    runMenuOut {
                        callBack.cancelSelect()
                        callBack.showMenuBar()
                        deactivate()
                    }
                },
                exit = { runMenuOut { callBack.exitSearchMenu() } },
            )
        }
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
        isVisible = true
        visibilityChanged(true)
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

    fun dispose() {
        if (pendingExit != 0L) controller.hidden(pendingExit)
        exitCallback = null
        pendingExit = 0
    }

    fun deactivate() {
        isVisible = false
        visibilityChanged(false)
        exitCallback = null
        pendingExit = 0
        controller.deactivate()
    }
}
