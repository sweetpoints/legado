package io.legado.app.model.sourceEngine

import io.legado.app.data.entities.BookSource
import org.junit.Assert.assertEquals
import org.junit.Test

class DartSourceTaskIdentityTest {
    @Test
    fun legacyIdentityUsesTheExactSubmittedSourceUrl() {
        val source =
            BookSource(bookSourceUrl = "https://fixture.invalid/source", bookSourceName = "Fixture")
        assertEquals(
            source.bookSourceUrl,
            DartSourceEngine.engineIdentity(DartSourceEngine.sourceJson(source)),
        )
    }

    @Test
    fun versionedCarrierBindsItsEngineIdWithoutPretendingItIsTheDaoKey() {
        val source =
            BookSource(
                bookSourceUrl = "https://fixture.invalid/carrier",
                bookSourceName = "Fixture",
                bookSourceComment =
                    "@source:v1 {\"schemaVersion\":1,\"id\":\"opaque-definition-id\",\"name\":\"Fixture\",\"baseUrl\":\"https://fixture.invalid/\"}",
            )
        assertEquals(
            "opaque-definition-id",
            DartSourceEngine.engineIdentity(DartSourceEngine.sourceJson(source)),
        )
        assertEquals("https://fixture.invalid/carrier", source.bookSourceUrl)
    }
}
