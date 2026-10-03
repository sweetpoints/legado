package io.legado.app.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserMenuTest {
    @Test fun typedMenuRetainsToolbarThenOverflowOrderAndDistinctDispatchKeys() {
        assertEquals(listOf(BrowserMenu.Refresh, BrowserMenu.Confirm, BrowserMenu.Open, BrowserMenu.Copy,
            BrowserMenu.Fullscreen, BrowserMenu.Log, BrowserMenu.Disable, BrowserMenu.Delete), browserMenus)
        assertEquals(browserMenus.size, browserMenus.map { it.name }.toSet().size)
    }
}
