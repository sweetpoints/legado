package io.legado.app.model.sourceEngine

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.*
import org.junit.Test

class LegacyDomHostTest {
    private fun fixture(): Element = Jsoup.parse(
        "<table id='t'><tr><td id='one'>Own <b>Bold</b></td><td id='two'><a href='/next'>Next</a></td></tr></table>",
        "https://fixture.invalid/root",
    ).getElementById("one")!!

    @Test fun preservesActualTableParentsAndSiblings() {
        val restored = LegacyDomHost.restore(LegacyDomHost.snapshot(fixture())) as Element
        assertEquals("tr", restored.parent()!!.tagName())
        assertEquals("two", restored.nextElementSibling()!!.id())
        assertEquals("t", restored.parents().first { it.tagName() == "table" }.id())
    }
    @Test fun selectedNodesKeepFullOwnerDocumentAndAbsoluteAttributes() {
        val marker = LegacyDomHost.call(LegacyDomHost.snapshot(fixture()), "nextElementSibling", emptyList()) as Map<*, *>
        val selection = LegacyDomHost.call(marker, "selectFirst", listOf("a")) as Map<*, *>
        assertEquals("https://fixture.invalid/next", LegacyDomHost.call(selection, "attr", listOf("abs:href")))
        assertEquals("two", (LegacyDomHost.restore(selection) as Element).parent()!!.id())
    }
    @Test fun textOwnTextHtmlAndOuterHtmlMatchRealElement() {
        val original = fixture(); val marker = LegacyDomHost.snapshot(original)
        assertEquals(original.text(), LegacyDomHost.call(marker, "text", emptyList()))
        assertEquals(original.ownText(), LegacyDomHost.call(marker, "ownText", emptyList()))
        assertEquals(original.html(), LegacyDomHost.call(marker, "html", emptyList()))
        assertEquals(original.outerHtml(), LegacyDomHost.call(marker, "outerHtml", emptyList()))
    }
    @Test fun listSharesOneFlatDocumentTable() {
        val value = LegacyDomHost.serialize(fixture().parent()!!.children().toList()) as Map<*, *>
        val list = value[LegacyDomHost.LIST] as Map<*, *>
        assertEquals(2, (list["indexes"] as List<*>).size)
        assertTrue((list["nodes"] as List<*>).isNotEmpty())
        assertFalse(list.containsKey("trees"))
        assertEquals("Own Bold Next", LegacyDomHost.call(value, "text", emptyList()))
        assertEquals("one", LegacyDomHost.call(value, "attr", listOf("id")))
        val ancestors = LegacyDomHost.serialize(listOf(fixture().root() as Element, fixture()))
        // Independently parsed documents remain independent, never merged by selector text.
        assertTrue(ancestors is List<*>)

    }
    @Test fun sharedListStringMatchesActualJsoupElementsExactly() {
        val nodes = Jsoup.parse("<a>First</a><a>Second</a>").select("a")
        assertEquals("<a>First</a>\n<a>Second</a>", nodes.toString())
        assertEquals(nodes.toString(), LegacyDomHost.call(LegacyDomHost.serialize(nodes.toList()) as Map<*, *>, "toString", emptyList()))
    }
    @Test fun elementsToArrayIsShallowOrderedCopyWithSharedOwnerDocument() {
        val document = Jsoup.parse("<table><tr><td>First</td><td>Second</td></tr></table>")
        val elements = document.select("td")
        val copied = elements.toArray()
        assertEquals(2, copied.size)
        assertSame(elements[0], copied[0]); assertSame(elements[1], copied[1])
        copied[0] = elements[1]
        assertSame(document.selectFirst("td"), elements[0])
        val restored = LegacyDomHost.restoreValue(LegacyDomHost.serialize(elements.toArray())) as org.jsoup.select.Elements
        assertSame(restored[0].ownerDocument(), restored[1].ownerDocument())
        assertEquals("tr", restored[0].parent()!!.tagName())
        assertSame(restored[1], restored[0].nextElementSibling())
    }

    @Test fun deepDomIsFlatAndDoesNotConsumeJsonRecursionDepth() {
        val document = Jsoup.parse("<body></body>")
        var child = document.body()
        repeat(160) { child = child.appendElement("section") }
        child.attr("id", "deep")
        val marker = LegacyDomHost.snapshot(child)
        val state = marker[LegacyDomHost.NODE] as Map<*, *>
        val rows = state["nodes"] as List<*>
        assertTrue(rows.size > 160)
        assertTrue(rows.all { row -> ((row as Map<*, *>)["children"] as List<*>).all { it is Int } })
        assertEquals("deep", (LegacyDomHost.restore(marker) as Element).id())
    }
    @Test fun retainedSnapshotSurvivesManyIndependentExtractions() {
        val retained = LegacyDomHost.snapshot(fixture())
        repeat(600) { LegacyDomHost.snapshot(fixture()) }
        assertEquals("Own", LegacyDomHost.call(retained, "ownText", emptyList()))
    }
    @Test fun malformedCyclesAndMultipleParentsAreRejected() {
        val marker = LegacyDomHost.snapshot(fixture())
        val state = (marker[LegacyDomHost.NODE] as Map<*, *>).toMutableMap()
        val rows = (state["nodes"] as List<*>).map { (it as Map<*, *>).toMutableMap() }
        rows[0]["children"] = listOf(0)
        state["nodes"] = rows
        assertThrows(IllegalArgumentException::class.java) { LegacyDomHost.restore(mapOf(LegacyDomHost.NODE to state)) }
        rows[0]["children"] = listOf(1, 1)
        assertThrows(IllegalArgumentException::class.java) { LegacyDomHost.restore(mapOf(LegacyDomHost.NODE to state)) }
    }
    @Test fun arbitraryJavaAndMutationOverloadsAreNotExposed() {
        val marker = LegacyDomHost.snapshot(fixture())
        assertThrows(IllegalStateException::class.java) { LegacyDomHost.call(marker, "getClass", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { LegacyDomHost.call(marker, "attr", listOf("id", "changed")) }
        assertEquals("one", LegacyDomHost.call(marker, "attr", listOf("id")))
    }
}
