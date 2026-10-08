package io.legado.app.model.sourceEngine

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.Assert.*
import org.junit.Test

class LegacyDomMutationTest {
    private fun marker(node: org.jsoup.nodes.Node, id: String): Map<String, Any?> {
        val state = LegacyDomHost.snapshot(node)[LegacyDomHost.NODE] as Map<*, *>
        val rows = state["nodes"] as List<*>
        return mapOf(
            LegacyDomHost.NODE to
                (state.entries.associate { it.key as String to it.value } +
                    mapOf(
                        "schemaVersion" to 2,
                        "documentId" to id,
                        "ids" to rows.indices.map { "$id-$it" },
                        "roots" to listOf(0),
                    ))
        )
    }

    private fun reply(node: Map<*, *>, op: String, vararg args: Any?): Map<*, *> =
        LegacyDomHost.call(node, op, args.toList()) as Map<*, *>

    private fun update(node: Map<*, *>, reply: Map<*, *>): Map<String, Any?> {
        val state = node[LegacyDomHost.NODE] as Map<*, *>
        val next = reply["__legacyDomUpdate"] as Map<*, *>
        val id = (state["ids"] as List<*>)[(state["index"] as Number).toInt()]
        return mapOf(
            LegacyDomHost.NODE to
                (next.entries.associate { it.key as String to it.value } +
                    ("index" to (next["ids"] as List<*>).indexOf(id)))
        )
    }

    @Test
    fun documentCreatesDetachedElementAndAppendPreservesOriginalAliasIdentity() {
        val doc =
            marker(Jsoup.parse("<title>Old</title><p id='a'>A</p>", "https://dom.invalid/"), "doc")
        val bodyReply = reply(doc, "body")
        val body = bodyReply["value"] as Map<*, *>
        val created = reply(update(doc, bodyReply), "createElement", "span")
        val detached = created["value"] as Map<*, *>
        assertNull(LegacyDomHost.restore(detached).parentNode())
        val text = reply(detached, "text", "Created")
        val appended = reply(update(body, text), "appendChild", text["value"])
        val live = (LegacyDomHost.restore(update(detached, appended)) as Element)
        assertEquals("body", live.parent()!!.tagName())
        assertEquals("Created", live.text())
        val actual = LegacyDomHost.restore(update(doc, appended)) as Document
        assertEquals("A Created", actual.body().text())
        val title = reply(update(doc, appended), "title", "New")
        assertNull(title["value"])
        assertEquals("New", (LegacyDomHost.restore(update(doc, title)) as Document).title())
    }

    @Test
    fun removingElementKeepsDetachedNodeAndDescendantAliasesUsable() {
        val doc = marker(Jsoup.parse("<div id='a'><b>Bold</b></div><p>Stay</p>"), "remove")
        val selected = reply(doc, "selectFirst", "#a")
        val original = selected["value"] as Map<*, *>
        val removed = reply(original, "remove")
        assertNull(removed["value"])
        assertEquals(
            "Stay",
            (LegacyDomHost.restore(update(doc, removed)) as Document).body().text(),
        )
        val detached = LegacyDomHost.restore(update(original, removed)) as Element
        assertNull(detached.parent())
        assertEquals("Bold", detached.selectFirst("b")!!.text())
        val reused = reply(update(doc, removed), "body")
        val attached = reply(reused["value"] as Map<*, *>, "appendChild", update(original, reused))
        assertEquals(
            "Stay Bold",
            (LegacyDomHost.restore(update(doc, attached)) as Document).body().text(),
        )
    }

    @Test
    fun crossDocumentAppendMovesTheSameNodeAndKeepsBothOwnerDocuments() {
        val left = marker(Jsoup.parse("<div id='target'></div>"), "left")
        val right = marker(Jsoup.parse("<p id='moved'>Move</p><b>Stay</b>"), "right")
        val target = reply(left, "selectFirst", "#target")["value"] as Map<*, *>
        val moved = reply(right, "selectFirst", "#moved")["value"] as Map<*, *>
        val combined = reply(target, "appendChild", moved)
        val updated = combined["__legacyDomUpdate"] as Map<*, *>
        assertEquals(listOf("left", "right"), updated["mergedDocumentIds"])
        assertEquals(
            "Move",
            (LegacyDomHost.restore(update(left, combined)) as Document).body().text(),
        )
        assertEquals(
            "Stay",
            (LegacyDomHost.restore(update(right, combined)) as Document).body().text(),
        )
        assertEquals(
            "target",
            (LegacyDomHost.restore(update(moved, combined)) as Element).parent()!!.id(),
        )
    }

    @Test
    fun elementsRemoveMatchesActualJsoupListAndRetainsSelectedNodes() {
        val document = Jsoup.parse("<i>One</i><i>Two</i><b>Stay</b>")
        val root = marker(document, "list")
        val selected = reply(root, "select", "i")["value"] as Map<*, *>
        val removed = reply(selected, "remove")
        val expected = Jsoup.parse(document.outerHtml())
        val elements = expected.select("i")
        elements.remove()
        assertEquals(expected.outerHtml(), LegacyDomHost.restore(update(root, removed)).outerHtml())
        assertEquals(
            elements.toString(),
            LegacyDomHost.call(removed["value"] as Map<*, *>, "toString", emptyList()).let {
                (it as Map<*, *>)["value"]
            },
        )
    }

    @Test
    fun xmlDeclarationParserAndCaseSurviveMutationRoundTrip() {
        val html =
            "<?xml version='1.0'?><Root xmlns='urn:fixture'><Keep/><Data><![CDATA[x<y]]></Data></Root>"
        val native = Jsoup.parse(html, "https://xml.invalid/", org.jsoup.parser.Parser.xmlParser())
        val root = marker(native, "xml")
        assertTrue(
            (LegacyDomHost.restore(root) as Document).parser().treeBuilder
                is org.jsoup.parser.XmlTreeBuilder
        )
        assertTrue(
            (LegacyDomHost.restore(root) as Document).parser().settings().preserveAttributeCase()
        )
        val selected = reply(root, "selectFirst", "Root")["value"] as Map<*, *>
        assertTrue(
            (LegacyDomHost.restore(selected) as Element).ownerDocument()!!.parser().treeBuilder
                is org.jsoup.parser.XmlTreeBuilder
        )
        assertTrue(
            (LegacyDomHost.restore(selected) as Element)
                .ownerDocument()!!
                .parser()
                .settings()
                .preserveAttributeCase()
        )
        val appended = reply(selected, "append", "<MixedCase AttrName='value'/>")
        val created = reply(update(root, appended), "createElement", "NewCase")
        val attached = reply(update(selected, created), "appendChild", created["value"])
        native.selectFirst("Root")!!.append("<MixedCase AttrName='value'/>")
        native.selectFirst("Root")!!.appendChild(native.createElement("NewCase"))
        assertEquals(native.outerHtml(), LegacyDomHost.restore(update(root, attached)).outerHtml())
        assertEquals(
            "xml",
            (LegacyDomHost.restore(update(root, attached)) as Document).childNode(0).let {
                (it as org.jsoup.nodes.XmlDeclaration).name()
            },
        )
    }

    @Test
    fun inheritedBaseUriFollowsMovedParentAndDisappearsWhenDetached() {
        val left =
            marker(
                Jsoup.parse("<a id='move' href='/next'>Move</a>", "https://left.invalid/"),
                "base-left",
            )
        val right =
            marker(Jsoup.parse("<div id='target'></div>", "https://right.invalid/"), "base-right")
        val moved = reply(left, "selectFirst", "a")["value"] as Map<*, *>
        assertEquals(
            "https://left.invalid/next",
            (LegacyDomHost.restore(moved) as Element).attr("abs:href"),
        )
        val target = reply(right, "selectFirst", "#target")["value"] as Map<*, *>
        val combined = reply(target, "appendChild", moved)
        val alias = update(moved, combined)
        assertEquals(
            "https://right.invalid/next",
            (LegacyDomHost.restore(alias) as Element).attr("abs:href"),
        )
        val removed = reply(alias, "remove")
        assertEquals("", LegacyDomHost.restore(update(alias, removed)).baseUri())
        assertEquals(
            "",
            (LegacyDomHost.restore(update(alias, removed)) as Element).attr("abs:href"),
        )
    }

    @Test
    fun explicitlyAssignedBaseUriSurvivesMoveAndRemoval() {
        val document = Jsoup.parse("<a href='/next'>Link</a>", "https://old.invalid/")
        document.selectFirst("a")!!.setBaseUri("https://explicit.invalid/")
        val original = marker(document.selectFirst("a")!!, "explicit")
        val newParent =
            marker(
                Jsoup.parse("<div></div>", "https://new.invalid/").selectFirst("div")!!,
                "new-parent",
            )
        val attached = reply(newParent, "appendChild", original)
        val moved = update(original, attached)
        val removed = reply(moved, "remove")
        assertEquals(
            "https://explicit.invalid/next",
            (LegacyDomHost.restore(update(moved, removed)) as Element).attr("abs:href"),
        )
    }

    @Test
    fun repeatedHtmlSetterPrunesOnlyUnobservedDetachedTrees() {
        val initial = marker(Jsoup.parse("<p id='keep'><b>Alias</b></p>"), "prune")
        val bodyReply = reply(initial, "body")
        var body = bodyReply["value"] as Map<*, *>
        val kept = reply(body, "selectFirst", "#keep")["value"] as Map<*, *>
        val originalState = kept[LegacyDomHost.NODE] as Map<*, *>
        val keptId = (originalState["ids"] as List<*>)[(originalState["index"] as Number).toInt()]
        repeat(400) {
            val state = body[LegacyDomHost.NODE] as Map<*, *>
            val ids = state["ids"] as List<*>
            val bodyId = ids[(state["index"] as Number).toInt()]
            val hinted =
                mapOf(
                    LegacyDomHost.NODE to
                        (state.entries.associate { it.key as String to it.value } +
                            ("retainIds" to listOf(ids[0], bodyId, keptId)))
                )
            val changed = reply(hinted, "html", "<p>New $it</p>")
            val update = changed["__legacyDomUpdate"] as Map<*, *>
            assertTrue((update["nodes"] as List<*>).size < 20)
            body = changed["value"] as Map<*, *>
            assertEquals("Alias", (LegacyDomHost.restore(update(kept, changed)) as Element).text())
        }
    }
}
