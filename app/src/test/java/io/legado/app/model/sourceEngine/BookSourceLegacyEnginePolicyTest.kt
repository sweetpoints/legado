package io.legado.app.model.sourceEngine

import io.legado.app.data.entities.BookSource
import io.legado.app.exception.BookSourceLegacyEngineRemovedException
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.jsSource.JsSourceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BookSourceLegacyEnginePolicyTest {
    @Test
    fun nonBookSourcePolicyRemainsAllowed() {
        BookSourceLegacyEnginePolicy.requireLegacyAllowed(false)
    }

    @Test
    fun rejectionHasStableCodeAndNoSourceContents() {
        val error = assertThrows(BookSourceLegacyEngineRemovedException::class.java) {
            BookSourceLegacyEnginePolicy.requireLegacyAllowed(true)
        }
        assertEquals("engine_migration_required", error.code)
        assertEquals(0, error.stackTrace.size)
    }

    @Test
    fun allOldBookSourceEntrypointsRejectBeforeExecution() {
        val source = BookSource(bookSourceUrl = "https://fixture.invalid")
        assertThrows(BookSourceLegacyEngineRemovedException::class.java) {
            AnalyzeRule(source = source)
        }
        assertThrows(BookSourceLegacyEngineRemovedException::class.java) {
            AnalyzeUrl("{{throw new Error('must never execute')}}", source = source)
        }
        assertThrows(BookSourceLegacyEngineRemovedException::class.java) {
            JsSourceEngine(source)
        }
    }
}
