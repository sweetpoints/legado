package io.legado.app.data.association

import io.legado.app.data.entities.HighlightRuleFile

/** Preserve legacy first-match classification, including overlapping and malformed rule records. */
fun associationJsonImportType(map: Map<String, *>): String? =
    when {
        map["type"] == HighlightRuleFile.TYPE -> "highlightRule"

        map.containsKey("bookSourceUrl") -> "bookSource"

        map.containsKey("sourceUrl") -> "rssSource"

        map.containsKey("pattern") &&
            map.containsKey("style") &&
            map.containsKey("uuid") &&
            !map.containsKey("replacement") -> "highlightRule"

        map.containsKey("pattern") && (map.containsKey("style") || map.containsKey("uuid")) -> null

        map.containsKey("pattern") -> "replaceRule"

        map.containsKey("themeName") -> "theme"

        map.containsKey("showRule") -> "dictRule"

        map.containsKey("name") && map.containsKey("rule") -> "txtRule"

        map.containsKey("cron") && map.containsKey("script") -> "autoTask"

        map.containsKey("name") && map.containsKey("url") -> "httpTts"

        map.containsKey("name") && map.containsKey("author") -> "bookshelf"

        else -> null
    }
