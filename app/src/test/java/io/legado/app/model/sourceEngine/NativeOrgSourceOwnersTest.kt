package io.legado.app.model.sourceEngine

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.SearchRule
import org.junit.Assert.*
import org.junit.Test

class NativeOrgSourceOwnersTest {
    @Test
    fun clearingAnEngineAliasReleasesCanonicalAndEphemeralOwnersWithoutOtherSources() {
        val owners = NativeOrgSourceOwners()
        owners.bind("book-class:one", "engine-one")
        owners.bind("ephemeral-task-one", "engine-one", "book-class:one")
        owners.bind("rss-class:one", "rss-engine-one")
        owners.bind("book-class:two", "engine-two")
        assertEquals(setOf("engine-one", "book-class:one"), owners.runtimeAliases("engine-one"))
        assertEquals(setOf("book-class:one", "ephemeral-task-one"), owners.take("engine-one"))
        assertTrue(owners.take("book-class:one").isEmpty())
        assertEquals(setOf("rss-class:one"), owners.take("rss-engine-one"))
        assertEquals(setOf("book-class:two"), owners.take("book-class:two"))
    }

    @Test
    fun finishingAnEphemeralTaskPreservesItsPersistentSourceConnectionOwner() {
        val owners = NativeOrgSourceOwners()
        owners.bind("persistent", "source")
        owners.bind("ephemeral", "source", "persistent")
        assertEquals(setOf("ephemeral"), owners.take("ephemeral"))
        assertEquals(setOf("persistent"), owners.take("source"))
        owners.bind("later", "source")
        owners.clear()
        assertTrue(owners.take("source").isEmpty())
    }

    @Test
    fun configurationIgnoresRuntimeMeasurementsAndRecognizesActualRecipeChanges() {
        val source = BookSource(bookSourceUrl = "https://fixture.invalid", jsLib = "var lib=1;")
        fun fingerprint() = NativeOrgSourceOwners.configurationFingerprint(source)
        val original = fingerprint()
        source.lastUpdateTime = 100
        source.respondTime = 23
        source.weight = 5
        source.customOrder = 17
        source.bookSourceName = "Renamed"
        source.bookSourceComment = "Personal annotation"
        assertEquals(original, fingerprint())
        source.jsLib = "var lib=2;"
        assertNotEquals(original, fingerprint())
        val library = fingerprint()
        source.header = "{\"X-Fixture\":\"changed\"}"
        assertNotEquals(library, fingerprint())
        val header = fingerprint()
        source.ruleSearch = SearchRule().apply { bookList = "tag.article" }
        assertNotEquals(header, fingerprint())
        val rule = fingerprint()
        source.bookSourceComment = "@source:v1 {\"version\":1,\"id\":\"fixture\"}"
        assertNotEquals(rule, fingerprint())
    }
}
