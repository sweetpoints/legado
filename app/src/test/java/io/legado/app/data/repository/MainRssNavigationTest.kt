package io.legado.app.data.repository

import io.legado.app.data.entities.RssSource
import org.junit.Assert.*
import org.junit.Test

class MainRssNavigationTest {
    private val source = RssSource(sourceUrl = "https://source.invalid", sourceName = "Feed")
    @Test fun ordinaryAndFirstCategoryLinkKeepOriginalGrammar() {
        assertEquals(source.sourceUrl, mainRssSingleUrl(source) { error("No JS") })
        assertEquals("https://first.invalid", mainRssSingleUrl(source.copy(sortUrl = "First::https://first.invalid::ignored")) { error("No JS") })
        assertEquals("custom:target", mainRssSingleUrl(source.copy(sortUrl = "custom:target")) { error("No JS") })
    }
    @Test fun bothSingleLinkJavascriptFormsEvaluateAndBlankResultKeepsOriginal() {
        for (value in listOf("@js:script", "<js>script</js>")) {
            val input = source.copy(sortUrl = value)
            assertEquals("https://resolved.invalid", mainRssSingleUrl(input) { assertEquals("script", it); "https://resolved.invalid" })
            assertEquals(value, mainRssSingleUrl(input) { " " })
            assertEquals(value, mainRssSingleUrl(input) { null })
        }
    }
    @Test fun singleHttpMatchingIsCaseInsensitiveAndOtherSchemesStayExternal() {
        assertEquals(MainRssDestination.ReaderLink, mainRssNavigation(source.copy(singleUrl = true, sortUrl = "HTTPS://target.invalid")) { null }.destination)
        assertEquals(MainRssDestination.External, mainRssNavigation(source.copy(singleUrl = true, sortUrl = "custom:target")) { null }.destination)
    }
    @Test fun literalHtmlAndMissingHtmlChooseReaderOrCategoryWithFullNameAndOrigin() {
        val category = mainRssNavigation(source) { error("No JS") }
        assertEquals(MainRssDestination.Categories, category.destination); assertNull(category.value)
        val reader = mainRssNavigation(source.copy(startHtml = "<html>Body</html>")) { error("No JS") }
        assertEquals(MainRssDestination.ReaderHtml, reader.destination); assertEquals("<html>Body</html>", reader.value)
        assertEquals(source.sourceUrl, reader.sourceUrl); assertEquals(source.sourceName, reader.sourceName)
    }
    @Test fun startHtmlJavascriptRetainsOriginalNullStringAndBlankCategoryFallback() {
        for (value in listOf("@js:script", "<js>script</js>")) {
            assertEquals("null", mainRssStartHtml(source.copy(startHtml = value)) { assertEquals("script", it); null })
            assertEquals(MainRssDestination.Categories, mainRssNavigation(source.copy(startHtml = value)) { " " }.destination)
            assertEquals("Generated body", mainRssNavigation(source.copy(startHtml = value)) { "Generated body" }.value)
        }
    }
    @Test fun cardIdentityAndMenuEligibilityAreIndependentOfMutableOrderAndMetadata() {
        val row = mainRssRow(source.copy(sourceIcon = "cover", loginUrl = "login"))
        assertTrue(row.hasLogin); assertEquals("cover", row.icon)
        assertEquals(row.id, mainRssRow(source.copy(customOrder = 99, sourceName = "Changed")).id)
        assertFalse(mainRssRow(source.copy(loginUrl = " ")).hasLogin)
        assertEquals(64, mainRssId("U".repeat(2000000)).length)
    }
}
