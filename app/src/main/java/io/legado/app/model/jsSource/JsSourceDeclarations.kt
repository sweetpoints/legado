package io.legado.app.model.jsSource

/**
 * Static capability hints, without evaluating programs or parsing function bodies with an execution
 * engine. Only direct top-level bindings are recognized.
 */
internal object JsSourceDeclarations {
    fun declares(text: String, names: Set<String>): Boolean {
        val tokens = runCatching { Lexer(text).tokens() }.getOrNull() ?: return false
        val found = hashSetOf<String>()
        var braces = 0
        var parens = 0
        var brackets = 0
        var expressionBodyEnd = -1
        tokens.forEachIndexed { index, token ->
            val current = token.value
            if (braces == 0 && parens == 0 && brackets == 0 && index > expressionBodyEnd) {
                if (current == "function") {
                    val previous = tokens.getOrNull(index - 1)
                    val beforeAsync = tokens.getOrNull(index - 2)
                    val boundary = if (previous?.value == "async") beforeAsync else previous
                    if (
                        boundary == null ||
                            boundary.value in setOf(";", "}") ||
                            text.substring(boundary.end, token.start).contains('\n') &&
                                boundary.value !in setOf("=", ",", ":")
                    ) {
                        val name =
                            tokens
                                .getOrNull(
                                    index + if (tokens.getOrNull(index + 1)?.value == "*") 2 else 1
                                )
                                ?.value
                        if (name in names) found.add(name!!)
                    }
                }
                if (
                    current in names &&
                        tokens.getOrNull(index + 1)?.value == "=" &&
                        tokens.getOrNull(index - 1)?.value !in setOf(".", "?.")
                ) {
                    if (functionValue(tokens, index + 2)) found.add(current)
                    else found.remove(current)
                }
            }
            if (
                current == "=>" &&
                    braces == 0 &&
                    parens == 0 &&
                    brackets == 0 &&
                    tokens.getOrNull(index + 1)?.value != "{"
            ) {
                var nested = 0
                expressionBodyEnd = tokens.lastIndex
                for (end in index + 1 until tokens.size) {
                    val value = tokens[end].value
                    if (nested == 0 && value in setOf(";", ",")) {
                        expressionBodyEnd = end - 1
                        break
                    }
                    if (value in setOf("(", "[", "{")) nested++
                    if (value in setOf(")", "]", "}")) nested--
                }
            }
            when (current) {
                "{" -> braces++
                "}" -> braces--
                "(" -> parens++
                ")" -> parens--
                "[" -> brackets++
                "]" -> brackets--
            }
            if (braces < 0 || parens < 0 || brackets < 0) return false
        }
        return braces == 0 && parens == 0 && brackets == 0 && found.containsAll(names)
    }

    /** Only literal direct config/source timestamps; expressions and nested keys stay unchanged. */
    fun timestampRanges(text: String): List<IntRange> {
        val tokens = runCatching { Lexer(text).tokens() }.getOrNull() ?: return emptyList()
        val result = arrayListOf<IntRange>()
        var depth = 0
        var parens = 0
        var brackets = 0
        for (index in tokens.indices) {
            val token = tokens[index]
            if (
                depth == 0 &&
                    parens == 0 &&
                    brackets == 0 &&
                    token.value in setOf("config", "source") &&
                    tokens.getOrNull(index - 1)?.value in setOf("var", "let", "const") &&
                    tokens.getOrNull(index + 1)?.value == "=" &&
                    tokens.getOrNull(index + 2)?.value == "{"
            ) {
                val end = matching(tokens, index + 2, "{", "}") ?: return emptyList()
                var nested = 0
                for (property in index + 3 until end) {
                    val key = tokens[property]
                    val literalKey = text.substring(key.start, key.end)
                    if (
                        nested == 0 &&
                            (key.value == "lastUpdateTime" ||
                                literalKey in setOf("'lastUpdateTime'", "\"lastUpdateTime\"")) &&
                            tokens.getOrNull(property - 1)?.value in setOf("{", ",") &&
                            tokens.getOrNull(property + 1)?.value == ":"
                    ) {
                        val valueIndex = property + 2
                        val value = tokens.getOrNull(valueIndex) ?: continue
                        val number =
                            Regex(
                                    "(?:0[xX][0-9a-fA-F]+|0[bB][01]+|0[oO][0-7]+|[0-9]+(?:\\.[0-9]*)?(?:[eE][+-]?[0-9]+)?)"
                                )
                                .matches(value.value)
                        val date =
                            tokens.subList(valueIndex, minOf(valueIndex + 5, tokens.size)).map {
                                it.value
                            } == listOf("Date", ".", "now", "(", ")")
                        val valueEnd = valueIndex + if (date) 4 else 0
                        if (
                            (number || date) &&
                                tokens.getOrNull(valueEnd + 1)?.value in setOf(",", "}")
                        ) {
                            result.add(value.start until tokens[valueEnd].end)
                        }
                    }
                    if (key.value in setOf("{", "(", "[")) nested++
                    if (key.value in setOf("}", ")", "]")) nested--
                }
            }
            when (token.value) {
                "{" -> depth++
                "}" -> depth--
                "(" -> parens++
                ")" -> parens--
                "[" -> brackets++
                "]" -> brackets--
            }
        }
        if (depth != 0 || parens != 0 || brackets != 0) return emptyList()
        return result
    }

    private fun functionValue(tokens: List<Token>, start: Int): Boolean {
        var index = start
        if (tokens.getOrNull(index)?.value == "async") index++
        if (tokens.getOrNull(index)?.value == "function") {
            index++
            if (tokens.getOrNull(index)?.value == "*") index++
            if (tokens.getOrNull(index)?.value != "(") index++ // optional expression name
            val paramsEnd = matching(tokens, index, "(", ")") ?: return false
            val bodyEnd = matching(tokens, paramsEnd + 1, "{", "}") ?: return false
            // An immediately invoked function is not a function-valued binding.
            return tokens.getOrNull(bodyEnd + 1)?.value !in
                setOf("(", ".", "?.", "[", "?", "+", "-", "||", "&&")
        }
        if (tokens.getOrNull(index)?.value == "(") {
            index = (matching(tokens, index, "(", ")") ?: return false) + 1
        } else {
            if (tokens.getOrNull(index)?.identifier != true) return false
            index++
        }
        return tokens.getOrNull(index)?.value == "=>"
    }

    private fun matching(tokens: List<Token>, start: Int, open: String, close: String): Int? {
        if (tokens.getOrNull(start)?.value != open) return null
        var depth = 0
        for (index in start until tokens.size) {
            if (tokens[index].value == open) depth++
            if (tokens[index].value == close && --depth == 0) return index
        }
        return null
    }

    private data class Token(
        val value: String,
        val start: Int,
        val end: Int,
        val identifier: Boolean = false,
    )

    private class Lexer(private val text: String) {
        private var position = 0
        private var previous = ""

        fun tokens(): List<Token> {
            val result = arrayListOf<Token>()
            while (position < text.length) {
                val token = next() ?: continue
                result.add(token)
                previous = token.value
            }
            return result
        }

        private fun next(): Token? {
            val start = position
            val c = text[position++]
            if (c.isWhitespace()) return null
            if (c == '/' && position < text.length && text[position] == '/') {
                position = text.indexOf('\n', position).let { if (it < 0) text.length else it }
                return null
            }
            if (c == '/' && position < text.length && text[position] == '*') {
                val end = text.indexOf("*/", position + 1)
                require(end >= 0)
                position = end + 2
                return null
            }
            if (c == '\'' || c == '"') {
                quoted(c)
                return Token("literal", start, position)
            }
            if (c == '`') {
                template()
                return Token("literal", start, position)
            }
            if (
                c == '/' &&
                    previous in
                        setOf(
                            "",
                            "=",
                            "(",
                            "[",
                            "{",
                            ",",
                            ":",
                            ";",
                            "return",
                            "throw",
                            "=>",
                            "!",
                            "?",
                            "&&",
                            "||",
                        )
            ) {
                regex()
                return Token("literal", start, position)
            }
            if (c.isLetter() || c == '_' || c == '$') {
                while (
                    position < text.length &&
                        (text[position].isLetterOrDigit() || text[position] in "_$")
                ) position++
                return Token(text.substring(start, position), start, position, true)
            }
            if (c.isDigit()) {
                val number =
                    Regex(
                            "(?:0[xX][0-9a-fA-F]+|0[bB][01]+|0[oO][0-7]+|[0-9]+(?:\\.[0-9]*)?(?:[eE][+-]?[0-9]+)?)"
                        )
                        .find(text, start)!!
                position = number.range.last + 1
                return Token(number.value, start, position)
            }
            val pair = if (position < text.length) "$c${text[position]}" else ""
            if (
                pair in
                    setOf("=>", "?.", "&&", "||", "==", "!=", "++", "--", "+=", "-=", "*=", "/=")
            ) {
                position++
                return Token(pair, start, position)
            }
            return Token(c.toString(), start, position)
        }

        private fun quoted(quote: Char) {
            while (position < text.length) {
                val c = text[position++]
                if (c == '\\') {
                    require(position < text.length)
                    position++
                } else if (c == quote) return else require(c != '\n' && c != '\r')
            }
            error("Unclosed string")
        }

        private fun regex() {
            var inClass = false
            while (position < text.length) {
                when (val c = text[position++]) {
                    '\\' -> {
                        require(position < text.length)
                        position++
                    }
                    '[' -> inClass = true
                    ']' -> inClass = false
                    '/' ->
                        if (!inClass) {
                            while (position < text.length && text[position].isLetter()) position++
                            return
                        }
                    else -> require(c != '\n' && c != '\r')
                }
            }
            error("Unclosed regex")
        }

        private fun template() {
            while (position < text.length) {
                when (text[position++]) {
                    '\\' -> {
                        require(position < text.length)
                        position++
                    }
                    '`' -> return
                    '$' ->
                        if (position < text.length && text[position] == '{') {
                            position++
                            var depth = 1
                            previous = "{"
                            while (position < text.length && depth > 0) {
                                val token = next() ?: continue
                                if (token.value == "{") depth++
                                if (token.value == "}") depth--
                                previous = token.value
                            }
                            require(depth == 0)
                        }
                }
            }
            error("Unclosed template")
        }
    }
}
