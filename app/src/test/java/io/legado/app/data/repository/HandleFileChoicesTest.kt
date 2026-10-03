package io.legado.app.data.repository

import org.junit.Assert.*
import org.junit.Test

class HandleFileChoicesTest {
    @Test
    fun originalModesPreserveExactOrderAndUnknownModeHasNoImplicitAction() {
        assertEquals(listOf(0, 10, 112), handleFileChoiceValues(0))
        assertEquals(listOf(1, 11), handleFileChoiceValues(1))
        assertEquals(listOf(0, 112), handleFileChoiceValues(2))
        assertEquals(listOf(111, 0, 10, 112), handleFileChoiceValues(3))
        assertEquals(listOf(4, 1, 11, 113), handleFileChoiceValues(4))
        assertTrue(handleFileChoiceValues(99).isEmpty())
    }

    @Test
    fun commonJavascriptBothMimeTypesWildcardTextFallbackAndDedupArePreserved() {
        val resolve: (String) -> String? = { if (it == "png") "image/png" else null }
        assertEquals(listOf("*/*"), handleFileMimeTypes(emptyList(), resolve))
        assertEquals(
            listOf(
                "application/javascript",
                "text/javascript",
                "text/*",
                "image/png",
                "application/octet-stream",
                "*/*",
            ),
            handleFileMimeTypes(listOf("js", "txt", "xml", "png", "unknown", "js", "*"), resolve),
        )
    }

    @Test
    fun extensionMatchingKeepsOriginalCaseRatherThanSilentlyBroadeningTypes() {
        assertEquals(listOf("application/octet-stream"), handleFileMimeTypes(listOf("JS")) { null })
    }
}
