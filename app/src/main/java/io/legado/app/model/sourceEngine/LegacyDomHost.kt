package io.legado.app.model.sourceEngine

import org.jsoup.nodes.*
import org.jsoup.parser.ParseSettings
import org.jsoup.parser.Parser
import org.jsoup.parser.Tag
import org.jsoup.parser.XmlTreeBuilder
import org.jsoup.select.Elements
import org.seimicrawler.xpath.JXNode

/** Jsoup JSON snapshots and mutable forests; native nodes live only for one RPC. */
object LegacyDomHost {
    const val NODE = "__legacyDom"
    const val LIST = "__legacyDomList"
    private val tagFlags =
        listOf(
            Tag.Known,
            Tag.Void,
            Tag.Block,
            Tag.InlineContainer,
            Tag.SelfClose,
            Tag.SeenSelfClose,
            Tag.PreserveWhitespace,
            Tag.RcData,
            Tag.Data,
            Tag.FormSubmittable,
            Tag.TextBoundary,
        )

    fun serialize(value: Any?): Any? =
        when (value) {
            is JXNode -> serialize(value.value())
            is Node -> snapshot(value)
            is List<*> -> {
                val normalized = value.map { if (it is JXNode) it.value() else it }
                val nodes = normalized.filterIsInstance<Node>()
                if (
                    nodes.isNotEmpty() &&
                        nodes.size == value.size &&
                        nodes.all { it.root() === nodes.first().root() }
                ) {
                    val (table, indexes) = capture(nodes.first().root())
                    mapOf(
                        LIST to
                            mapOf(
                                "schemaVersion" to 1,
                                "nodes" to table,
                                "indexes" to nodes.map { indexes[it]!! },
                            )
                    )
                } else normalized.map(::serialize)
            }
            is Array<*> -> serialize(value.toList())
            else -> value
        }

    fun snapshot(node: Node): Map<String, Any?> {
        val (table, indexes) = capture(node.root())
        return mapOf(
            NODE to mapOf("schemaVersion" to 1, "nodes" to table, "index" to indexes[node]!!)
        )
    }

    private fun capture(
        root: Node
    ): Pair<List<Map<String, Any?>>, java.util.IdentityHashMap<Node, Int>> {
        val nodes = mutableListOf(root)
        val indexes = java.util.IdentityHashMap<Node, Int>()
        indexes[root] = 0
        var offset = 0
        while (offset < nodes.size) {
            nodes[offset++].childNodes().forEach { child ->
                require(nodes.size < 100000) { "Legacy DOM snapshot exceeds resource bounds" }
                indexes[child] = nodes.size
                nodes.add(child)
            }
        }
        return captureRows(nodes, indexes) to indexes
    }

    private fun captureRows(
        nodes: List<Node>,
        indexes: java.util.IdentityHashMap<Node, Int>,
    ): List<Map<String, Any?>> = nodes.map { node ->
        val result = mutableMapOf<String, Any?>("baseUri" to node.baseUri())
        when (node) {
            is Document -> {
                result["kind"] = "document"
                val parser = node.parser()
                result["parser"] =
                    mapOf(
                        "xml" to (parser.treeBuilder is XmlTreeBuilder),
                        "tagCase" to parser.settings().preserveTagCase(),
                        "attributeCase" to parser.settings().preserveAttributeCase(),
                    )
                val settings = node.outputSettings()
                result["output"] =
                    mapOf(
                        "pretty" to settings.prettyPrint(),
                        "outline" to settings.outline(),
                        "indent" to settings.indentAmount(),
                        "syntax" to settings.syntax().name,
                        "charset" to settings.charset().name(),
                        "escapeMode" to settings.escapeMode().name,
                    )
            }
            is Element -> {
                result["kind"] = "element"
                result["tag"] = node.tagName()
                result["namespace"] = node.tag().namespace()
                result["selfClosing"] = node.tag().isSelfClosing
                result["tagOptions"] =
                    tagFlags.filter { node.tag().`is`(it) }.fold(0) { bits, flag -> bits or flag }
                result["baseUriOwn"] =
                    if (node.attributes().hasKey("/baseUri")) node.attributes().get("/baseUri")
                    else null
            }
            is CDataNode -> {
                result["kind"] = "cdata"
                result["value"] = node.wholeText
            }
            is TextNode -> {
                result["kind"] = "text"
                result["value"] = node.wholeText
            }
            is DataNode -> {
                result["kind"] = "data"
                result["value"] = node.wholeData
            }
            is Comment -> {
                result["kind"] = "comment"
                result["value"] = node.data
            }
            is XmlDeclaration -> {
                result["kind"] = "declaration"
                result["name"] = node.name()
                result["declaration"] = node.outerHtml().startsWith("<!")
                result["attributes"] =
                    node
                        .attributes()
                        .filter { it.key != node.nodeName() }
                        .associate { it.key to it.value }
            }
            is DocumentType -> {
                result["kind"] = "doctype"
                result["name"] = node.name()
                result["publicId"] = node.publicId()
                result["systemId"] = node.systemId()
            }
            else -> error("Unsupported legacy DOM node kind")
        }
        if (node is Element)
            result["attributes"] = node.attributes().associate { it.key to it.value }
        result["children"] = node.childNodes().map { indexes[it]!! }
        result
    }

    fun restore(marker: Map<*, *>): Node {
        val state = marker[NODE] as? Map<*, *> ?: error("Legacy DOM marker required")
        val nodes = restoreNodes(state)
        val index = (state["index"] as? Number)?.toDouble() ?: error("DOM index required")
        require(index == index.toInt().toDouble() && index.toInt() in nodes.indices) {
            "Invalid DOM index"
        }
        return nodes[index.toInt()]
    }

    private fun restoreNodes(state: Map<*, *>): List<Node> {
        val schema = (state["schemaVersion"] as? Number)?.toDouble()
        require(schema == 1.0 || schema == 2.0) { "Legacy DOM schema required" }
        val table = state["nodes"] as? List<*> ?: error("DOM node table required")
        require(table.isNotEmpty() && table.size <= 100000) { "DOM node table exceeds bounds" }
        fun index(value: Any?): Int {
            val number = value as? Number ?: error("DOM index required")
            require(
                number.toDouble() == number.toInt().toDouble() && number.toInt() in table.indices
            ) {
                "Invalid DOM index"
            }
            return number.toInt()
        }
        val children = table.map { row ->
            val value = row as? Map<*, *> ?: error("DOM node row required")
            (value["children"] as? List<*> ?: error("DOM children required")).map(::index)
        }
        val roots =
            if (schema == 2.0) {
                require(
                    state["documentId"] is String && (state["documentId"] as String).isNotBlank()
                ) {
                    "DOM document identity required"
                }
                val ids = state["ids"] as? List<*> ?: error("DOM identities required")
                require(
                    ids.size == table.size &&
                        ids.all { it is String && it.isNotBlank() } &&
                        ids.toSet().size == ids.size
                ) {
                    "Invalid DOM identities"
                }
                (state["roots"] as? List<*> ?: error("DOM roots required")).map(::index).also {
                    require(it.isNotEmpty() && it.toSet().size == it.size)
                }
            } else listOf(0)
        val parents = IntArray(table.size)
        children.forEach { edges ->
            edges.forEach { child ->
                require(child !in roots && ++parents[child] == 1) { "DOM must have unique parents" }
            }
        }
        require(parents.indices.all { parents[it] == if (it in roots) 0 else 1 }) {
            "Detached DOM table nodes"
        }
        val seen = BooleanArray(table.size)
        val queue = java.util.ArrayDeque<Int>()
        roots.forEach(queue::add)
        while (queue.isNotEmpty()) {
            val position = queue.removeFirst()
            require(!seen[position]) { "Cyclic DOM table" }
            seen[position] = true
            children[position].forEach(queue::add)
        }
        require(seen.all { it }) { "Unreachable DOM table nodes" }
        val nodes = table.map { row ->
            val value = row as Map<*, *>
            fun text(key: String) = value[key] as? String ?: error("Invalid legacy DOM field")
            val baseUri = text("baseUri")
            val node: Node =
                when (value["kind"]) {
                    "document" ->
                        Document(baseUri).also { document ->
                            (value["parser"] as? Map<*, *>)?.let { parser ->
                                val restoredParser =
                                    if (parser["xml"] == true) Parser.xmlParser()
                                    else Parser.htmlParser()
                                restoredParser.settings(
                                    ParseSettings(
                                        parser["tagCase"] == true,
                                        parser["attributeCase"] == true,
                                    )
                                )
                                document.parser(restoredParser)
                            }
                            val output =
                                value["output"] as? Map<*, *>
                                    ?: error("DOM output settings required")
                            document
                                .outputSettings()
                                .prettyPrint(output["pretty"] as Boolean)
                                .outline(output["outline"] as Boolean)
                                .indentAmount((output["indent"] as Number).toInt())
                                .syntax(
                                    Document.OutputSettings.Syntax.valueOf(
                                        output["syntax"] as String
                                    )
                                )
                                .charset(output["charset"] as String)
                                .escapeMode(
                                    Entities.EscapeMode.valueOf(output["escapeMode"] as String)
                                )
                        }
                    "element" ->
                        Element(
                            Tag.valueOf(text("tag"), text("namespace"), ParseSettings.preserveCase)
                                .also {
                                    val options = value["tagOptions"] as? Number
                                    if (options != null) {
                                        val bits = options.toInt()
                                        require(
                                            bits.toDouble() == options.toDouble() &&
                                                bits >= 0 &&
                                                bits <=
                                                    tagFlags.fold(0) { all, flag -> all or flag }
                                        )
                                        tagFlags.forEach { flag ->
                                            if (bits and flag != 0) it.set(flag) else it.clear(flag)
                                        }
                                        if (bits and Tag.Known == 0) it.clear(Tag.Known)
                                    } else if (value["selfClosing"] == true) it.set(Tag.SelfClose)
                                },
                            if (value.containsKey("baseUriOwn")) value["baseUriOwn"] as? String
                            else baseUri,
                        )
                    "cdata" -> CDataNode(text("value"))
                    "text" -> TextNode(text("value"))
                    "data" -> DataNode(text("value"))
                    "comment" -> Comment(text("value"))
                    "declaration" ->
                        XmlDeclaration(text("name"), value["declaration"] == true).also {
                            declaration ->
                            (value["attributes"] as? Map<*, *>)?.forEach { (key, item) ->
                                declaration.attr(key as String, item as String)
                            }
                        }
                    "doctype" -> DocumentType(text("name"), text("publicId"), text("systemId"))
                    else -> error("Unsupported legacy DOM node kind")
                }
            if (node is Element) {
                val attrs = value["attributes"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
                attrs.forEach { (key, item) ->
                    node.attributes().put(key as String, item as String)
                }
            }
            node
        }
        children.forEachIndexed { position, edges ->
            require(nodes[position] is Element || edges.isEmpty()) {
                "DOM leaf cannot have children"
            }
            edges.forEach { (nodes[position] as Element).appendChild(nodes[it]) }
        }
        return nodes
    }

    fun restoreValue(value: Any?): Any? =
        when {
            value is Map<*, *> && value.containsKey(NODE) -> restore(value)
            value is Map<*, *> && value.containsKey(LIST) -> restoreElements(value)
            value is List<*> -> value.map(::restoreValue)
            else -> value
        }

    private fun restoreElements(marker: Map<*, *>): Elements {
        val state = marker[LIST] as? Map<*, *> ?: error("DOM list state required")
        val indexes = state["indexes"] as? List<*> ?: error("DOM list indexes required")
        val nodes = restoreNodes(state)
        val elements =
            Elements(
                indexes.map {
                    val number = it as? Number ?: error("DOM list index required")
                    require(
                        number.toInt().toDouble() == number.toDouble() &&
                            number.toInt() in nodes.indices
                    )
                    nodes[number.toInt()] as? Element ?: error("DOM element list required")
                }
            )
        return elements
    }

    fun call(marker: Map<*, *>, operation: String, arguments: List<Any?>): Any? {
        val state = (marker[NODE] ?: marker[LIST]) as? Map<*, *>
        if ((state?.get("schemaVersion") as? Number)?.toDouble() == 2.0)
            return callMutable(marker, operation, arguments)
        if (marker.containsKey(LIST)) {
            val elements = restoreElements(marker)
            fun noArgs() = require(arguments.isEmpty()) { "Unsupported DOM list overload" }
            fun selector(): String {
                require(arguments.size == 1)
                return arguments[0] as? String ?: error("DOM string required")
            }
            val result =
                when (operation) {
                    "toString" -> {
                        noArgs()
                        elements.toString()
                    }
                    "text" -> {
                        noArgs()
                        elements.text()
                    }
                    "html" -> {
                        noArgs()
                        elements.html()
                    }
                    "attr" -> elements.attr(selector())
                    "select" -> elements.select(selector()).toList()
                    else -> error("Unsupported legacy DOM list operation")
                }
            return serialize(result)
        }
        val node = restore(marker)
        fun arity(count: Int) = require(arguments.size == count) { "Unsupported DOM overload" }
        fun text(): String {
            arity(1)
            return arguments[0] as? String ?: error("DOM string required")
        }
        fun element(): Element = node as? Element ?: error("DOM element required")
        val result: Any? =
            when (operation) {
                "outerHtml" -> {
                    arity(0)
                    node.outerHtml()
                }
                "attr" -> node.attr(text())
                "hasAttr" -> node.hasAttr(text())
                "text" -> {
                    arity(0)
                    element().text()
                }
                "ownText" -> {
                    arity(0)
                    element().ownText()
                }
                "html" -> {
                    arity(0)
                    element().html()
                }
                "data" -> {
                    arity(0)
                    element().data()
                }
                "tagName" -> {
                    arity(0)
                    element().tagName()
                }
                "id" -> {
                    arity(0)
                    element().id()
                }
                "className" -> {
                    arity(0)
                    element().className()
                }
                "select" -> element().select(text()).toList()
                "selectFirst" -> element().selectFirst(text())
                "getElementsByTag" -> element().getElementsByTag(text()).toList()
                "getElementsByClass" -> element().getElementsByClass(text()).toList()
                "getElementById" -> element().getElementById(text())
                "parent" -> {
                    arity(0)
                    node.parentNode()
                }
                "children" -> {
                    arity(0)
                    element().children().toList()
                }
                "nextElementSibling" -> {
                    arity(0)
                    element().nextElementSibling()
                }
                "previousElementSibling" -> {
                    arity(0)
                    element().previousElementSibling()
                }
                else -> error("Unsupported legacy DOM operation")
            }
        return serialize(result)
    }

    private class MutableGraph(
        val documentId: String,
        val nodes: MutableList<Node>,
        val ids: MutableList<String>,
    ) {
        val merged = linkedSetOf(documentId)
        var retained: MutableSet<String>? = null

        fun prune(value: Any?) {
            val keepIds = retained ?: return
            fun resultIds(result: Any?) {
                when (result) {
                    is Node ->
                        nodes
                            .indexOfFirst { it === result }
                            .takeIf { it >= 0 }
                            ?.let { keepIds.add(ids[it]) }
                    is Iterable<*> -> result.forEach(::resultIds)
                }
            }
            resultIds(value)
            if (nodes.isNotEmpty()) keepIds.add(ids.first())
            val keepRoots =
                java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Node, Boolean>())
            ids.forEachIndexed { index, id ->
                if (id in keepIds) keepRoots.add(nodes[index].root())
            }
            val keep = nodes.indices.filter { nodes[it].root() in keepRoots }
            val nextNodes = keep.map { nodes[it] }
            val nextIds = keep.map { ids[it] }
            nodes.clear()
            nodes.addAll(nextNodes)
            ids.clear()
            ids.addAll(nextIds)
        }

        fun discover(value: Any?) {
            when (value) {
                is Node ->
                    if (nodes.none { it === value }) {
                        nodes.add(value)
                        ids.add(java.util.UUID.randomUUID().toString())
                    }
                is Iterable<*> -> value.forEach(::discover)
            }
            var offset = 0
            while (offset < nodes.size) nodes[offset++].childNodes().forEach { child ->
                if (nodes.none { it === child }) {
                    require(nodes.size < 100000)
                    nodes.add(child)
                    ids.add(java.util.UUID.randomUUID().toString())
                }
            }
        }

        fun indexes(): java.util.IdentityHashMap<Node, Int> =
            java.util.IdentityHashMap<Node, Int>().also { map ->
                nodes.forEachIndexed { index, node -> map[node] = index }
            }

        fun selected(marker: Map<*, *>): Node {
            val state = marker[NODE] as? Map<*, *> ?: error("DOM node argument required")
            val position = (state["index"] as? Number)?.toDouble() ?: error("DOM index required")
            val sourceIds = state["ids"] as? List<*> ?: error("DOM identities required")
            require(
                position == position.toInt().toDouble() && position.toInt() in sourceIds.indices
            )
            val id = sourceIds[position.toInt()] as String
            return nodes[ids.indexOf(id).also { require(it >= 0) { "Unknown DOM identity" } }]
        }
    }

    private fun mutableGraph(state: Map<*, *>): MutableGraph {
        require((state["schemaVersion"] as? Number)?.toDouble() == 2.0)
        return MutableGraph(
                state["documentId"] as String,
                restoreNodes(state).toMutableList(),
                (state["ids"] as List<*>).map { it as String }.toMutableList(),
            )
            .also { graph ->
                (state["retainIds"] as? List<*>)?.let { values ->
                    require(values.all { it is String && it in graph.ids }) {
                        "Invalid retained DOM identity"
                    }
                    graph.retained = values.map { it as String }.toMutableSet()
                }
            }
    }

    private fun callMutable(marker: Map<*, *>, operation: String, args: List<Any?>): Any? {
        val state = (marker[NODE] ?: marker[LIST]) as Map<*, *>
        val graph = mutableGraph(state)
        fun arity(count: Int) = require(args.size == count) { "Unsupported DOM overload" }
        fun text(index: Int = 0) = args.getOrNull(index) as? String ?: error("DOM string required")
        fun argumentNode(index: Int): Node {
            val value = args.getOrNull(index) as? Map<*, *> ?: error("DOM node argument required")
            val other = value[NODE] as? Map<*, *> ?: error("DOM node marker required")
            if (other["documentId"] != graph.documentId) {
                val imported = mutableGraph(other)
                require(imported.ids.none { it in graph.ids }) { "Colliding DOM identities" }
                graph.nodes.addAll(imported.nodes)
                graph.ids.addAll(imported.ids)
                graph.merged.add(imported.documentId)
                if (graph.retained != null && imported.retained != null)
                    graph.retained!!.addAll(imported.retained!!)
                else graph.retained = null
                require(graph.nodes.size <= 100000)
            }
            return graph.selected(value)
        }
        val mutation =
            operation in
                setOf(
                    "remove",
                    "appendChild",
                    "append",
                    "appendElement",
                    "createElement",
                    "empty",
                ) || (operation in setOf("attr", "text", "html", "title") && args.isNotEmpty())
        val selected =
            if (marker.containsKey(LIST)) {
                val positions = state["indexes"] as? List<*> ?: error("DOM indexes required")
                Elements(
                    positions.map { item ->
                        val n = (item as? Number)?.toDouble() ?: error("DOM index required")
                        require(n == n.toInt().toDouble() && n.toInt() in graph.nodes.indices)
                        graph.nodes[n.toInt()] as? Element ?: error("DOM element list required")
                    }
                )
            } else null
        val node = if (selected == null) graph.selected(marker) else null
        fun element() = node as? Element ?: error("DOM element required")
        fun document() = node as? Document ?: error("DOM document required")
        graph.retained?.let { retained ->
            if (node != null) retained.add(graph.ids[graph.nodes.indexOfFirst { it === node }])
            selected?.forEach { item ->
                retained.add(graph.ids[graph.nodes.indexOfFirst { it === item }])
            }
        }
        val value: Any? =
            if (selected != null)
                when (operation) {
                    "toString" -> {
                        arity(0)
                        selected.toString()
                    }
                    "text" -> {
                        arity(0)
                        selected.text()
                    }
                    "html" ->
                        if (args.isEmpty()) selected.html()
                        else {
                            arity(1)
                            selected.html(text())
                        }
                    "attr" ->
                        if (args.size == 1) selected.attr(text())
                        else {
                            arity(2)
                            selected.attr(text(), text(1))
                        }
                    "select" -> {
                        arity(1)
                        selected.select(text())
                    }
                    "remove" -> {
                        arity(0)
                        selected.remove()
                    }
                    "append" -> {
                        arity(1)
                        selected.append(text())
                    }
                    "appendChild" -> {
                        arity(1)
                        selected.append(argumentNode(0))
                    }
                    "empty" -> {
                        arity(0)
                        selected.empty()
                    }
                    else -> error("Unsupported legacy DOM list operation")
                }
            else
                when (operation) {
                    "body" -> {
                        arity(0)
                        document().body()
                    }
                    "head" -> {
                        arity(0)
                        document().head()
                    }
                    "title" ->
                        if (args.isEmpty()) document().title()
                        else {
                            arity(1)
                            document().title(text())
                            null
                        }
                    "createElement" -> {
                        arity(1)
                        document().createElement(text())
                    }
                    "outerHtml" -> {
                        arity(0)
                        node!!.outerHtml()
                    }
                    "attr" ->
                        if (args.size == 1) node!!.attr(text())
                        else {
                            arity(2)
                            node!!.attr(text(), text(1))
                        }
                    "hasAttr" -> {
                        arity(1)
                        node!!.hasAttr(text())
                    }
                    "text" ->
                        if (args.isEmpty()) element().text()
                        else {
                            arity(1)
                            element().text(text())
                        }
                    "ownText" -> {
                        arity(0)
                        element().ownText()
                    }
                    "html" ->
                        if (args.isEmpty()) element().html()
                        else {
                            arity(1)
                            element().html(text())
                        }
                    "data" -> {
                        arity(0)
                        element().data()
                    }
                    "tagName" -> {
                        arity(0)
                        element().tagName()
                    }
                    "id" -> {
                        arity(0)
                        element().id()
                    }
                    "className" -> {
                        arity(0)
                        element().className()
                    }
                    "select" -> {
                        arity(1)
                        element().select(text())
                    }
                    "selectFirst" -> {
                        arity(1)
                        element().selectFirst(text())
                    }
                    "getElementsByTag" -> {
                        arity(1)
                        element().getElementsByTag(text())
                    }
                    "getElementsByClass" -> {
                        arity(1)
                        element().getElementsByClass(text())
                    }
                    "getElementById" -> {
                        arity(1)
                        element().getElementById(text())
                    }
                    "parent" -> {
                        arity(0)
                        node!!.parentNode()
                    }
                    "children" -> {
                        arity(0)
                        element().children()
                    }
                    "nextElementSibling" -> {
                        arity(0)
                        element().nextElementSibling()
                    }
                    "previousElementSibling" -> {
                        arity(0)
                        element().previousElementSibling()
                    }
                    "remove" -> {
                        arity(0)
                        node!!.remove()
                        null
                    }
                    "empty" -> {
                        arity(0)
                        element().empty()
                    }
                    "appendChild" -> {
                        arity(1)
                        element().appendChild(argumentNode(0))
                    }
                    "append" -> {
                        arity(1)
                        element().append(text())
                    }
                    "appendElement" -> {
                        arity(1)
                        element().appendElement(text())
                    }
                    else -> error("Unsupported legacy DOM operation")
                }
        graph.discover(value)
        graph.prune(value)
        val indexes = graph.indexes()
        val rows = captureRows(graph.nodes, indexes)
        val roots = graph.nodes.indices.filter { graph.nodes[it].parentNode() == null }
        val updated =
            mapOf(
                "schemaVersion" to 2,
                "documentId" to graph.documentId,
                "nodes" to rows,
                "ids" to graph.ids,
                "roots" to roots,
            )
        fun encoded(item: Any?): Any? =
            when (item) {
                is Node -> mapOf(NODE to (updated + ("index" to indexes[item]!!)))
                is Iterable<*> -> {
                    val values = item.toList()
                    if (values.all { it is Element })
                        mapOf(LIST to (updated + ("indexes" to values.map { indexes[it]!! })))
                    else values.map { encoded(it) }
                }
                else -> item
            }
        return mapOf(
            "__legacyDomUpdate" to
                (updated +
                    mapOf("mergedDocumentIds" to graph.merged.toList(), "mutated" to mutation)),
            "value" to encoded(value),
        )
    }
}
