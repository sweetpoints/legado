package io.legado.app.model.sourceEngine

import org.junit.Assert.*
import org.junit.Test

class LegacyLoginCheckHostTest {
    private fun response() =
        mapOf(
            "__legacyResponseKind" to "str",
            "url" to "https://fixture.invalid/final",
            "body" to "retained",
            "status" to 403,
            "multiHeaders" to mapOf("X-Fixture" to listOf("one", "two")),
        )

    @Test
    fun validStatusAndHeaderListsAreRetained() {
        assertEquals(403, LegacyLoginCheckHost.validateResponse(response())["status"])
        assertEquals(
            response()["multiHeaders"],
            LegacyLoginCheckHost.validateResponse(response())["multiHeaders"],
        )
    }

    @Test
    fun invalidUrlStatusAndHeadersAreExplicitlyRejected() {
        for (patch in
            listOf(
                mapOf("url" to "relative"),
                mapOf("url" to "file:///private"),
                mapOf("status" to 200.5),
                mapOf("status" to Double.NaN),
                mapOf("status" to 600),
                mapOf("multiHeaders" to mapOf("X" to "value")),
                mapOf("body" to null),
            )) {
            assertThrows(SourceScriptException::class.java) {
                LegacyLoginCheckHost.validateResponse(response() + patch)
            }
        }
    }
}
