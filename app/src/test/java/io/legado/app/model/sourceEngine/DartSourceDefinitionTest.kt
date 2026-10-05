package io.legado.app.model.sourceEngine

import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Test

class DartSourceDefinitionTest {
    @Test
    fun executionAndAuxiliaryEvaluationShareAppliedDefinition() {
        val candidate = """{"schemaVersion":1,"id":"modern"}"""
        val source =
            BookSource(
                bookSourceUrl = "https://legacy.invalid/",
                jsLib = "legacy-only library",
                bookSourceComment = "notes\n  @source:v1 $candidate\n",
            )
        assertEquals(candidate, DartSourceEngine.sourceJson(source))
    }

    @Test
    fun unmarkedSourcesStillSerializeAllLegacyFields() {
        val source =
            BookSource(
                bookSourceUrl = "https://legacy.invalid/",
                bookSourceComment = "notes mention @source:v1 in prose\n@engine:legacy",
            )
        assertEquals(GSON.toJson(source), DartSourceEngine.sourceJson(source))
    }
}
