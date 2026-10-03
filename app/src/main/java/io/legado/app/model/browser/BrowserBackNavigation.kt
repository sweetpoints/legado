package io.legado.app.model.browser

internal data class BrowserHistoryItem(val originalUrl: String, val title: String?)
internal sealed class BrowserBackAction {
    data object HideVideo : BrowserBackAction()
    data object ExitFullscreen : BrowserBackAction()
    data object Close : BrowserBackAction()
    data class GoBack(val steps: Int) : BrowserBackAction()
}
internal fun browserBackAction(video: Boolean, fullscreen: Boolean, canGoBack: Boolean,
    items: List<BrowserHistoryItem>, current: Int, blank: String = "about:blank",
    data: String = "data:text/html;charset=utf-8;base64,"): BrowserBackAction {
    if (video) return BrowserBackAction.HideVideo
    if (fullscreen) return BrowserBackAction.ExitFullscreen
    if (!canGoBack || items.size <= 1 || current !in items.indices) return BrowserBackAction.Close
    val selected = items[current]; var steps = 1
    for (index in current - 1 downTo 0) {
        val previous = items[index]
        if (previous.originalUrl == blank) return BrowserBackAction.Close
        if (previous.originalUrl != selected.originalUrl || previous.title != selected.title || selected.originalUrl == data) break
        steps++
    }
    return if (steps == items.size) BrowserBackAction.Close else BrowserBackAction.GoBack(steps)
}
