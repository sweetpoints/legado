package io.legado.app.help.source

/** Old menus are often JavaScript array literals with a final comma. Keep quoted rules intact. */
internal fun normalizeLegacyExploreJson(json: String): String = buildString(json.length) {
    var quote: Char? = null
    var escaped = false
    for ((index, char) in json.withIndex()) {
        if (quote != null) {
            append(char)
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == quote) quote = null
        } else {
            if (char == '"' || char == '\'') quote = char
            if (char == ',') {
                var next = index + 1
                while (next < json.length && json[next].isWhitespace()) next++
                val previous = lastOrNull { !it.isWhitespace() }
                // Do not turn empty slots ([,] or [item,,]) into valid entries.
                if (next < json.length && json[next] == ']' && previous != '[' && previous != ',') continue
            }
            append(char)
        }
    }
}
