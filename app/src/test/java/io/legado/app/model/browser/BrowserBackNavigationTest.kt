package io.legado.app.model.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserBackNavigationTest {
    private fun history(vararg urls: String) = urls.map { BrowserHistoryItem(it, "Title") }
    @Test fun videoAndButtonFullscreenTakePriorityOverHistoryOrClose() {
        assertEquals(BrowserBackAction.HideVideo, browserBackAction(true, true, false, emptyList(), -1))
        assertEquals(BrowserBackAction.ExitFullscreen, browserBackAction(false, true, true, history("a", "b"), 1))
        assertEquals(BrowserBackAction.Close, browserBackAction(false, false, false, history("a", "b"), 1))
    }
    @Test fun identicalRedirectHistorySkipsDuplicatesUntilDifferentOriginalUrlOrTitle() {
        assertEquals(BrowserBackAction.GoBack(3), browserBackAction(false, false, true, history("old", "current", "current", "current"), 3))
        val titles = listOf(BrowserHistoryItem("same", "old"), BrowserHistoryItem("same", "new"), BrowserHistoryItem("same", "new"))
        assertEquals(BrowserBackAction.GoBack(2), browserBackAction(false, false, true, titles, 2))
    }
    @Test fun blankHistoryAndAllDuplicatesCloseWhileDataUrlRemainsSingleStep() {
        assertEquals(BrowserBackAction.Close, browserBackAction(false, false, true, history("about:blank", "new"), 1))
        assertEquals(BrowserBackAction.Close, browserBackAction(false, false, true, history("same", "same"), 1))
        val data = "data:text/html;charset=utf-8;base64,"
        assertEquals(BrowserBackAction.GoBack(1), browserBackAction(false, false, true, history(data, data), 1))
        assertEquals(BrowserBackAction.Close, browserBackAction(false, false, true, history("single"), 0))
    }
    @Test fun localHtmlInjectionPreservesEveryCharacterAndHandlesExistingCaseInsensitiveMalformedOrMissingHead() {
        val input = "<html><HEAD data-meta='unchanged'><title>Title</title></HEAD><body>原文</body></html>"
        assertEquals(input.replace("<HEAD data-meta='unchanged'>", "<HEAD data-meta='unchanged'><script>script</script>"), injectBrowserScript(input, "script"))
        assertEquals("<head><script>s</script></head><head", injectBrowserScript("<head", "s"))
        assertEquals("<head><script>s</script></head>body", injectBrowserScript("body", "s"))
        assertEquals("<head><script>s</script></head>", injectBrowserScript("", "s"))
    }
}
