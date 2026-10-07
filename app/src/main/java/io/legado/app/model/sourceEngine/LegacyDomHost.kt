package io.legado.app.model.sourceEngine

import org.seimicrawler.xpath.JXNode
import org.jsoup.nodes.*
import org.jsoup.parser.ParseSettings
import org.jsoup.parser.Tag
import org.jsoup.select.Elements

/** Read-only Jsoup JSON snapshots. No process/task registry or Java object references. */
object LegacyDomHost {
    const val NODE = "__legacyDom"
    const val LIST = "__legacyDomList"

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
        val table = nodes.map { node ->
            val result = mutableMapOf<String, Any?>("baseUri" to node.baseUri())
            when (node) {
                is Document -> {
                    result["kind"] = "document"
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
        return table to indexes
    }

    fun restore(marker: Map<*, *>): Node {
        val state = marker[NODE] as? Map<*, *> ?: error("Legacy DOM marker required")
        require((state["schemaVersion"] as? Number)?.toDouble() == 1.0) {
            "Legacy DOM schema required"
        }
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
        val parents = IntArray(table.size)
        children.forEach { edges ->
            edges.forEach { child ->
                require(child != 0 && ++parents[child] == 1) { "DOM must have unique parents" }
            }
        }
        require(parents.drop(1).all { it == 1 }) { "Detached DOM table nodes" }
        val seen = BooleanArray(table.size)
        val queue = java.util.ArrayDeque<Int>()
        queue.add(0)
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
                                    if (value["selfClosing"] == true) it.set(Tag.SelfClose)
                                },
                            baseUri,
                        )
                    "text" -> TextNode(text("value"))
                    "data" -> DataNode(text("value"))
                    "comment" -> Comment(text("value"))
                    "doctype" -> DocumentType(text("name"), text("publicId"), text("systemId"))
                    else -> error("Unsupported legacy DOM node kind")
                }
            if (node is Element) {
                val attrs = value["attributes"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
                attrs.forEach { (key, item) -> node.attr(key as String, item as String) }
            }
            node
        }
        children.forEachIndexed { position, edges ->
            require(nodes[position] is Element || edges.isEmpty()) {
                "DOM leaf cannot have children"
            }
            edges.forEach { (nodes[position] as Element).appendChild(nodes[it]) }
        }
        return nodes[index(state["index"])]
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
        val root =
            restore(
                mapOf(
                    NODE to
                        mapOf(
                            "schemaVersion" to state["schemaVersion"],
                            "nodes" to state["nodes"],
                            "index" to 0,
                        )
                )
            )
        val rows = state["nodes"] as List<*>
        val nodes = arrayOfNulls<Node>(rows.size)
        nodes[0] = root
        val queue = java.util.ArrayDeque<Int>()
        queue.add(0)
        while (queue.isNotEmpty()) {
            val position = queue.removeFirst()
            val edges = (rows[position] as Map<*, *>)["children"] as List<*>
            edges.forEachIndexed { offset, target ->
                val index = (target as Number).toInt()
                nodes[index] = nodes[position]!!.childNode(offset)
                queue.add(index)
            }
        }
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
        if (marker.containsKey(LIST)) {
            val elements = restoreElements(marker)
            fun noArgs() = require(arguments.isEmpty()) { "Unsupported DOM list overload" }
            fun selector(): String {
                require(arguments.size == 1)
                return arguments[0] as? String ?: error("DOM string required")
            }
            val result =
                when (operation) {
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
}
