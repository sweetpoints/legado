package io.legado.app.ui.components.markdown

import kotlin.math.roundToInt
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

data class RichTextStyle(val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false,
    val underline: Boolean = false, val strike: Boolean = false, val color: Int? = null, val background: Int? = null, val link: String? = null)
sealed interface RichInline {
    data class Text(val text: String, val style: RichTextStyle = RichTextStyle()) : RichInline
    data class Image(val source: String, val description: String, val link: String?) : RichInline
}
sealed interface RichBlock {
    data class Leaf(val id: Int, val content: List<RichInline>, val heading: Int = 0, val code: Boolean = false,
        val header: Boolean = false, val alignment: String? = null) : RichBlock {
        val text get() = content.joinToString("") { when (it) { is RichInline.Text -> it.text; is RichInline.Image -> "\uFFFC" } }
    }
    data class Quote(val children: List<RichBlock>) : RichBlock
    data class ListBlock(val items: List<List<RichBlock>>, val start: Int?) : RichBlock
    data class Table(val rows: List<List<Leaf>>) : RichBlock
    data object Divider : RichBlock
}
data class RichDocument(val blocks: List<RichBlock>) {
    val leaves: List<RichBlock.Leaf> get() = buildList {
        fun visit(block: RichBlock) { when (block) {
            is RichBlock.Leaf -> add(block); is RichBlock.Quote -> block.children.forEach(::visit)
            is RichBlock.ListBlock -> block.items.flatten().forEach(::visit); is RichBlock.Table -> addAll(block.rows.flatten()); RichBlock.Divider -> Unit
        } }; blocks.forEach(::visit)
    }
    val text get() = leaves.joinToString("\n") { it.text }
}
fun projectTextDocument(content: String, mode: String): RichDocument {
    if (mode == "TEXT") return RichDocument(listOf(RichBlock.Leaf(0, listOf(RichInline.Text(if (content.length >= 32 * 1024) content.take(32 * 1024) + "\n\n数据太大，无法全部显示…" else content)))))
    val html = if (mode == "MD") {
        val extensions = listOf(TablesExtension.create())
        HtmlRenderer.builder().extensions(extensions).build().render(Parser.builder().extensions(extensions).build().parse(content))
    } else content
    return HtmlRichProjection().parse(html)
}
private class HtmlRichProjection {
    private var next = 0
    fun parse(html: String) = RichDocument(blocks(Jsoup.parseBodyFragment(html).body()))
    private val blockTags = setOf("p", "div", "section", "article", "header", "footer", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "blockquote", "ul", "ol", "table", "hr")
    private fun leaf(nodes: List<Node>, heading: Int = 0, code: Boolean = false, header: Boolean = false, alignment: String? = null): RichBlock.Leaf =
        RichBlock.Leaf(next++, nodes.flatMap { inline(it, RichTextStyle(code = code, bold = header), code) }, heading, code, header, alignment)
    private fun blocks(parent: Element): List<RichBlock> = buildList {
        val pending = mutableListOf<Node>()
        fun flush() { if (pending.isNotEmpty()) { val value = leaf(pending.toList()); if (value.text.isNotBlank()) add(value); pending.clear() } }
        parent.childNodes().forEach { node ->
            val element = node as? Element; val tag = element?.normalName()
            if (tag == "script" || tag == "style") return@forEach
            if (element == null || tag !in blockTags) { pending += node; return@forEach }
            flush()
            when (tag) {
                "blockquote" -> add(RichBlock.Quote(blocks(element)))
                "ul", "ol" -> add(RichBlock.ListBlock(element.children().filter { it.normalName() == "li" }.map { blocks(it) }, if (tag == "ol") element.attr("start").toIntOrNull() ?: 1 else null))
                "table" -> add(RichBlock.Table(element.select("tr").map { row -> row.children().filter { it.normalName() in setOf("td", "th") }.map { cell ->
                    leaf(cell.childNodes(), header = cell.normalName() == "th", alignment = cell.attr("align").ifBlank { css(cell)["text-align"].orEmpty() }.uppercase())
                } }))
                "hr" -> add(RichBlock.Divider)
                "pre" -> add(leaf(element.childNodes(), code = true))
                "h1", "h2", "h3", "h4", "h5", "h6" -> add(leaf(element.childNodes(), heading = tag.last().digitToInt()))
                else -> if (element.children().any { it.normalName() in blockTags }) addAll(blocks(element)) else add(leaf(listOf(element)))
            }
        }
        flush()
    }
    private fun inline(node: Node, style: RichTextStyle, preserve: Boolean): List<RichInline> {
        if (node is TextNode) return listOf(RichInline.Text(if (preserve) node.wholeText else node.text(), style))
        val element = node as? Element ?: return emptyList(); val tag = element.normalName(); val css = css(element)
        if (tag in setOf("script", "style")) return emptyList()
        val current = style.copy(bold = style.bold || tag in setOf("b", "strong", "th") || css["font-weight"] == "bold" || (css["font-weight"]?.toIntOrNull() ?: 0) >= 600,
            italic = style.italic || tag in setOf("em", "i", "cite") || css["font-style"] == "italic", code = style.code || tag in setOf("code", "tt", "pre"),
            underline = style.underline || tag == "u" || css["text-decoration"].orEmpty().contains("underline"),
            strike = style.strike || tag in setOf("s", "strike", "del") || css["text-decoration"].orEmpty().contains("line-through"),
            color = richColor(css["color"] ?: element.attr("color")) ?: style.color,
            background = richColor(css["background-color"]) ?: style.background,
            link = if (tag == "a" && element.hasAttr("href")) element.attr("href") else style.link)
        return when (tag) {
            "img" -> listOf(RichInline.Image(element.attr("src"), element.attr("alt"), current.link))
            "br" -> listOf(RichInline.Text("\n", current))
            else -> element.childNodes().flatMap { inline(it, current, preserve || tag == "pre") }
        }
    }
    private fun css(element: Element): Map<String, String> = element.attr("style").split(';').mapNotNull { pair ->
        val index = pair.indexOf(':'); if (index < 0) null else pair.substring(0, index).trim().lowercase() to pair.substring(index + 1).trim().lowercase()
    }.toMap()
}
internal fun richColor(value: String?): Int? {
    val text = value?.trim()?.lowercase() ?: return null
    if (text.startsWith("#")) {
        val hex = text.drop(1); val normalized = when (hex.length) { 3 -> hex.flatMap { listOf(it, it) }.joinToString(""); 6, 8 -> hex; else -> return null }
        val parsed = normalized.toLongOrNull(16)?.toInt() ?: return null
        return if (normalized.length == 8) parsed else parsed or 0xff000000.toInt()
    }
    val functional = Regex("""(rgb|rgba)\(([^)]+)\)""").matchEntire(text)
    if (functional != null) {
        val parts = functional.groupValues[2].split(',').map { it.trim() }
        if (parts.size != if (functional.groupValues[1] == "rgba") 4 else 3) return null
        fun channel(raw: String): Int? {
            val number = raw.removeSuffix("%").toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            return (if (raw.endsWith('%')) number.coerceIn(0.0, 100.0) * 255 / 100 else number.coerceIn(0.0, 255.0)).roundToInt()
        }
        val red = channel(parts[0]) ?: return null; val green = channel(parts[1]) ?: return null; val blue = channel(parts[2]) ?: return null
        val alpha = if (parts.size == 4) {
            val raw = parts[3]; val number = raw.removeSuffix("%").toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            ((if (raw.endsWith('%')) number / 100 else number).coerceIn(0.0, 1.0) * 255).roundToInt()
        } else 255
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }
    return mapOf("black" to 0xff000000L, "white" to 0xffffffffL, "red" to 0xffff0000L, "green" to 0xff008000L,
        "cyan" to 0xff00ffffL, "aqua" to 0xff00ffffL, "magenta" to 0xffff00ffL, "fuchsia" to 0xffff00ffL,
        "darkgray" to 0xff444444L, "darkgrey" to 0xff444444L, "lightgray" to 0xffccccccL, "lightgrey" to 0xffccccccL,
        "lime" to 0xff00ff00L, "maroon" to 0xff800000L, "navy" to 0xff000080L, "olive" to 0xff808000L,
        "purple" to 0xff800080L, "silver" to 0xffc0c0c0L, "teal" to 0xff008080L,
        "blue" to 0xff0000ffL, "yellow" to 0xffffff00L, "gray" to 0xff808080L, "grey" to 0xff808080L, "transparent" to 0x00000000L).get(text)?.toInt()
}
