package io.legado.app.help.source

import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import org.junit.Assert.*
import org.junit.Test

class LegacyExploreJsonTest {
    private fun parse(json: String) =
        GSON.fromJsonArray<ExploreKind>(normalizeLegacyExploreJson(json)).getOrThrow()

    @Test fun suppliedFanqieMenuKeepsAllSixtyThreeCategoriesAndStyles() {
        val raw = checkNotNull(javaClass.getResource("/legacy-explore/fanqie-trailing-comma.json")).readText()
        assertTrue(GSON.fromJsonArray<ExploreKind>(raw).isFailure)
        val kinds = parse(raw)
        assertEquals(63, kinds.size)
        assertEquals("全部🏷分类", kinds.first().title)
        assertEquals("/list-0-{{page}}/", kinds.first().url)
        assertNotNull(kinds.first().style)
        assertEquals("搞笑吐槽 ", kinds.last().title)
        assertEquals("/list-28-{{page}}/", kinds.last().url)
    }

    @Test fun trailingMenuCommaDoesNotCreateANullCategory() {
        val kinds = parse("""[{"title":"分类","url":"/list-{{page}}/","style":{"layout_flexGrow":1}},
            ]""")
        assertEquals(1, kinds.size)
        assertEquals("分类", kinds.single().title)
        assertEquals("/list-{{page}}/", kinds.single().url)
    }

    @Test fun quotedCommasBracketsAndEscapesRemainExact() {
        val raw = """[{"title":"逗号,]和引号\"","url":"/path?x=,]&y=\\"},]"""
        val value = parse(raw).single()
        assertEquals("逗号,]和引号\"", value.title)
        assertEquals("/path?x=,]&y=\\", value.url)
    }

    @Test fun explicitNullAndMissingSlotsStillFail() {
        for (raw in listOf("[null]", "[,]", "[{},,]", "[{},null,]")) {
            assertTrue(raw, GSON.fromJsonArray<ExploreKind>(normalizeLegacyExploreJson(raw)).isFailure)
        }
    }

    @Test fun standardJsonIsUnchanged() {
        val raw = """[{"title":"分类","url":"/list"}]"""
        assertEquals(raw, normalizeLegacyExploreJson(raw))
        assertEquals(1, parse(raw).size)
    }
}
