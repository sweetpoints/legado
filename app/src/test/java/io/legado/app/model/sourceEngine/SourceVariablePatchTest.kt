package io.legado.app.model.sourceEngine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceVariablePatchTest {
    @Test
    fun acceptsOnlyStringVariableDeltasIncludingExplicitDeletion() {
        val patch =
            SourceVariablePatch.fromResult(
                mapOf(
                    "bookVariables" to mapOf("imageDoubleTap" to "done", "removed" to null),
                    "chapterVariables" to mapOf("review" to "37"),
                )
            )
        assertEquals(mapOf("imageDoubleTap" to "done", "removed" to null), patch.book)
        assertEquals(mapOf("review" to "37"), patch.chapter)
    }

    @Test
    fun rejectsArbitraryDtoFieldsAndMalformedDeltasBeforeAnyApplication() {
        for (value in
            listOf(
                mapOf(
                    "bookVariables" to emptyMap<String, String>(),
                    "chapterVariables" to emptyMap<String, String>(),
                    "bookUrl" to "other",
                ),
                mapOf(
                    "bookVariables" to mapOf("unsafe" to 12),
                    "chapterVariables" to emptyMap<String, String>(),
                ),
                mapOf(
                    "bookVariables" to emptyMap<String, String>(),
                    "chapterVariables" to mapOf(1 to "unsafe"),
                ),
                mapOf("bookVariables" to emptyMap<String, String>()),
            )) {
            assertThrows(IllegalArgumentException::class.java) {
                SourceVariablePatch.fromResult(value)
            }
        }
    }
}
