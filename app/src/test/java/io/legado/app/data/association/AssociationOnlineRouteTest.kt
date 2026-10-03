package io.legado.app.data.association

import org.junit.Assert.assertEquals
import org.junit.Test

class AssociationOnlineRouteTest {
    @Test
    fun directPublicSchemePathsRetainOriginalImportDialogs() {
        val paths =
            mapOf(
                "/bookSource" to "bookSource",
                "/rssSource" to "rssSource",
                "/replaceRule" to "replaceRule",
                "/textTocRule" to "txtRule",
                "/httpTTS" to "httpTts",
                "/dictRule" to "dictRule",
                "/theme" to "theme",
                "/autoTask" to "autoTask",
                "/addToBookshelf" to "addToBookshelf",
            )
        for ((path, type) in paths) {
            assertEquals(AssociationOnlineRoute(type), associationOnlineRoute(path, "import"))
        }
        assertEquals(
            AssociationOnlineRoute(readConfig = true),
            associationOnlineRoute("/readConfig", "import"),
        )
    }

    @Test
    fun legacyAliasesAndUnknownPathsUseOriginalAutomaticDetection() {
        assertEquals(
            AssociationOnlineRoute("bookSource"),
            associationOnlineRoute("/importonline", "booksource"),
        )
        assertEquals(
            AssociationOnlineRoute("rssSource"),
            associationOnlineRoute("/importonline", "rsssource"),
        )
        assertEquals(
            AssociationOnlineRoute("replaceRule"),
            associationOnlineRoute("/importonline", "replace"),
        )
        for ((path, host) in
            listOf(
                "/importonline" to "other",
                "/auto" to "import",
                "/unknown" to "import",
                "/highlightRule" to "import",
                "/BOOKSOURCE" to "import",
                null to null,
            )) {
            assertEquals(AssociationOnlineRoute(), associationOnlineRoute(path, host))
        }
    }
}
