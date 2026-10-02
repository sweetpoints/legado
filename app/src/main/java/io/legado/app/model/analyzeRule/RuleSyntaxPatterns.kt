package io.legado.app.model.analyzeRule

import java.util.regex.Pattern

/** Shared rule syntax definitions for Compose projections and the native code editor. */
val legadoPattern: Pattern = Pattern.compile("\\|\\||&&|%%|@js:|@Json:|@css:|@@|@XPath:|@webjs:")
val jsonPattern: Pattern = Pattern.compile("\"[A-Za-z0-9]*?\"\\:|\"|\\{|\\}|\\[|\\]")
val wrapPattern: Pattern = Pattern.compile("\\\\n")
val operationPattern: Pattern =
    Pattern.compile(":|==|>|<|!=|>=|<=|->|=|%|-|-=|%=|\\+|\\-|\\-=|\\+=|\\^|\\&|\\|::|\\?|\\*")
val jsPattern: Pattern = Pattern.compile(
    "\\b(?:var|let|const|function|return|if|else|for|while|do|break|continue|switch|case|default|" +
        "try|catch|finally|throw|new|delete|typeof|instanceof|in|of|void|this|true|false|null|undefined)\\b"
)
